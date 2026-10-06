package com.hhst.youtubelite.extractor

import com.google.common.util.concurrent.SettableFuture
import android.app.Application
import android.app.Service
import android.content.*
import android.os.*
import android.webkit.WebView
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature
import com.grack.nanojson.JsonObject
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.downloader.webview.WebViewTimerOccupancy
import com.hhst.youtubelite.downloader.webview.WebViewTimerOwner
import java.io.File
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.streams.*

/** The fallback owns a separate browser process/data directory; terminating it cannot kill the account WebView. */
internal object EjsRuntimeProcess {
    var ready = false
    fun canIsolate(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 ||
        WebViewFeature.isStartupFeatureSupported(context, WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)
    fun initialize(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else File("/proc/self/cmdline").readText().trimEnd('\u0000')
        if (!name.endsWith(":youtube_ejs")) return false
        ready = runCatching {
            if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("youtube-ejs")
            else {
                if (!canIsolate(app))
                    throw IOException("JS_DATA_DIRECTORY_UNAVAILABLE")
                ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(app, "youtube-ejs"))
            }
        }.isSuccess
        return true
    }
}

private const val OPEN = 1
private const val EVALUATE = 2

internal class RemoteEjsRuntime(private val app: Context, private val userAgent: String) : JavascriptRuntime {
    private var remote: Messenger? = null
    private val pending = ConcurrentHashMap<Long, SettableFuture<String>>()
    private val ids = AtomicLong()
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var servicePid = 0
    @Volatile private var closed = false
    private var actualVersion = "webview-process"
    override val version: String get() = actualVersion
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = message.data
            val future = pending.remove(data.getLong("id")) ?: return
            if (message.arg1 > 0 && !closed) servicePid = message.arg1
            val error = data.getString("error")
            if (error != null) future.setException(IOException(error))
            else future.set(data.getString("result").orEmpty())
        }
    })
    private fun died() {
        pending.values.forEach { it.setException(IOException("JS_SERVICE_DIED")) }
    }
    private fun transport(context: ExtractionContext): Messenger {
        if (closed) throw IOException("JS_RUNTIME_CLOSED")
        remote?.let { return it }
        val startup = context.withTimeLimit(5_000)
        try {
            // Android can briefly retain the old ServiceRecord after a deliberate termination.
            // Retry only that startup race, with a fresh connection and the same deadline.
            repeat(3) { attempt ->
                val connected = SettableFuture.create<Messenger>()
                val candidate = object : ServiceConnection {
                    private fun dead() {
                        connected.setException(IOException("JS_SERVICE_DIED"))
                        if (connection === this) died()
                    }
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (closed || connection !== this) return
                        try { binder.linkToDeath({ dead() }, 0); connected.set(Messenger(binder)) }
                        catch (_: RemoteException) { dead() }
                    }
                    override fun onServiceDisconnected(name: ComponentName) = dead()
                    override fun onBindingDied(name: ComponentName) = dead()
                    override fun onNullBinding(name: ComponentName) = dead()
                }
                connection = candidate
                if (!app.bindService(Intent(app, EjsRuntimeService::class.java), candidate,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) throw IOException("JS_SERVICE_BIND")
                try {
                    val service = await(connected, 5_000, startup) { }
                    actualVersion = request(service, OPEN, Bundle().apply { putString("ua", userAgent) }, 5_000, startup, false)
                    remote = service
                    return service
                } catch (failure: IOException) {
                    if (attempt == 2 || generateSequence<Throwable>(failure) { it.cause }
                            .none { it.message == "JS_SERVICE_DIED" }) throw failure
                    connection = null
                    runCatching { app.unbindService(candidate) }
                    startup.check()
                    Thread.sleep(50)
                }
            }
            throw IOException("JS_SERVICE_BIND")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    private fun request(remote: Messenger, kind: Int, data: Bundle, timeoutMs: Long, context: ExtractionContext,
                        closeOnFailure: Boolean = true): String {
        val id = ids.incrementAndGet()
        val future = SettableFuture.create<String>()
        pending[id] = future
        data.putLong("id", id)
        data.putLong("timeout", minOf(timeoutMs, context.remainingMillis()))
        try {
            remote.send(Message.obtain().apply { what = kind; replyTo = replies; this.data = data })
            return await(future, timeoutMs, context) { if (closeOnFailure) close() }
        } finally { pending.remove(id) }
    }
    override fun evaluate(code: String, timeoutMs: Long, context: ExtractionContext): String {
        val remote = transport(context)
        val file = File.createTempFile("ejs-ipc-", ".js", app.cacheDir)
        try {
            file.writeText(code)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                request(remote, EVALUATE, Bundle().apply { putParcelable("input", fd) }, timeoutMs, context)
            }
        } finally { file.delete() }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        stopHost()
    }
    private fun stopHost() {
        pending.values.forEach { it.setException(IOException("JS_RUNTIME_CLOSED")) }
        pending.clear()
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        remote = null
        // Remove AUTO_CREATE before termination; otherwise a concurrent new binding can
        // attach to the dying service record or trigger an unwanted automatic restart.
        val pid = servicePid
        servicePid = 0
        if (pid > 0 && pid != Process.myPid()) {
            Process.killProcess(pid)
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (File("/proc/$pid").exists() && System.nanoTime() < deadline) Thread.sleep(10)
            }
        }
    }
}

/** No credentials/HTTP/session cache are initialized in this process. The host sends public EJS code via FD. */
class EjsRuntimeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var live = true
    private var runtime: HiddenJavascriptRuntime? = null
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = message.data
            val reply = message.replyTo
            val kind = message.what
            worker.execute {
                val started = System.nanoTime()
                val response = Bundle().apply { putLong("id", data.getLong("id")) }
                try {
                    if (!EjsRuntimeProcess.ready) throw IOException("JS_DATA_DIRECTORY_UNAVAILABLE")
                    val ua = data.getString("ua").orEmpty()
                    if (kind == OPEN) {
                        runtime = HiddenJavascriptRuntime(this@EjsRuntimeService, WebViewTimerOccupancy.NOOP,
                            WebViewTimerOwner.EJS, ua)
                        response.putString("result", "webview-process:" + runtime!!.version.substringAfter(':'))
                    } else {
                        @Suppress("DEPRECATION") val fd = requireNotNull(data.getParcelable<ParcelFileDescriptor>("input"))
                        val code = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
                            val text = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                if (text.length + count > 10 * 1024 * 1024) throw IOException("JS_INPUT_LIMIT")
                                text.append(buffer, 0, count)
                            }
                            text.toString()
                        }
                        val timeout = data.getLong("timeout").coerceAtLeast(1)
                        val context = ExtractionContext(YoutubeSession("ejs-only", YoutubeSession.Account.ANONYMOUS,
                            0, null, null, null, null, "", 0, null, "isolated-ejs", JsonObject(), { "" }),
                            object : Downloader() { override fun execute(request: Request): Response = throw IOException("JS_NO_NETWORK") },
                            ChallengeSolver { _, _, _, _ -> ChallengeSolver.Solutions(emptyMap(), emptyMap()) }, null,
                            false, false, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout), { live },
                            { _, _, _, _, _ -> })
                        val result = requireNotNull(runtime).evaluate(code, timeout, context)
                        if (result.length > 256 * 1024) throw IOException("JS_RESULT_LIMIT")
                        response.putString("result", result)
                    }
                } catch (failure: Throwable) {
                    val safe = setOf("JS_TIMEOUT", "JS_DATA_DIRECTORY_UNAVAILABLE", "JS_INPUT_LIMIT", "JS_RESULT_LIMIT",
                        "JS_EVALUATION_Error", "JS_EVALUATION_TypeError", "JS_EVALUATION_SyntaxError", "JS_EVALUATION_RangeError",
                        "JS_EVALUATION_ReferenceError", "JS_RENDERER_GONE", "JS_RUNTIME_CLOSED")
                    response.putString("error", failure.message?.takeIf(safe::contains) ?: "JS_SERVICE_FAILURE")
                }
                AppLog.event(AppLog.Category.EXTRACTOR, "ejs_process_result",
                    mapOf("operation" to data.getLong("id"), "kind" to kind, "duration_ms" to (System.nanoTime() - started) / 1_000_000,
                        "error_code" to response.getString("error")), critical = response.containsKey("error"))
                runCatching { reply.send(Message.obtain().apply { arg1 = Process.myPid(); this.data = response }) }
            }
        }
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() {
        live = false
        worker.shutdownNow()
        runtime?.close()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
