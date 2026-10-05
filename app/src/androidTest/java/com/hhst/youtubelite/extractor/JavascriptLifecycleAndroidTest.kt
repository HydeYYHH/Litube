package com.hhst.youtubelite.extractor

import android.app.ActivityManager
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.streams.*
import java.io.File
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Real non-terminating JS, host death and heap exhaustion; no network or credential export. */
class JavascriptLifecycleAndroidTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private fun context(current: AtomicBoolean = AtomicBoolean(true)): ExtractionContext = ExtractionContext(
        YoutubeSession("lifecycle", YoutubeSession.Account.ANONYMOUS, 0, null, null, null,
            null, "fixture", 0, null, "offline", JsonObject(), { "" }),
        object : Downloader() {
            override fun execute(request: Request): Response = throw IOException("LIFECYCLE_HAS_NO_NETWORK")
        }, ChallengeSolver { _, _, _, _ -> ChallengeSolver.Solutions(emptyMap(), emptyMap()) },
        null, false, false, System.nanoTime() + TimeUnit.SECONDS.toNanos(20), { current.get() },
        { _, _, _, _, _ -> })

    private fun hostPid(): Int = app.getSystemService(ActivityManager::class.java).runningAppProcesses
        .first { it.processName == app.packageName + ":youtube_ejs" }.pid

    private fun assertStopped(pid: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (File("/proc/$pid").exists() && System.nanoTime() < end) Thread.sleep(20)
        assertFalse("EJS process remained alive after termination", File("/proc/$pid").exists())
    }

    @Test fun remoteTimeoutCancellationAndCrashPreserveAccountWebView() {
        lateinit var browser: WebView
        var cookieSnapshot: String? = null
        instrumentation.runOnMainSync {
            cookieSnapshot = CookieManager.getInstance().getCookie(YoutubeSessionProvider.ORIGIN)
            browser = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                loadData("<title>lifecycle</title>", "text/html", "UTF-8")
            }
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            for (failureMode in listOf("timeout", "cancel", "crash")) {
                val runtime = RemoteEjsRuntime(app, "fixture")
                try {
                    assertEquals("ready", runtime.evaluate("'ready'", 5_000, context()))
                    val pid = hostPid()
                    val current = AtomicBoolean(true)
                    if (failureMode == "cancel") timer.schedule({ current.set(false) }, 250, TimeUnit.MILLISECONDS)
                    if (failureMode == "crash") timer.schedule({ Process.killProcess(pid) }, 250, TimeUnit.MILLISECONDS)
                    val started = System.nanoTime()
                    try {
                        runtime.evaluate("while(true){}", if (failureMode == "timeout") 300 else 5_000, context(current))
                        fail("Infinite JS returned successfully")
                    } catch (failure: IOException) {
                        assertTrue("Termination exceeded its bound", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
                    }
                    runtime.close()
                    assertStopped(pid)
                    // Only this isolated EJS process was stopped; the user's browser remains usable.
                    val heartbeat = CompletableFuture<String>()
                    instrumentation.runOnMainSync {
                        browser.evaluateJavascript("'alive'", heartbeat::complete)
                        assertTrue("Account cookies changed", cookieSnapshot ==
                            CookieManager.getInstance().getCookie(YoutubeSessionProvider.ORIGIN))
                    }
                    assertEquals("\"alive\"", heartbeat.get(3, TimeUnit.SECONDS))
                } finally { runtime.close() }
            }
            RemoteEjsRuntime(app, "fixture").use { reopened ->
                assertEquals("42", reopened.evaluate("String(6*7)", 5_000, context()))
            }
        } finally {
            timer.shutdownNow()
            instrumentation.runOnMainSync { browser.destroy() }
        }
    }

    @Test fun sandboxHeapLimitAndTimeoutAllowFreshRuntime() {
        val runtime = SandboxRuntime.create(app, context())
        assumeNotNull(runtime)
        runtime!! .use {
            assertEquals("ready", it.evaluate("'ready'", 1_000, context()))
            try {
                it.evaluate("globalThis.held=[];while(true)held.push(new Array(1000000).fill(42));", 10_000, context())
                fail("Heap budget did not terminate the isolate")
            } catch (failure: IOException) {
                assertEquals("JS_HEAP_LIMIT", failure.message)
            }
        }
        // A close/termination must not leave an isolate consuming CPU indefinitely.
        SandboxRuntime.create(app, context()).use { fresh ->
            assertNotNull(fresh)
            assertEquals("42", fresh!!.evaluate("String(6*7)", 5_000, context()))
            try {
                fresh.evaluate("while(true){}", 300, context())
                fail("Sandbox timeout did not stop the isolate")
            } catch (failure: IOException) {
                assertEquals("JS_TIMEOUT", failure.message)
            }
        }
    }
}
