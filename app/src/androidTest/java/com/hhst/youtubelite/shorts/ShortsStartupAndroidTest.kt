package com.hhst.youtubelite.shorts

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.shorts.domain.ShortsContent
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Opt-in real UI observation; latency is recorded, never asserted against a network-dependent limit. */
class ShortsStartupAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settledSwipesReachActualVideoFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val trace = JSONArray()
        val openedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch(ShortsActivity::class.java).use { scenario ->
            val player = GlobalContext.get().get<PlaybackApi>()
            fun current(): String? {
                var id: String? = null
                scenario.onActivity { id = it.shortsViewModel.uiState.value.currentItemId }
                return id
            }
            repeat(4) { step ->
                val previous = if (step == 0) null else current()
                val began = if (step == 0) openedAt else SystemClock.elapsedRealtime()
                if (step > 0) compose.onNodeWithTag("shorts-pager").performTouchInput { swipeUp() }
                var frameMs: Long? = null
                var id: String? = null
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    compose.mainClock.advanceTimeByFrame()
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    check(focused || SystemClock.elapsedRealtime() - openedAt < 1_000) {
                        "Shorts lost foreground; stopping device interaction"
                    }
                    id = current()
                    val state = player.snapshot.value
                    if (id != null && id != previous && state.videoId == id && state.firstFrameRendered) {
                        frameMs = SystemClock.elapsedRealtime() - began
                        break
                    }
                    Thread.sleep(40)
                }
                val state = player.snapshot.value
                var items = emptyList<String>()
                scenario.onActivity { items = (it.shortsViewModel.uiState.value.content as? ShortsContent.Ready)
                    ?.page?.items?.map { item -> item.id }.orEmpty() }
                trace.put(JSONObject().put("step", step).put("video", id).put("firstFrameMs", frameMs ?: JSONObject.NULL)
                    .put("height", state.videoHeight).put("width", state.videoWidth)
                    .put("error", state.error ?: JSONObject.NULL).put("feedIds", JSONArray(items)))
                File(instrumentation.targetContext.filesDir, "shorts-startup.json").writeText(trace.toString(2))
                assertTrue("No actual frame after settled swipe $step", frameMs != null)
                // Compose's test clock must advance during viewing too: first-frame effects start the next prefetch, and sleeping alone postpones them until the swipe.
                val dwellUntil = SystemClock.elapsedRealtime() + 3_000
                while (SystemClock.elapsedRealtime() < dwellUntil) {
                    compose.mainClock.advanceTimeByFrame()
                    Thread.sleep(40)
                }
            }
        }
    }
}
