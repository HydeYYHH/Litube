package com.hhst.youtubelite.extractor

import android.app.ActivityManager
import java.net.URI
import java.io.Reader
import java.security.MessageDigest
import android.content.Context
import android.os.Build
import androidx.javascriptengine.JavaScriptSandbox
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hhst.youtubelite.downloader.webview.WebViewTimerOccupancy
import com.hhst.youtubelite.downloader.webview.WebViewTimerOwner
import org.schabi.newpipe.extractor.services.youtube.streams.ChallengeSolver
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** EJS 0.8.0 with compiled solver retention; cache keys include full variant URL and source hash. */
class AndroidChallengeSolver(
    context: Context,
    private val timers: WebViewTimerOccupancy,
    private val heavy: HeavyJsGate,
) : ChallengeSolver {
    private val app = context.applicationContext
    private val gson = Gson()
    private val lock = HeavyJsGate()
    private val directory = File(app.cacheDir, "youtube-player-v1").apply { mkdirs() }
    private val lowRam = app.getSystemService(ActivityManager::class.java).isLowRamDevice
    private var runtime: JavascriptRuntime? = null
    private var active = ""
    private var lastUsed = 0L
    private val values = LinkedHashMap<String, String>(256, .75f, true)
    private data class PlayerSource(val file: File, val length: Long, var modified: Long,
                                    var hash: String? = null, var timestamp: Int? = null)
    private val sources = LinkedHashMap<String, PlayerSource>(4, .75f, true)
    private val reaper = Executors.newSingleThreadScheduledExecutor { Thread(it, "youtube-js-idle").apply { isDaemon = true } }

    init {
        reaper.scheduleWithFixedDelay({ lock.tryRunIdle {
            // A compiled sandbox solver is cheap to keep and spares the next video a cold compile;
            // the WebView fallback peaks near 400 MiB, so it keeps the short idle lifetime.
            val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && runtime is SandboxRuntime) SANDBOX_IDLE_MS else FALLBACK_IDLE_MS
            if (runtime != null && System.currentTimeMillis() - lastUsed >= idle) release()
        } }, 60, 60, TimeUnit.SECONDS)
    }

    override fun signatureTimestamp(playerUrl: String, context: ExtractionContext): Int = lock.run(context) {
        val source = source(playerUrl, context)
        source.timestamp ?: source.file.bufferedReader().use { readSignatureTimestamp(it, context::check) }
            .also { source.timestamp = it }
    }

    override fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext): ChallengeSolver.Solutions = lock.run(context) {
        context.check()
        val source = source(playerUrl, context)
        val key = key(playerUrl, source, context)
        val missingS = signatures.filter { !values.containsKey("$key:s:$it") }
        val missingN = throttles.filter { !values.containsKey("$key:n:$it") }
        val cold = runtime == null || active != key
        val start = System.nanoTime()
        val execution = context.withTimeLimit(if (cold) 10_000 else 1_000)
        try {
            if (missingS.isNotEmpty() || missingN.isNotEmpty()) {
                if (cold) heavy.run(execution) { compile(key, source, execution, allowFallback = true) }
                val json = runtime!!.evaluate("JSON.stringify({s:Object.fromEntries(${gson.toJson(missingS)}.map(x=>[x,_solver.sig(x)])),n:Object.fromEntries(${gson.toJson(missingN)}.map(x=>[x,_solver.n(x)]))})", if (cold) 10_000 else 1_000, execution)
                val result = JsonParser.parseString(json).asJsonObject
                for ((kind, entries) in listOf("s" to missingS, "n" to missingN)) {
                    for (input in entries) {
                        val output = result.getAsJsonObject(kind).get(input)?.asString ?: throw IOException("JS_EMPTY_RESULT")
                        if (output.isEmpty() || output.startsWith("enhanced_except_") || output.startsWith("_w8_")) throw IOException("JS_INVALID_RESULT")
                        values["$key:$kind:$input"] = output
                    }
                }
                while (values.size > 1024) values.remove(values.keys.first())
            }
            context.check()
            lastUsed = System.currentTimeMillis()
            context.diagnostics.event("js", "EJS", "${runtime?.version ?: "result-cache"}:${if (cold) "cold" else "cached"}", (System.nanoTime() - start) / 1_000_000, 0)
            ChallengeSolver.Solutions(signatures.associateWith { values.getValue("$key:s:$it") }, throttles.associateWith { values.getValue("$key:n:$it") })
        } catch (failure: Throwable) {
            release()
            throw if (failure is IOException) failure else IOException("EJS_FAILURE", failure)
        } finally { if (lowRam) release() }
    }

    /**
     * Compiles [playerUrl] ahead of the first solve. Sandbox only: the WebView fallback is too heavy
     * to start speculatively, so an unsupported sandbox leaves the first solve to do it on demand.
     */
    fun initialize(playerUrl: String, context: ExtractionContext): Boolean = lock.run(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || lowRam || runtime is RemoteEjsRuntime || !JavaScriptSandbox.isSupported()) return@run false
        val start = System.nanoTime()
        try {
            val source = source(playerUrl, context)
            val key = key(playerUrl, source, context)
            if (runtime == null || active != key) {
                val execution = context.withTimeLimit(10_000)
                heavy.run(execution) { compile(key, source, execution, allowFallback = false) }
                if (runtime == null) return@run false
            }
            lastUsed = System.currentTimeMillis()
            context.diagnostics.event("js", "EJS", "${runtime?.version}:init", (System.nanoTime() - start) / 1_000_000, 0)
            true
        } catch (failure: Throwable) {
            release()
            context.diagnostics.event("js", "EJS", "init-failed:${failure.message ?: failure.javaClass.simpleName}", (System.nanoTime() - start) / 1_000_000, 0)
            false
        }
    }

    private fun key(playerUrl: String, source: PlayerSource, context: ExtractionContext): String {
        val hash = source.hash ?: MessageDigest.getInstance("SHA-256").apply {
            source.file.inputStream().use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    context.check()
                    val count = input.read(chunk)
                    if (count < 0) break
                    update(chunk, 0, count)
                }
            }
        }.digest().hexString().also { source.hash = it }
        return YoutubeSessionProvider.digest("$playerUrl:$hash:ejs-0.8.0-export1")
    }

    private fun compile(key: String, source: PlayerSource, execution: ExtractionContext, allowFallback: Boolean) {
        val previous = runtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && previous is SandboxRuntime && runCatching { previous.restartIsolate(execution) }.isSuccess) {
            active = ""
        } else {
            release()
            runtime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { SandboxRuntime.create(app, execution) }.getOrNull()
            } else null
            if (runtime == null) {
                if (!allowFallback) return
                runtime = if (EjsRuntimeProcess.canIsolate(app)) {
                    RemoteEjsRuntime(app, execution.session.userAgent)
                } else {
                    // Old providers cannot isolate their data directory across processes.
                    execution.diagnostics.event("js", "EJS", "webview-local-fallback", 0, 0)
                    HiddenJavascriptRuntime(app, timers, WebViewTimerOwner.EJS, execution.session.userAgent)
                }
            }
        }
        val libraries = app.assets.open("ejs/yt.solver.lib.min.js").bufferedReader().use { it.readText() } +
            "\nObject.assign(globalThis,lib);\n" + app.assets.open("ejs/yt.solver.core.js").bufferedReader().use { it.readText() }
        runtime!!.evaluate(libraries + "\n'loaded'", 10_000, execution)
        val prepared = File(directory, "$key.prepared")
        if (prepared.isFile) {
            prepared.setLastModified(System.currentTimeMillis())
            runtime!!.compilePlayer(prepared, true, 10_000, execution)
        } else {
            runtime!!.compilePlayer(source.file, false, 10_000, execution, prepared)
            trimDisk()
        }
        active = key
    }

    private fun source(url: String, context: ExtractionContext): PlayerSource {
        context.check()
        // Only player scripts from YouTube are eligible to enter the JavaScript runtime.
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "www.youtube.com" || !uri.path.startsWith("/s/player/")) throw IOException("PLAYER_ORIGIN")
        val file = File(directory, YoutubeSessionProvider.digest(url) + ".source")
        sources[url]?.takeIf { file.isFile && it.length == file.length() && it.modified == file.lastModified() }?.let {
            file.setLastModified(System.currentTimeMillis())
            it.modified = file.lastModified()
            return it
        }
        if (!file.isFile) {
            val response = context.get(url, mapOf("User-Agent" to listOf(context.session.userAgent)))
            if (response.responseCode() != 200 || response.responseBody().length > 8 * 1024 * 1024) throw IOException("PLAYER_SOURCE_HTTP")
            val temporary = File(directory, file.name + ".tmp")
            try {
                temporary.writeText(response.responseBody())
                context.check()
                if (!temporary.renameTo(file)) throw IOException("PLAYER_SOURCE_STORAGE")
            } finally { temporary.delete() }
        }
        file.setLastModified(System.currentTimeMillis())
        return PlayerSource(file, file.length(), file.lastModified()).also {
            sources[url] = it
            while (sources.size > 4) sources.remove(sources.keys.first())
            trimDisk()
        }
    }

    private fun trimDisk() {
        val files = directory.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        for (file in files) { if (bytes <= 16L * 1024 * 1024) break; val size = file.length(); if (file.delete()) bytes -= size }
    }

    private fun release() { runtime?.close(); runtime = null; active = "" }

    private companion object {
        const val SANDBOX_IDLE_MS = 10 * 60_000L
        const val FALLBACK_IDLE_MS = 2 * 60_000L
    }
}

/** Locale-free encoding on the session/challenge hot path. */
internal fun ByteArray.hexString(): String {
    val digits = "0123456789abcdef"
    return CharArray(size * 2).also { result ->
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 15]
        }
    }.concatToString()
}

/** A timestamp at a buffer boundary must include all digits before it is accepted. */
internal fun readSignatureTimestamp(reader: Reader, check: () -> Unit): Int {
    val chunk = CharArray(8 * 1024)
    val pattern = Regex("signatureTimestamp[:=](\\d+)(?=\\D)")
    var tail = ""
    while (true) {
        check()
        val count = reader.read(chunk)
        if (count < 0) break
        val text = tail + String(chunk, 0, count)
        pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        tail = text.takeLast(64)
    }
    return pattern.find(tail + ";")?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
