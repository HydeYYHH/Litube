package com.hhst.youtubelite.player.surface

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual injected CSS and JS bridge in Android WebView. */
class LandscapeWatchLayoutAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    class LayoutBridge {
        val compact = AtomicBoolean(true)
        val reportedHeight = AtomicInteger()
        val reportedWidth = AtomicInteger()
        val reportedLeft = AtomicInteger()
        val plays = AtomicInteger()
        @JavascriptInterface fun isPlayerCompact() = compact.get()
        @JavascriptInterface fun play(url: String) { plays.incrementAndGet() }
        @JavascriptInterface fun setPlayerLayout(top: Int, height: Int) { reportedHeight.set(height) }
        @JavascriptInterface fun setPlayerBounds(left: Int, top: Int, width: Int, height: Int, viewportWidth: Int) {
            reportedHeight.set(height); reportedWidth.set(width); reportedLeft.set(left)
        }
        @JavascriptInterface fun setPageHasPlaylist(has: Boolean) = Unit
    }

    @Test
    fun collapsedWatchSlotReleasesContentAndRestoresWithoutRestartingPlayback() {
        val bridge = LayoutBridge()
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        compose.runOnUiThread {
            webView = WebView(compose.activity).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(bridge, "Bridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
            }
        }
        compose.setContent { AndroidView({ webView }, modifier = Modifier.fillMaxSize()) }
        compose.runOnUiThread {
            webView.loadDataWithBaseURL("https://m.youtube.com/watch?v=aaaaaaaaaaa", """
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body { margin:0 } .masthead { height:48px }
                .player-container { height:300px } #movie_player { height:100% }
                .watch-below-the-player { margin-top:0; padding-top:0 }</style></head>
                <body><div class="masthead"></div>
                <div id="player-container-id" class="player-container"><div id="movie_player"></div></div>
                <div class="watch-below-the-player"><button id="action" onclick="window.tapped=true">Action</button></div>
                </body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        assertTrue("Watch fixture did not load", loaded.await(10, TimeUnit.SECONDS))
        fun evaluate(script: String): String {
            val done = CountDownLatch(1)
            var result = ""
            compose.runOnUiThread { webView.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue("JavaScript did not finish", done.await(10, TimeUnit.SECONDS))
            return result
        }
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val script = listOf("script/core.js", "script/player-hook.js").joinToString("\n") {
            assets.open(it).bufferedReader().use { reader -> reader.readText() }
        }
        try {
            evaluate(script)
            evaluate("window.__syncPlayerCompact();")
            assertEquals("0", evaluate("document.querySelector('.player-container').getBoundingClientRect().height"))
            assertEquals("48", evaluate("document.querySelector('#action').getBoundingClientRect().top"))
            assertEquals(300, bridge.reportedHeight.get())
            evaluate("document.querySelector('#action').click();")
            assertEquals("true", evaluate("window.tapped"))

            // A resize/re-render changes the original slot while it stays collapsed.
            evaluate("document.querySelector('.player-container').style.height='360px'; window.__syncPlayerCompact();")
            assertEquals(360, bridge.reportedHeight.get())
            assertEquals("0", evaluate("document.querySelector('.player-container').getBoundingClientRect().height"))

            bridge.compact.set(false)
            evaluate("window.__syncPlayerCompact();")
            assertEquals("360", evaluate("document.querySelector('.player-container').getBoundingClientRect().height"))
            assertEquals("408", evaluate("document.querySelector('#action').getBoundingClientRect().top"))
            assertEquals(1, bridge.plays.get())
            evaluate("document.querySelector('.player-container').style.cssText='height:180px;width:240px;margin-left:32px'; window.__syncPlayerCompact();")
            assertEquals(240, bridge.reportedWidth.get())
            assertEquals(32, bridge.reportedLeft.get())
            evaluate("document.querySelector('.player-container').style.cssText='height:180px;width:200px;margin-left:60px'; window.dispatchEvent(new Event('resize'));")
            assertEquals(200, bridge.reportedWidth.get())
            assertEquals(60, bridge.reportedLeft.get())
        } finally {
            compose.runOnUiThread { webView.destroy() }
        }
    }
}
