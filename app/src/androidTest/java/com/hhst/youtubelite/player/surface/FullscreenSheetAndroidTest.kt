package com.hhst.youtubelite.player.surface

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.Choreographer
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.hhst.youtubelite.player.engine.LoopMode
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

    @Test fun endBehaviorRequiresASelectionAndKeepsTheQueueOpen() {
        var sheet by mutableStateOf<PlayerSheet?>(PlayerSheet.More)
        var dialog by mutableStateOf<PlayerDialog?>(null)
        var state by mutableStateOf(PlayerUiState(visible = true))
        var selections = 0
        val callbacks = proxyActions("EndBehaviorActions") { name, args ->
            if (name == "onLoopMode") {
                selections++
                state = state.copy(loopMode = args!![0] as LoopMode)
            }
        }
        compose.setContent { AppTheme {
            PlayerSheetHost(sheet, state, remember { mutableStateOf(0L) }, callbacks,
                { sheet = null }, { dialog = it }, { sheet = it }, { sheet = PlayerSheet.Queue })
            PlayerDialogHost(dialog, state, callbacks, { dialog = null })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.playback_end_next)).performClick()
        compose.onNodeWithTag("player-dialog-end-behavior").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.playback_end_next)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, selections); assertEquals(null, sheet) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { dialog == null }
        compose.runOnIdle { assertEquals(0, selections); sheet = PlayerSheet.Queue }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.playback_end_next)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.playback_end_pause)).performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(LoopMode.PAUSE_AT_END, state.loopMode)
            assertEquals(PlayerSheet.Queue, sheet)
            assertEquals(null, dialog)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.playback_end_pause)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.playback_end_loop)).performClick()
        compose.runOnIdle { assertEquals(LoopMode.LOOP_ONE, state.loopMode); assertEquals(PlayerSheet.Queue, sheet) }
    }

    @Test fun fullscreenSheetsKeepBarsHiddenFromOpeningThroughDismissal() {
        // Sample sheet focus after the activity's fullscreen rotation has settled.
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        var sheet by mutableStateOf<PlayerSheet?>(null)
        var dialog by mutableStateOf<PlayerDialog?>(null)
        var fullscreen by mutableStateOf(true)
        val host = PlayerWindowHost(compose.activity,
            compose.activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager, {}, { false }, { null })
        val callbacks = PlayerCallbackBridge(GlobalContext.get().get<PlayerViewModel>(), host)
        compose.setContent { AppTheme {
            PlayerWindowEffects(compose.activity, host, fullscreen, 1920, 1080)
            val state = PlayerUiState(fullscreen = fullscreen, visible = true)
            PlayerSheetHost(sheet, state, remember { mutableStateOf(0L) }, callbacks, { sheet = null }, { dialog = it }, { sheet = it })
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
        var watchSheetFocus = true
        var phase = "initial"
        val focusLossPhases = mutableSetOf<String>()
        val decor = compose.activity.window.decorView
        val watcher = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!watching) return
                frames++
                if (systemBarsVisible(decor)) barFrames++
                if (watchSheetFocus && !decor.hasWindowFocus()) {
                    lostFocusFrames++
                    focusLossPhases += phase
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        compose.runOnUiThread { Choreographer.getInstance().postFrameCallback(watcher) }
        try {
            repeat(3) { index ->
                compose.runOnIdle { phase = "more-$index"; sheet = PlayerSheet.More }
                compose.onNodeWithText(compose.activity.getString(R.string.player_info_quality)).assertIsDisplayed()
                Thread.sleep(350) // Sample system bars while the fully open sheet stays visible.
                assertTrue("Opening More changed activity-window focus", decor.hasWindowFocus())
                DeviceEvidence.captureScene("fullscreen-sheet-more-open-$index")
                when (index) {
                    0 -> compose.onNodeWithTag("player-sheet-scrim").performTouchInput { click(Offset(8f, 8f)) }
                    1 -> compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    else -> {
                        val handle = compose.onNodeWithTag("player-sheet-handle", useUnmergedTree = true)
                        val bounds = handle.fetchSemanticsNode().boundsInRoot
                        val endY = decor.height - bounds.top - 16f
                        handle.performTouchInput {
                            swipeDown(startY = height / 2f, endY = endY, durationMillis = 300)
                        }
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
                    compose.runOnUiThread { phase = "opening-$index"; sheet = PlayerSheet.More }
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
            compose.runOnIdle { phase = "queue"; sheet = PlayerSheet.Queue }
            compose.waitForIdle()
            DeviceEvidence.captureScene("fullscreen-sheet-queue")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.close)).performClick()
            compose.waitUntil(10000) { sheet == null }
            compose.runOnIdle { phase = "subtitle-style"; sheet = PlayerSheet.More }
            compose.runOnIdle { watchSheetFocus = true }
            compose.onNodeWithText(compose.activity.getString(R.string.subtitle_style)).performClick()
            compose.onNodeWithTag("player-sheet-subtitle-style").assertIsDisplayed()
            DeviceEvidence.captureScene("fullscreen-sheet-subtitle-style")
            compose.onNodeWithTag("subtitle-style-close").performClick()
            compose.waitUntil(10000) { sheet == null }
            compose.runOnIdle {
                assertTrue("No frames sampled", frames > 10)
                assertEquals("System bars became visible during sheet opening/stay/dismissal", 0, barFrames)
                assertEquals("Player lost focus during $focusLossPhases", 0, lostFocusFrames)
            }
            DeviceEvidence.captureScene("fullscreen-sheet-restored")
        } finally {
            compose.runOnUiThread { watching = false; Choreographer.getInstance().removeFrameCallback(watcher) }
        }
        compose.runOnIdle { fullscreen = false }
        compose.waitUntil(10_000) { !hidden() }
        compose.runOnIdle { sheet = PlayerSheet.More }
        compose.onNodeWithText(compose.activity.getString(R.string.player_info_quality)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { sheet == null }
        assertFalse("Regular player should retain visible system bars", hidden())
    }

    @Test fun playerOptionsOpenIndependentDialogs() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        var sheet by mutableStateOf<PlayerSheet?>(PlayerSheet.More)
        var dialog by mutableStateOf<PlayerDialog?>(null)
        var quality: String? = "unset"
        var speed = 0f
        var resize: ResizeMode? = null
        var audioPicked = false
        val callbacks = proxyActions("SheetSubpageActions") { name, args ->
            if (name == "onQuality") quality = args!![0] as String?
            if (name == "onSpeed") speed = args!![0] as Float
            if (name == "onResize") resize = args!![0] as ResizeMode
            if (name == "onAudioTrack") audioPicked = true
        }
        var state by mutableStateOf(PlayerUiState(fullscreen = true, visible = true, speed = 1f, qualityLabel = "720p", activeQuality = "720p",
            qualities = listOf("1080p", "720p", "480p")))
        compose.setContent { AppTheme {
            PlayerSheetHost(sheet, state, remember { mutableStateOf(0L) }, callbacks,
                { sheet = null }, { dialog = it }, { sheet = it })
            PlayerDialogHost(dialog, state, callbacks, { dialog = null })
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.player_info_quality))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.player_info_quality)).performClick()
        compose.onNodeWithTag("player-dialog-quality").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, sheet); assertEquals(PlayerDialog.Quality, dialog) }
        compose.onNodeWithText(compose.activity.getString(R.string.audio_track)).assertDoesNotExist()
        compose.onNodeWithText("720p").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { dialog == null }
        compose.runOnIdle { assertEquals("720p", quality); assertEquals(null, sheet); sheet = PlayerSheet.More }
        compose.onNodeWithText(compose.activity.getString(R.string.info_playback_speed)).performClick()
        compose.onNodeWithTag("player-dialog-speed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, sheet); assertEquals(PlayerDialog.Speed, dialog) }
        compose.onNodeWithText("1.5x").performClick()
        compose.waitUntil(10_000) { dialog == null }
        compose.runOnIdle { assertEquals(1.5f, speed); assertEquals(null, sheet) }
        compose.runOnIdle { sheet = PlayerSheet.More }
        compose.onNodeWithText(compose.activity.getString(R.string.resize_mode)).performClick()
        compose.onNodeWithTag("player-dialog-resize").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, sheet); assertEquals(PlayerDialog.Resize, dialog) }
        compose.onNodeWithText(compose.activity.getString(R.string.resize_zoom)).performClick()
        compose.waitUntil(10_000) { dialog == null }
        compose.runOnIdle { assertEquals(ResizeMode.Zoom, resize); assertEquals(null, sheet) }
        compose.runOnIdle { sheet = PlayerSheet.More }
        compose.onNodeWithText(compose.activity.getString(R.string.audio_track)).performClick()
        compose.onNodeWithTag("player-dialog-audio").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, sheet); assertEquals(PlayerDialog.Audio, dialog) }
        compose.onNodeWithText(compose.activity.getString(R.string.player_audio_default)).performClick()
        compose.waitUntil(10_000) { dialog == null }
        compose.runOnIdle { assertTrue(audioPicked); assertEquals(null, sheet) }
        compose.runOnIdle { sheet = PlayerSheet.More }
        compose.onNodeWithText(compose.activity.getString(R.string.player_info_quality)).performClick()
        compose.onNodeWithTag("player-dialog-quality").assertIsDisplayed()
        compose.waitUntil(10_000) { !compose.activity.window.decorView.hasWindowFocus() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { dialog == null }
        compose.runOnIdle { assertEquals(null, sheet) }
        compose.runOnIdle { state = state.copy(qualityLabel = null); dialog = PlayerDialog.Quality }
        compose.onNodeWithTag("player-dialog-quality").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.player_quality_auto)).assertIsSelected()
        compose.onNodeWithTag("player-dialog-scrim").performTouchInput { click(Offset(8f, 8f)) }
        compose.waitUntil(10_000) { dialog == null }
    }
}
