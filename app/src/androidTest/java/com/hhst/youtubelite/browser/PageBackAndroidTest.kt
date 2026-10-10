package com.hhst.youtubelite.browser

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import org.junit.Assert.*
import org.junit.Test

/** The page header's JS Back must walk both the WebView and native tab stack. */
class PageBackAndroidTest {
    @Test fun injectedHeaderBackPopsFreshTabsAndWalksLocalHistoryFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var host: MainActivity
        lateinit var model: BrowserViewModel
        scenario.onActivity { host = it }
        fun find(root: View): WebView? {
            if (root is WebView && root.isShown) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i))?.let { return it }
            return null
        }
        fun await(condition: () -> Boolean) {
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) Thread.sleep(50)
            }
            var diagnostic = ""
            if (!ready) instrumentation.runOnMainSync {
                val page = find(host.window.decorView)
                diagnostic = "url=${page?.url}, history=${page?.copyBackForwardList()?.size}"
            }
            assertTrue("Browser condition timed out: $diagnostic", ready)
        }
        try {
            await { find(host.window.decorView) != null }
            instrumentation.runOnMainSync { model = ViewModelProvider(host)[BrowserViewModel::class.java] }
            val home = model.uiState.value.activeId
            for (operation in listOf("history.back()", "history.go(-1)")) {
                instrumentation.runOnMainSync { model.openTab("https://m.youtube.com/account") }
                await { find(host.window.decorView)?.url == "https://m.youtube.com/account" }
                val page = find(host.window.decorView)!!
                val probe = WebProbe { page }
                instrumentation.runOnMainSync {
                    page.stopLoading()
                    page.loadDataWithBaseURL("https://m.youtube.com/account", """
                        <html><head><meta name=viewport content="width=device-width,initial-scale=1"></head><body>
                        <button style="position:fixed;left:0;top:0;width:100px;height:48px"
                          onclick="history.pushState({},'', '/account?panel=general')">General</button>
                        <h1 style="margin-top:60px">YouTube settings</h1>
                        <button id=back onclick="$operation">Back</button>
                        <script>window.fixtureReady=true</script></body></html>
                    """.trimIndent(), "text/html", "UTF-8", "https://m.youtube.com/account")
                }
                probe.waitFor("return {ready:window.fixtureReady===true}")
                instrumentation.runOnMainSync {
                    page.clearHistory()
                    page.evaluateJavascript(context.assets.open("script/nav.js").bufferedReader().use { it.readText() }, null)
                }
                // Same-kind SPA navigation belongs to this WebView, not a new tab.
                // Modern Chromium skips history entries without user activation.
                val point = IntArray(2)
                instrumentation.runOnMainSync { page.getLocationOnScreen(point) }
                DeviceEvidence.shell("input tap ${point[0] + 40} ${point[1] + 40}")
                probe.waitFor("return {ready:location.search==='?panel=general'}")
                await { page.canGoBack() }
                probe.evaluate("document.getElementById('back').click();return {}")
                probe.waitFor("return {ready:location.search==='' }")
                assertNotEquals(home, model.uiState.value.activeId)
                instrumentation.runOnMainSync { page.clearHistory(); assertFalse(page.canGoBack()) }
                DeviceEvidence.captureScene("issue-334-settings-before-back")
                probe.evaluate("document.getElementById('back').click();return {}")
                await { model.uiState.value.activeId == home }
                assertEquals(1, model.uiState.value.tabs.size)
                assertFalse(host.isFinishing)
                DeviceEvidence.captureScene("issue-334-source-after-back")
            }
            DeviceEvidence.writeJson("issue-334-page-back.json", "{\"historyBack\":true,\"historyGoMinusOne\":true,\"localHistoryFirst\":true,\"sourceTabRestored\":true}")
        } finally { scenario.close() }
    }
}
