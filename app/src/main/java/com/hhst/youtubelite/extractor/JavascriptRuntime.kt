package com.hhst.youtubelite.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.gson.Gson
import com.hhst.youtubelite.core.AndroidWebViewMainGate
import com.hhst.youtubelite.core.WebViewTimerOccupancy
import com.hhst.youtubelite.core.WebViewTimerOwner
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.Future
import java.io.Reader
import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.MemoryLimitExceededException
import androidx.webkit.WebViewCompat
import java.io.File
import java.io.IOException
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** One deadline-aware permit; queued playback precedes speculative/download work. */
class HeavyJsGate {
    private val lock = ReentrantLock(true)
    private val available = lock.newCondition()
    private var busy = false
    private var waiting = 0
    private var playbackWaiting = 0
    fun <T> run(context: ExtractionContext, block: () -> T): T {
        lock.lock()
        val playback = context.playbackPriority
        waiting++
        if (playback) playbackWaiting++
        try {
            while (busy || !playback && playbackWaiting > 0) {
                context.check()
                available.await(minOf(50L, context.remainingMillis()), TimeUnit.MILLISECONDS)
            }
            context.check()
            busy = true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("SESSION_CHANGED_OR_CANCELLED", interrupted)
        } finally { waiting--; if (playback) playbackWaiting--; available.signalAll(); lock.unlock() }
        try { return block() } finally { releasePermit() }
    }

    /** Maintenance must never delay an extraction or retain a stale runtime reference. */
    fun tryRunIdle(block: () -> Unit): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (busy || waiting > 0) return false
            busy = true
        } finally { lock.unlock() }
        try { block() } finally { releasePermit() }
        return true
    }

    private fun releasePermit() {
        lock.lock()
        try { busy = false; available.signalAll() } finally { lock.unlock() }
    }
}

internal interface JavascriptRuntime : AutoCloseable {
    val version: String
    fun evaluate(code: String, timeoutMs: Long, context: ExtractionContext): String

    /** Avoid duplicating a multi-megabyte player as nested JSON on the Java heap/IPC. */
    fun compilePlayer(file: File, prepared: Boolean, timeoutMs: Long, context: ExtractionContext, output: File? = null): String {
        try {
            loadPlayerInput(file, timeoutMs, context)
            val result = evaluate(compileInput(prepared), timeoutMs, context)
            if (!prepared) savePrepared(requireNotNull(output), result.toInt(), timeoutMs, context)
            return "compiled"
        } catch (failure: Throwable) { close(); throw failure }
    }

    fun loadPlayerInput(file: File, timeoutMs: Long, context: ExtractionContext) {
        val json = Gson()
        evaluate("globalThis._sourceParts=[];'ready'", timeoutMs, context)
        file.bufferedReader().use { reader ->
            sourceChunks(reader, context) { chunk ->
                evaluate("_sourceParts.push(${json.toJson(chunk)});'chunk'", timeoutMs, context)
            }
        }
        evaluate("globalThis._input=_sourceParts.join('');_sourceParts=null;'ready'", timeoutMs, context)
    }

    /** Prepared code is also large: stream it to disk without a giant callback/Binder result. */
    fun savePrepared(output: File, length: Int, timeoutMs: Long, context: ExtractionContext) {
        val temporary = File(output.parentFile, output.name + ".tmp")
        try {
            temporary.bufferedWriter().use { writer ->
                var offset = 0
                while (offset < length) {
                    context.check()
                    val end = minOf(offset + 32 * 1024, length)
                    val chunk = evaluate("(function(){var end=$end,c=_prepared.charCodeAt(end-1);" +
                        "if(c>=0xD800&&c<=0xDBFF&&end<_prepared.length)end++;return _prepared.slice($offset,end);})()", timeoutMs, context)
                    if (chunk.isEmpty()) throw IOException("JS_PREPARE_STORAGE")
                    writer.write(chunk)
                    offset += chunk.length
                }
            }
            if (!temporary.renameTo(output)) throw IOException("JS_PREPARE_STORAGE")
            evaluate("globalThis._prepared=null;'stored'", timeoutMs, context)
        } finally { temporary.delete() }
    }
}

internal fun sourceChunks(reader: Reader, context: ExtractionContext, consume: (String) -> Unit) {
    val buffer = CharArray(32 * 1024 + 1)
    while (true) {
        context.check()
        var count = reader.read(buffer, 0, buffer.size - 1)
        if (count < 0) break
        if (count == 0) continue
        if (buffer[count - 1].isHighSurrogate()) {
            val next = reader.read()
            if (next >= 0) buffer[count++] = next.toChar()
        }
        consume(String(buffer, 0, count))
    }
}

private fun compileInput(prepared: Boolean): String = if (prepared) {
    "globalThis._solver=jsc.compile(_input);_input=null;'compiled'"
} else {
    "globalThis._prepared=jsc.prepare(_input);_input=null;" +
        "globalThis._solver=jsc.compile(_prepared);String(_prepared.length)"
}

@RequiresApi(Build.VERSION_CODES.O)
internal class SandboxRuntime private constructor(
    private val app: Context,
    private val sandbox: JavaScriptSandbox,
    private var isolate: JavaScriptIsolate,
) : JavascriptRuntime {
    override val version = "sandbox-1.1.1"

    /** Fresh solver heap, keeping the verified service connection until idle release. */
    fun restartIsolate(context: ExtractionContext) {
        context.check()
        isolate.close()
        isolate = sandbox.createIsolate(parameters())
    }

    override fun evaluate(code: String, timeoutMs: Long, context: ExtractionContext): String {
        val file = File.createTempFile("ejs-input-", ".js", app.cacheDir)
        try {
            file.writeText(code)
            return evaluateFile(file, timeoutMs, context)
        } finally { file.delete() }
    }

    override fun compilePlayer(file: File, prepared: Boolean, timeoutMs: Long, context: ExtractionContext, output: File?): String {
        val script = File.createTempFile("ejs-player-", ".js", app.cacheDir)
        val json = Gson()
        try {
            script.bufferedWriter().use { writer ->
                writer.write("globalThis._input=[")
                file.bufferedReader().use { reader ->
                    var first = true
                    sourceChunks(reader, context) { chunk ->
                        if (!first) writer.write(",")
                        first = false
                        json.toJson(chunk, writer)
                    }
                }
                writer.write("].join('');" + compileInput(prepared))
            }
            val result = evaluateFile(script, timeoutMs, context)
            if (!prepared) savePrepared(requireNotNull(output), result.toInt(), timeoutMs, context)
            return "compiled"
        } finally { script.delete() }
    }

    private fun evaluateFile(file: File, timeoutMs: Long, context: ExtractionContext): String =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            await(isolate.evaluateJavaScriptAsync(fd), timeoutMs, context) { close() }
        }

    override fun close() { isolate.close(); sandbox.close() }

    companion object {
        fun create(app: Context, context: ExtractionContext): SandboxRuntime? {
            if (!JavaScriptSandbox.isSupported()) return null
            val connecting = JavaScriptSandbox.createConnectedInstanceAsync(app)
            val sandbox = await(connecting, 5_000, context) { connecting.cancel(true) }
            val required = listOf(JavaScriptSandbox.JS_FEATURE_EVALUATE_FROM_FD,
                JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION)
            if (!required.all(sandbox::isFeatureSupported)) { sandbox.close(); return null }
            return try {
                SandboxRuntime(app, sandbox, sandbox.createIsolate(parameters()))
            } catch (failure: Throwable) { sandbox.close(); throw failure }
        }
        private fun parameters() = IsolateStartupParameters().apply {
            maxHeapSizeBytes = 192L * 1024 * 1024
            maxEvaluationReturnSizeBytes = 8 * 1024 * 1024
        }
    }
}

/** Independent hidden renderer, no external navigation, file access or renderer network. */
internal class HiddenJavascriptRuntime(
    private val app: Context,
    private val timers: WebViewTimerOccupancy,
    private val owner: WebViewTimerOwner,
    private val userAgent: String,
) : JavascriptRuntime {
    private val main = AndroidWebViewMainGate()
    private val pending = ConcurrentHashMap<Long, SettableFuture<String>>()
    private val sequence = AtomicLong()
    private var view: WebView? = null
    override val version = "webview:${WebViewCompat.getCurrentWebViewPackage(app)?.versionName}"

    @SuppressLint("SetJavaScriptEnabled")
    private fun initialize(context: ExtractionContext) {
        if (view != null) return
        val ready = SettableFuture.create<Unit>()
        main.run {
            view = WebView(app).apply {
                timers.attach(this)
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.userAgentString = userAgent
                addJavascriptInterface(Callbacks(), "JsResult")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String) { ready.set(Unit) }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        pending.values.forEach { it.setException(IOException("JS_RENDERER_GONE")) }
                        close()
                        return true
                    }
                }
                loadDataWithBaseURL(YoutubeSessionProvider.ORIGIN, "<!doctype html><title>JS</title>", "text/html", "UTF-8", null)
            }
        }
        await(ready, 5_000, context) { close() }
    }

    private inner class Callbacks {
        @JavascriptInterface fun result(id: Long, value: String) { pending.remove(id)?.set(value) }
        @JavascriptInterface fun error(id: Long, kind: String) {
            val safe = kind.takeIf { it in setOf("Error", "TypeError", "ReferenceError", "RangeError", "SyntaxError") } ?: "Error"
            pending.remove(id)?.setException(IOException("JS_EVALUATION_$safe"))
        }
    }

    override fun evaluate(code: String, timeoutMs: Long, context: ExtractionContext): String =
        timers.withOwner(owner) {
            initialize(context)
            val id = sequence.incrementAndGet()
            val future = SettableFuture.create<String>()
            pending[id] = future
            try {
                main.run {
                    view?.evaluateJavascript("Promise.resolve().then(function(){return (0,eval)(${Gson().toJson(code)});}).then(function(r){JsResult.result($id,String(r));},function(e){JsResult.error($id,e&&e.name||'Error');});", null)
                }
                await(future, timeoutMs, context) { close() }
            } finally { pending.remove(id) }
        }

    override fun close() {
        pending.values.forEach { it.setException(IOException("JS_RUNTIME_CLOSED")) }
        pending.clear()
        main.run { view?.removeJavascriptInterface("JsResult"); view?.destroy(); view = null }
    }
}

internal fun <T> await(future: Future<T>, timeoutMs: Long,
                       context: ExtractionContext, abort: () -> Unit): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMs, context.remainingMillis()))
    try {
        while (true) {
            context.check()
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw IOException("JS_TIMEOUT")
            try { return future.get(minOf(100, remaining), TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (failure: ExecutionException) {
                val cause = failure.cause
                if (cause is IOException && cause.message?.matches(Regex("JS_[A-Za-z_]{1,64}")) == true) throw cause
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sandboxFailure(failure.cause) else "JS_RUNTIME_FAILURE"
                throw IOException(code, failure.cause)
            }
        }
    } catch (failure: Throwable) { abort(); throw failure }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun sandboxFailure(failure: Throwable?): String = when (failure) {
    is MemoryLimitExceededException -> "JS_HEAP_LIMIT"
    is IsolateTerminatedException -> "JS_ISOLATE_TERMINATED"
    else -> "JS_RUNTIME_FAILURE"
}
