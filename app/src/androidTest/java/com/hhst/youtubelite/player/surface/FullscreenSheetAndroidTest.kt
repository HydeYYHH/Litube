package com.hhst.youtubelite.player.surface

import android.content.Context
import android.media.AudioManager
import android.view.Choreographer
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Fullscreen sheets run inside the player window: system bars stay hidden and focus is retained
 * from opening through every dismissal path (scrim, back, drag, interrupted animation). */
class FullscreenSheetAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerTestActivity>()

    @Test fun fullscreenSheetsKeepBarsHiddenFromOpeningThroughDismissal() {
        var sheet by mutableStateOf<PlayerSheet?>(null)
        var dialog by mutableStateOf<PlayerDialog?>(null)
        var fullscreen by mutableStateOf(true)
        val host = PlayerWindowHost(compose.activity,
            compose.activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager, {}, { false }, { null })
        val callbacks = PlayerCallbackBridge(GlobalContext.get().get<PlayerViewModel>(), host)
        compose.setContent { AppTheme {
            PlayerWindowEffects(compose.activity, host, fullscreen, 1920, 1080)
            val state = PlayerUiState(fullscreen = fullscreen, visible = true)
            PlayerSheetHost(sheet, state, callbacks, { sheet = null }, { dialog = it })
            PlayerDialogHost(dialog, state, callbacks, { dialog = null })
        } }
        fun hidden(): Boolean {
            var value = false
            compose.runOnUiThread { value = statusBarsHidden(compose.activity) }
            return value
        }
        compose.waitUntil(10_000) { hidden() && compose.activity.window.decorView.hasWindowFocus() }
        var watching = true
        var frames = 0
        var barFrames = 0
        var lostFocusFrames = 0
        val decor = compose.activity.window.decorView
        val watcher = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!watching) return
                frames++
                if (systemBarsVisible(decor)) barFrames++
                if (!decor.hasWindowFocus()) lostFocusFrames++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        compose.runOnUiThread { Choreographer.getInstance().postFrameCallback(watcher) }
        try {
            repeat(3) { index ->
                compose.runOnIdle { sheet = PlayerSheet.More }
                compose.onNodeWithText(compose.activity.getString(R.string.more_options)).assertIsDisplayed()
                Thread.sleep(350) // Sample system bars while the fully open sheet stays visible.
                assertTrue("Opening More changed activity-window focus", decor.hasWindowFocus())
                DeviceEvidence.captureScene("fullscreen-sheet-more-open-$index")
                when (index) {
                    0 -> compose.onNodeWithTag("player-sheet-scrim").performTouchInput { click(Offset(8f, 8f)) }
                    1 -> compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    else -> compose.onNodeWithText(compose.activity.getString(R.string.more_options)).performTouchInput {
                        swipeDown(startY = height / 2f, endY = height + 700f, durationMillis = 300)
                    }
                }
                compose.waitUntil(10000) { sheet == null }
                assertTrue(hidden())
            }
            // Dismiss during the opening animation, before the sheet reaches Expanded.
            repeat(2) { index ->
                compose.waitForIdle() // Dispose the previous sheet before reopening it.
                compose.mainClock.autoAdvance = false
                try {
                    compose.runOnUiThread { sheet = PlayerSheet.More }
                    compose.mainClock.advanceTimeBy(96)
                    compose.waitForIdle()
                    if (index == 0) {
                        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    } else {
                        val handle = compose.onNodeWithTag("player-sheet-handle", useUnmergedTree = true)
                        val bounds = handle.fetchSemanticsNode().boundsInRoot
                        val endY = decor.height - bounds.top - 16f
                        handle.performTouchInput {
                            swipeDown(startY = height / 2f, endY = endY, durationMillis = 100)
                        }
                    }
                    compose.mainClock.autoAdvance = true
                    compose.waitForIdle()
                    compose.waitUntil(10000) { sheet == null }
                    compose.waitForIdle()
                } finally {
                    compose.mainClock.autoAdvance = true
                }
            }
            compose.runOnIdle { sheet = PlayerSheet.Queue }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.close)).performClick()
            compose.waitUntil(10000) { sheet == null }
            compose.runOnIdle { sheet = PlayerSheet.More }
            compose.onNodeWithText(compose.activity.getString(R.string.subtitle_style)).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.subtitle_style)).assertIsDisplayed()
            DeviceEvidence.captureScene("fullscreen-sheet-subtitle-style")
            compose.onNodeWithText(compose.activity.getString(R.string.close)).performClick()
            compose.waitUntil(10000) { dialog == null }
            compose.runOnIdle {
                assertTrue("No frames sampled", frames > 10)
                assertEquals("System bars became visible during sheet opening/stay/dismissal", 0, barFrames)
                assertEquals("Player lost focus to a separate sheet window", 0, lostFocusFrames)
            }
            DeviceEvidence.captureScene("fullscreen-sheet-restored")
        } finally {
            compose.runOnUiThread { watching = false; Choreographer.getInstance().removeFrameCallback(watcher) }
        }
        compose.runOnIdle { fullscreen = false }
        compose.waitUntil(10_000) { !hidden() }
        compose.runOnIdle { sheet = PlayerSheet.More }
        compose.onNodeWithText(compose.activity.getString(R.string.more_options)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { sheet == null }
        assertFalse("Regular player should retain visible system bars", hidden())
    }
}
