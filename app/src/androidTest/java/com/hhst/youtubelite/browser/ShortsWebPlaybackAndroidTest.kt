package com.hhst.youtubelite.browser

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class ShortsWebPlaybackAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Test fun coldShortsUsesWebMediaAndReportsSoundOutcome() {
        assumeTrue("Supply -e network=1 for live-web shorts acceptance",
            InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String>("{}")
        var host: BrowserHost? = null
        var nativeCalls = 0
        val fallback = AtomicReference<String?>(null)
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val extractor = GlobalContext.get().get<Extractor>()
        val callbacks = object : WebViewCallbacks {
            override fun onPageStarted(url: String) {}
            override fun onPageFinished(url: String) {}
            override fun onProgressChanged(progress: Int) {}
            override fun onNavigationStateChanged(canGoBack: Boolean) {}
            override fun onHistoryChanged(url: String) {}
            override fun onOpenTab(url: String) { host?.webView?.loadUrl(url) }
            override fun onShortsAutoplayBlocked(url: String, reason: String) { fallback.set(reason) }
        }
        try {
            activity.scenario.onActivity { context ->
                host = WebViewFactory.create(context, callbacks, prefs, {}, {}, {}, {}, {}, extractor,
                    object : PlayerHooks {
                        override fun playVideo(url: String, origin: PageOrigin) { nativeCalls++ }
                        override fun hidePlayer(origin: PageOrigin) {}
                        override fun seekLoadedVideo(url: String, positionMs: Long) = false
                        override fun setPlayerLayout(topDp: Int, heightDp: Int, origin: PageOrigin) {}
                    })
                context.setContentView(host!!.container)
                host!!.webView.loadUrl("https://m.youtube.com/shorts")
            }
            val deadline = System.currentTimeMillis() + 60_000
            var observed = JSONObject()
            while (System.currentTimeMillis() < deadline) {
                val latch = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    host!!.webView.evaluateJavascript("JSON.stringify((()=>{const v=Array.from(document.querySelectorAll('video')).find(x=>x.getBoundingClientRect().height>40);return {url:location.href,ready:v?.readyState??0,muted:v?.muted??true,paused:v?.paused??true,time:v?.currentTime??0,audioBytes:v?.webkitAudioDecodedByteCount??0,title:document.title}})())") {
                        result.set(it ?: "{}"); latch.countDown()
                    }
                }
                latch.await(3, TimeUnit.SECONDS)
                observed = runCatching { JSONObject(JSONTokener(result.get()).nextValue().toString()) }.getOrDefault(JSONObject())
                if (observed.optInt("ready") >= 2 && observed.optDouble("time") > 3) break
                Thread.sleep(500)
            }
            val evidence = JSONObject().put("provider", WebView.getCurrentWebViewPackage()?.versionName)
                .put("media", observed).put("fallbackReason", fallback.get()).put("nativePlayCalls", nativeCalls)
            File(instrumentation.targetContext.filesDir, "upgrade-shorts-webview.json").writeText(evidence.toString(2))
            assertEquals("Shorts must not request native playback", 0, nativeCalls)
            assertTrue("No playable Shorts media loaded: $evidence", observed.optInt("ready") >= 2)
            assertTrue("No sound and no fallback reported: $evidence", (!observed.optBoolean("muted") && !observed.optBoolean("paused")) || fallback.get() != null)
        } finally {
            instrumentation.runOnMainSync { host?.webView?.let { it.loadUrl("about:blank"); it.stopLoading(); it.destroy() } }
        }
    }
}
