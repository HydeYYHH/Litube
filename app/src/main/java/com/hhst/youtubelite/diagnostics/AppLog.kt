package com.hhst.youtubelite.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.webkit.WebViewCompat
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only operational events enter this sink; resource and frame samples stay in memory. */
object AppLog {
    enum class Category { CRASH, EXTRACTOR, DOWNLOADER, PLAYER }
    @Volatile var playerSummary: String = "No native playback samples"
    private val gson = Gson()
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { job -> Thread(job, "diagnostics").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
    private var context: Context? = null
    private var storage: DiagnosticFiles? = null
    private val session = UUID.randomUUID().toString().take(8)
    private val segments = mutableMapOf<String, File>()
    private var sequence = 0L
    private var lastPrune = 0L
    private val cleaner = Executors.newSingleThreadScheduledExecutor { job -> Thread(job, "diagnostic-cleanup").apply { isDaemon = true } }

    @Synchronized fun initialize(app: Application) {
        if (context != null) return
        context = app.applicationContext
        storage = DiagnosticFiles(File(app.filesDir, "diagnostics"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { append(Category.CRASH, "uncaught", mapOf("thread" to thread.name), failure, true) }
            previous?.uncaughtException(thread, failure)
        }
        cleaner.scheduleAtFixedRate({ writer.execute { runCatching { storage?.prune(); cleanExports(app) } } }, 1, 1, TimeUnit.HOURS)
        writer.execute {
            runCatching {
                storage?.prune()
                cleanExports(app)
                if (Build.VERSION.SDK_INT >= 30 && Application.getProcessName() == app.packageName) collectExits(app)
            }
        }
    }

    fun event(category: Category, event: String, fields: Map<String, Any?> = emptyMap(), failure: Throwable? = null, critical: Boolean = failure != null) {
        if (context == null) return
        writer.execute { runCatching { append(category, event, fields, failure, critical) } }
    }

    @Synchronized private fun append(category: Category, event: String, fields: Map<String, Any?>, failure: Throwable?, critical: Boolean) {
        val app = context ?: return
        val directory = File(app.filesDir, "diagnostics").apply { mkdirs() }
        val key = category.name.lowercase() + if (critical) "-critical" else "-events"
        var file = segments[key]
        var segmentAt = file?.lastModified() ?: System.currentTimeMillis()
        if (file == null || !file.exists() || file.length() >= DiagnosticFiles.SEGMENT_BYTES || System.currentTimeMillis() - segmentAt >= TimeUnit.HOURS.toMillis(1)) {
            segmentAt = System.currentTimeMillis()
            file = File(directory, "$key-${Process.myPid()}-$session-${sequence++}.jsonl")
            segments[key] = file
        }
        val record = linkedMapOf<String, Any>("time" to System.currentTimeMillis(), "session" to session,
            "pid" to Process.myPid(), "category" to category.name, "event" to event.take(100),
            "fields" to fields.mapValues { DiagnosticRedaction.field(it.key, it.value) })
        if (failure != null) record["error"] = DiagnosticRedaction.failure(failure)
        file.appendText(gson.toJson(record) + "\n", Charsets.UTF_8)
        // Age belongs to the segment start, so a quiet process cannot keep old rows alive.
        file.setLastModified(segmentAt)
        if (System.currentTimeMillis() - lastPrune > 30_000 || critical) {
            storage?.prune()
            lastPrune = System.currentTimeMillis()
        }
    }

    /** A queued barrier flushes this process before a bounded, complete-line snapshot. */
    fun export(app: Context, summaries: Map<String, String>): File {
        val done = SettableFuture.create<File>()
        // Export must not be discarded when the ordinary event queue is saturated.
        writer.queue.put(Runnable {
            try {
                storage?.prune()
                cleanExports(app)
                val directory = File(app.cacheDir, "diagnostic-exports").apply { mkdirs() }
                val archive = File(directory, "litube-diagnostics-${System.currentTimeMillis()}.zip")
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    fun entry(name: String, text: String) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                    }
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    entry("device.txt", "App: ${info.versionName}\nAndroid: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n" +
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nWebView: ${WebViewCompat.getCurrentWebViewPackage(app)?.versionName ?: "unavailable"}\n")
                    entry("player.txt", DiagnosticRedaction.text(playerSummary))
                    summaries.forEach { (name, summary) -> entry("$name.txt", DiagnosticRedaction.text(summary)) }
                    val snapshot = storage?.files().orEmpty().sortedBy { it.name }.map { it to it.length().coerceAtMost(2L * 1024 * 1024) }
                    snapshot.forEach { (file, length) ->
                        zip.putNextEntry(ZipEntry("logs/${file.name}"))
                        runCatching {
                            val bytes = ByteArray(length.toInt())
                            RandomAccessFile(file, "r").use { it.readFully(bytes) }
                            // Concurrent process writes after the captured length cannot enter this export.
                            val text = bytes.toString(Charsets.UTF_8)
                            val complete = text.substringBeforeLast('\n', "")
                            complete.lineSequence().forEach { line ->
                                if (line.length <= 64_000 && runCatching { gson.fromJson(line, Map::class.java) }.isSuccess)
                                    zip.write((line + "\n").toByteArray())
                            }
                        }
                        zip.closeEntry()
                    }
                }
                done.set(archive)
            } catch (failure: Throwable) { done.setException(failure) }
        })
        writer.prestartCoreThread()
        return done.get(30, TimeUnit.SECONDS)
    }

    private fun cleanExports(app: Context) {
        File(app.cacheDir, "diagnostic-exports").listFiles()?.filter {
            it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        }?.forEach { it.delete() }
    }

    @RequiresApi(30)
    private fun collectExits(app: Context) {
        val prefs = app.getSharedPreferences("diagnostic-exits", Context.MODE_PRIVATE)
        val last = prefs.getLong("last", 0L)
        val exits = app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 8)
        exits.filter { it.timestamp > last && it.reason in setOf(ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR) }.forEach { exit ->
            val trace = runCatching { exit.traceInputStream?.bufferedReader()?.use { val chars = CharArray(32_768); val n = it.read(chars); if (n > 0) String(chars, 0, n) else "" } }.getOrNull()
            append(Category.CRASH, "process-exit", mapOf("reason" to exit.reason, "timestamp" to exit.timestamp,
                "description" to exit.description, "trace" to trace), null, true)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last", it).apply() }
    }
}
