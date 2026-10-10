package com.hhst.youtubelite.browser

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import com.google.gson.Gson
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** The production browser, page bridge, native decoder and settings share one real watch page. */
class YoutubeNativeUiAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun pageView(root: View, watch: Boolean = true): WebView? {
        if (root is WebView && root.isShown && (!watch || root.url?.contains("E-HuR_CEdo4") == true)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) pageView(root.getChildAt(index), watch)?.let { return it }
        return null
    }

    @Test fun liveWatchAndNativeQueueSettingsAndAboutUseTheSameAppearance() {
        assumeTrue("Supply -e network 1 for the production YouTube UI", InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = GlobalContext.get().get<PlaybackApi>()
        val model = GlobalContext.get().get<PlayerViewModel>()
        val previousQueue = model.uiState.value.queueItems.toList()
        val previousQueueEnabled = model.uiState.value.queueEnabled
        var queueChanged = false
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        lateinit var host: MainActivity
        scenario.onActivity { host = it }
        val probe = WebProbe { pageView(host.window.decorView)!! }
        fun back() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        fun capture(name: String) {
            compose.waitForIdle()
            Thread.sleep(400) // Activity and platform-window transitions use the real clock.
            compose.waitForIdle()
            DeviceEvidence.captureScene(name)
        }
        fun showMore() {
            instrumentation.runOnMainSync { if (!model.uiState.value.controlsVisible) model.onToggleControls() }
            compose.onNodeWithContentDescription(context.getString(R.string.more_options)).assertIsDisplayed().performClick()
        }
        try {
            val homeDeadline = SystemClock.elapsedRealtime() + 30_000
            var homeReady = false
            while (!homeReady && SystemClock.elapsedRealtime() < homeDeadline) {
                compose.waitForIdle()
                instrumentation.runOnMainSync { homeReady = pageView(host.window.decorView, false)?.progress == 100 }
                if (!homeReady) Thread.sleep(200)
            }
            assertTrue("Browser home did not finish", homeReady)
            WebProbe { pageView(host.window.decorView, false)!! }.evaluate("Bridge.openTab('https://m.youtube.com/watch?v=E-HuR_CEdo4');return {}")
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                compose.waitForIdle()
                instrumentation.runOnMainSync {
                    loaded = pageView(host.window.decorView)?.progress == 100 &&
                        engine.snapshot.value.videoId == "E-HuR_CEdo4" && engine.snapshot.value.prepared && engine.snapshot.value.isPlaying
                }
                if (!loaded) Thread.sleep(200)
            }
            if (!loaded) {
                val pages = mutableListOf<Map<String, Any?>>()
                fun collect(root: View) {
                    if (root is WebView) pages += mapOf("url" to root.url, "progress" to root.progress, "shown" to root.isShown)
                    if (root is ViewGroup) for (index in 0 until root.childCount) collect(root.getChildAt(index))
                }
                instrumentation.runOnMainSync { collect(host.window.decorView) }
                DeviceEvidence.writeJson("youtube-native-watch-failure.json", Gson().toJson(mapOf(
                    "pages" to pages, "engine" to engine.snapshot.value, "ui" to model.uiState.value)))
                DeviceEvidence.captureScene("youtube-native-watch-failure")
            }
            assertTrue("Production watch did not become playable: ${engine.snapshot.value}", loaded)
            val votes = probe.waitFor("""
                const row=Lite.bar(),buttons=Array.from(row.querySelectorAll('like-button-view-model,dislike-button-view-model'));
                return {ready:buttons.length===2&&buttons.every(b=>b.querySelector('[data-lite-vote-count]')),
                  title:document.title,background:getComputedStyle(document.body).backgroundColor,
                  counts:buttons.map(b=>b.querySelector('[data-lite-vote-count]')?.textContent)};
            """.trimIndent())
            DeviceEvidence.writeJson("youtube-native-watch.json", votes.toString(2))
            instrumentation.runOnMainSync { if (!model.uiState.value.controlsVisible) model.onToggleControls() }
            compose.waitForIdle()
            capture("youtube-native-live-player")
            probe.evaluate("document.getElementById('downloadButton').scrollIntoView({block:'nearest',inline:'start'});return {}")
            capture("youtube-native-live-actions")
            probe.evaluate("""
                Bridge.addToQueue(JSON.stringify({videoId:'E-HuR_CEdo4',url:location.href,
                  title:document.title.replace(' - YouTube',''),author:'Bell玲惠',
                  thumbnailUrl:'https://i.ytimg.com/vi/E-HuR_CEdo4/mqdefault.jpg'}));
                Bridge.addToQueue(JSON.stringify({videoId:'jNQXAC9IVRw',url:'https://m.youtube.com/watch?v=jNQXAC9IVRw',
                  title:'Me at the zoo',author:'jawed',thumbnailUrl:'https://i.ytimg.com/vi/jNQXAC9IVRw/mqdefault.jpg'}));
                return {};
            """.trimIndent())
            queueChanged = true
            showMore()
            compose.onNodeWithText(context.getString(R.string.queue)).assertIsDisplayed()
            capture("youtube-native-more")
            compose.onNodeWithText(context.getString(R.string.queue)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.close)).assertIsDisplayed()
            compose.onNodeWithText("Me at the zoo").assertIsDisplayed()
            capture("youtube-native-queue")
            compose.onAllNodesWithContentDescription(context.getString(R.string.queue_remove)).onLast().performClick()
            compose.onNodeWithText("Me at the zoo").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
            probe.evaluate("Bridge.extension();return {}")
            compose.onNodeWithText(context.getString(R.string.interface_category)).assertIsDisplayed().performClick()
            compose.waitForIdle()
            capture("youtube-native-extension")
            back()
            probe.evaluate("Bridge.about();return {}")
            compose.onNodeWithText(context.getString(R.string.about)).assertIsDisplayed()
            compose.waitForIdle()
            capture("youtube-native-about")
            back()
        } finally {
            instrumentation.runOnMainSync {
                engine.stop()
                if (queueChanged) {
                    model.onQueueClear()
                    previousQueue.forEach(model::onQueueAdd)
                    model.onQueueEnabled(previousQueueEnabled)
                }
            }
            scenario.close()
        }
    }
}
