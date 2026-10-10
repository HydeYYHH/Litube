package com.hhst.youtubelite.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.webkit.WebViewCompat
import com.google.gson.Gson
import com.hhst.youtubelite.BuildConfig
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Ordinary events only enqueue sanitized records; disk IO belongs to the writer. */
object AppLog {
    enum class Category { APP, CRASH, NETWORK, BROWSER, EXTRACTOR, DOWNLOADER, PLAYER, CAST, EXTENSION, GALLERY, STORAGE }
    private val session = UUID.randomUUID().toString().take(8)
    @Volatile private var app: Context? = null
    @Volatile private var storage: DiagnosticFiles? = null
    @Volatile private var recorder: DiagnosticRecorder? = null
    private val queue = DiagnosticQueue()
    private val enqueueLock = Any()
    private val dropped = AtomicLong()
    private val writeFailures = AtomicLong()
    private val flushes = mutableListOf<CountDownLatch>()
    private val providers = ConcurrentHashMap<String, DiagnosticSnapshotProvider>()
    private val remoteFlushes = ConcurrentHashMap<String, (Long) -> Boolean>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { job -> Thread(job, "diagnostic-sampling").apply { isDaemon = true } }

    @Synchronized fun initialize(application: Application) {
        if (app != null) return
        app = application.applicationContext
        DiagnosticRedaction.initialize(File(application.filesDir, "diagnostics"))
        storage = DiagnosticFiles(File(application.filesDir, "diagnostics"))
        val process = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(application.packageName)
        recorder = DiagnosticRecorder(process, Process.myPid(), session, System::currentTimeMillis, SystemClock::elapsedRealtime, ::enqueue) {
            providers.mapValues { runCatching { it.value.snapshot() }.getOrDefault(mapOf("state" to "unknown")) }
        }
        Thread({ writeLoop() }, "diagnostics-writer").apply { isDaemon = true; start() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(previous) { thread, failure ->
                val deadline = SystemClock.elapsedRealtime() + 500
                event(Category.CRASH, "uncaught", mapOf("thread" to thread.name), failure, true, level = DiagnosticLevel.FATAL)
                storage?.append(recorder?.recent().orEmpty(), true) { SystemClock.elapsedRealtime() >= deadline }
        })
        scheduler.scheduleAtFixedRate({
            runCatching {
                recorder?.tick()
                DiagnosticNetwork.sample()
                if (SystemClock.elapsedRealtime() / 1000 % 30 == 0L) event(Category.APP, "logger_health", statistics())
            }
        }, 1, 1, TimeUnit.SECONDS)
        scheduler.execute {
            runCatching { storage?.prune(); cleanExports(application) }
            if (Build.VERSION.SDK_INT >= 30 && process == application.packageName) runCatching { collectExits(application) }
        }
        scheduler.scheduleAtFixedRate({ runCatching { storage?.prune() } }, 1, 1, TimeUnit.HOURS)
        event(Category.APP, "process_start", mapOf("version" to BuildConfig.VERSION_NAME, "build_id" to BuildConfig.DIAGNOSTIC_BUILD_ID))
        if (process == application.packageName) runCatching { AndroidDiagnosticState.install(application) }
    }
    @JvmStatic @JvmOverloads
    fun event(category: Category, event: String, fields: Map<String, Any?> = emptyMap(),
        failure: Throwable? = null, critical: Boolean = failure != null, context: DiagnosticContext? = null,
        level: DiagnosticLevel = if (critical) DiagnosticLevel.ERROR else DiagnosticLevel.INFO) {
        runCatching { recorder?.record(category.name, event, level, context, fields, failure, incident = critical) }
    }
    @JvmStatic @JvmOverloads
    fun detail(category: Category, event: String, fields: Map<String, Any?> = emptyMap(), context: DiagnosticContext? = null) {
        runCatching { recorder?.record(category.name, event, DiagnosticLevel.DEBUG, context, fields, detail = true) }
    }
    fun snapshot(category: Category, context: DiagnosticContext?, fields: Map<String, Any?>) {
        runCatching { recorder?.record(category.name, "state_snapshot", DiagnosticLevel.DEBUG, context, fields, detail = true, kind = "snapshot") }
    }
    fun operation(category: Category, name: String, context: DiagnosticContext = DiagnosticContext(), fields: Map<String, Any?> = emptyMap()): DiagnosticOperation {
        val started = SystemClock.elapsedRealtime()
        event(category, "$name.start", fields, context = context)
        return DiagnosticOperation(context) { outcome, reason, failure, result ->
            event(category, "$name.end", result + mapOf("outcome" to outcome.name, "reason" to reason,
                "duration_ms" to (SystemClock.elapsedRealtime() - started)), failure, outcome == DiagnosticOutcome.FAILURE, context)
            endContext(context)
        }
    }
    fun endContext(context: DiagnosticContext) { runCatching { recorder?.finish(context) } }
    fun registerSnapshot(name: String, provider: DiagnosticSnapshotProvider) { providers[name] = provider }
    fun registerRemoteFlush(name: String, flush: (Long) -> Boolean) { remoteFlushes[name] = flush }
    fun unregisterRemoteFlush(name: String) { remoteFlushes.remove(name) }
    fun playbackThreads(context: DiagnosticContext) {
        runCatching {
            val frames = Thread.getAllStackTraces().entries.filter { (thread, _) ->
                thread.name.contains("ExoPlayer") || thread.name.contains("Loader:") || thread.name.startsWith("youtube-")
            }.take(8).associate { (thread, stack) -> thread.name to stack.take(20).map { it.toString() } }
            event(Category.PLAYER, "stall_threads", mapOf("threads" to frames), critical = true, context = context)
        }
    }
    fun statistics(): Map<String, Any> = recorder?.statistics().orEmpty() +
        mapOf("dropped" to dropped.get() + queue.dropped, "write_failures" to writeFailures.get(), "queued_bytes" to queue.bytes,
            "snapshot_truncated_bytes" to (storage?.snapshotTruncatedBytes ?: 0))
    private fun enqueue(records: List<DiagnosticEvent>, priority: Boolean) {
        synchronized(enqueueLock) { records.chunked(64).forEach { queue.offer(it, priority) } }
    }
    private fun writeLoop() {
        while (true) {
            val batches: List<DiagnosticQueue.Batch>; val barriers: List<CountDownLatch>
            synchronized(enqueueLock) {
                batches = queue.drain()
                barriers = flushes.toList(); flushes.clear()
            }
            batches.forEach { batch ->
                try { dropped.addAndGet((storage?.append(batch.records, batch.priority) ?: batch.records.size).toLong()) }
                catch (_: Throwable) { writeFailures.incrementAndGet(); dropped.addAndGet(batch.records.size.toLong()) }
            }
            barriers.forEach(CountDownLatch::countDown)
            if (batches.isEmpty()) try { Thread.sleep(25) } catch (_: InterruptedException) { return }
        }
    }
    fun flush(timeoutMs: Long = 2_000): Boolean {
        recorder?.flushAggregates()
        event(Category.APP, "logger_health", statistics())
        val latch = CountDownLatch(1)
        synchronized(enqueueLock) { flushes += latch }
        return runCatching { latch.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }
    /** Called on IO; unresponsive auxiliary processes yield an explicitly partial archive. */
    fun export(context: Context): File {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        event(Category.APP, "diagnostic_export.start")
        val coverage = linkedMapOf("main" to flush(minOf(2_000, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))))
        remoteFlushes.forEach { (name, remote) ->
            coverage[name] = runCatching { remote(minOf(2_000, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))) }.getOrDefault(false)
        }
        val sources = storage?.snapshot { SystemClock.elapsedRealtime() >= deadline } ?: (emptyMap<String, String>() to 0)
        val environment = mapOf("app_version" to context.packageManager.getPackageInfo(context.packageName, 0).versionName,
            "build_id" to BuildConfig.DIAGNOSTIC_BUILD_ID, "build_type" to BuildConfig.BUILD_TYPE,
            "android_sdk" to Build.VERSION.SDK_INT, "android_release" to Build.VERSION.RELEASE,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "webview" to (WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unknown"),
            "dependencies" to BuildConfig.DIAGNOSTIC_DEPENDENCIES, "available_storage_bytes" to context.filesDir.usableSpace,
            "snapshots" to recorder?.currentSnapshots().orEmpty().map { mapOf("category" to it.category, "context" to it.context, "fields" to it.fields) },
            "providers" to providers.mapValues { runCatching { DiagnosticRedaction.fields(it.value.snapshot()) }.getOrDefault(mapOf("state" to "unknown")) })
        cleanExports(context)
        val directory = File(context.cacheDir, "diagnostic-exports").apply { mkdirs() }
        val archive = File(directory, "litube-diagnostics-" + System.currentTimeMillis() + ".zip")
        DiagnosticArchive.write(archive, sources.first, environment, statistics() + mapOf("process_flush" to coverage, "skipped_files" to sources.second))
        event(Category.APP, "diagnostic_export.end", mapOf("outcome" to "SUCCESS", "partial" to (coverage.values.any { !it } || sources.second > 0), "files" to sources.first.size))
        return archive
    }
    private fun cleanExports(context: Context) {
        File(context.cacheDir, "diagnostic-exports").listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1) }?.forEach { it.delete() }
    }
    @RequiresApi(30) private fun collectExits(context: Context) {
        val prefs = context.getSharedPreferences("diagnostic-exits", Context.MODE_PRIVATE)
        val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
        exits.filter { it.timestamp > prefs.getLong("last", 0L) }.forEach { exit ->
            val abnormal = exit.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
            val frames = runCatching { exit.traceInputStream?.bufferedReader()?.use { reader ->
                reader.lineSequence().take(1000).filter { it.trimStart().startsWith("at ") || it.trimStart().startsWith("#") }.take(128).toList()
            } }.getOrNull()
            event(Category.CRASH, "process_exit", mapOf("reason" to exit.reason, "timestamp" to exit.timestamp,
                "exit_pid" to exit.pid, "process_name" to exit.processName, "frames" to frames), critical = abnormal)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last", it).apply() }
    }
}

/** A separate reserved channel keeps failures ahead of ordinary traffic. */
internal class DiagnosticQueue(private val normalLimit: Int = 256, private val urgentLimit: Int = 64,
    private val normalBytes: Int = 2 * 1024 * 1024, private val totalBytes: Int = 4 * 1024 * 1024) {
    data class Batch(val records: List<DiagnosticEvent>, val priority: Boolean, val bytes: Int)
    private val normal = ArrayDeque<Batch>()
    private val urgent = ArrayDeque<Batch>()
    private val gson = Gson()
    @Volatile var bytes = 0; private set
    @Volatile var dropped = 0L; private set
    @Synchronized fun offer(records: List<DiagnosticEvent>, priority: Boolean) {
        val size = records.sumOf { gson.toJson(it).toByteArray(Charsets.UTF_8).size + 1 }
        val limit = if (priority) totalBytes else normalBytes
        if (priority) while (bytes + size > limit && normal.isNotEmpty()) {
            val batch = normal.removeFirst(); bytes -= batch.bytes; dropped += batch.records.size
        }
        val channel = if (priority) urgent else normal
        if (bytes + size > limit || channel.size >= if (priority) urgentLimit else normalLimit) dropped += records.size
        else { channel.addLast(Batch(records, priority, size)); bytes += size }
    }
    @Synchronized fun drain(): List<Batch> {
        val result = urgent.toList() + normal.toList()
        urgent.clear(); normal.clear(); bytes = 0
        return result
    }
}

internal class DiagnosticCrashHandler(private val previous: Thread.UncaughtExceptionHandler?,
    private val capture: (Thread, Throwable) -> Unit) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try { runCatching { capture(thread, failure) } }
        finally { previous?.uncaughtException(thread, failure) }
    }
}
