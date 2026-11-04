package com.hhst.youtubelite.shorts

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class ShortsPlaybackSequenceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun successiveRealPagesRenderTheirOwnMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Supply -e network 1", args.getString("network") == "1")
        val count = args.getString("shortsCount")?.toIntOrNull()?.coerceIn(2, 20) ?: 10
        val engine = GlobalContext.get().get<PlaybackApi>()
        val model = GlobalContext.get().get<PlayerViewModel>()
        val scenario = ActivityScenario.launch<ShortsActivity>(
            ShortsActivity.intent(instrumentation.targetContext, args.getString("videoId")))
        lateinit var viewModel: ShortsViewModel
        scenario.onActivity { viewModel = it.shortsViewModel }
        try {
            compose.waitUntil(60_000) { viewModel.uiState.value.content !is ShortsContent.Loading }
            assertTrue("The real feed did not load", viewModel.uiState.value.content is ShortsContent.Ready)
            val seen = mutableSetOf<String>()
            repeat(count) { index ->
                compose.waitUntil(15_000) { viewModel.uiState.value.currentItemId != null }
                val id = requireNotNull(viewModel.uiState.value.currentItemId)
                assertTrue("The pager did not advance", seen.add(id))
                val started = SystemClock.elapsedRealtime()
                compose.waitUntil(60_000) {
                    val state = engine.snapshot.value
                    state.videoId == id && (state.error != null || state.firstFrameRendered && state.isPlaying)
                }
                val state = engine.snapshot.value
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("shortsPlayback", "index=$index video=$id elapsed_ms=${SystemClock.elapsedRealtime() - started} first_frame=${state.firstFrameRendered} error=${state.error != null}")
                })
                assertNull("Short $index ($id) failed", state.error)
                assertTrue("Short $index rendered no matching frame", state.firstFrameRendered)
                val initialPosition = state.positionMs
                compose.waitUntil(5_000) {
                    val current = engine.snapshot.value
                    current.videoId == id && current.error == null && current.isPlaying && current.positionMs > initialPosition
                }
                // Dwell before the swipe: prefetch must land inside the window the viewer actually occupies, not just at maximum speed.
                val dwell = args.getString("dwellMs")?.toLongOrNull() ?: 0L
                if (dwell > 0) Thread.sleep(dwell)
                if (index < count - 1) {
                    compose.onNodeWithTag("shorts-pager").performTouchInput { swipeUp(durationMillis = 250) }
                    compose.waitUntil(15_000) { viewModel.uiState.value.currentItemId != id }
                }
            }
        } finally {
            instrumentation.runOnMainSync { model.stopOnExit() }
            scenario.close()
        }
    }
}
