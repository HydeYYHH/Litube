package com.hhst.youtubelite.shorts

import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.shorts.presentation.ShortPlaybackControls
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ShortPlaybackControlsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun portraitHost() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                compose.activity.window.decorView.hasWindowFocus()
        }
        compose.waitForIdle()
    }

    @Test fun tapTogglesAndHoldingRestoresOnRelease() {
        val events = mutableListOf<String>()
        compose.setContent {
            ShortPlaybackControls(true, { events += "toggle" }, { events += "hold" }, { events += "release" }) {}
        }
        val controls = compose.onNodeWithTag("short-playback-controls")
        controls.performTouchInput { click() }
        controls.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf("toggle", "hold", "release"), events) }
    }

    @Test fun cancellingOrRemovingAHeldPageRestoresSpeed() {
        val events = mutableListOf<String>()
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) ShortPlaybackControls(true, {}, { events += "hold" }, { events += "release" }) {}
        }
        val controls = compose.onNodeWithTag("short-playback-controls")
        controls.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("short-speed-hold").assertIsDisplayed()
        controls.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(listOf("hold", "release"), events) }
        controls.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertEquals(listOf("hold", "release", "hold", "release"), events) }
    }

    @Test fun verticalSwipeDoesNotToggleOrActivateTheHold() {
        val events = mutableListOf<String>()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ShortPlaybackControls(true, { events += "toggle" }, { events += "hold" }, { events += "release" }) {}
            }
        }
        compose.onNodeWithTag("short-playback-controls").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
    }

    @Test fun loadingCannotStartASpeedHold() {
        val events = mutableListOf<String>()
        compose.setContent {
            ShortPlaybackControls(true, { events += "toggle" }, { events += "hold" },
                { events += "release" }, loading = true) {}
        }
        compose.onNodeWithTag("short-playback-controls").performTouchInput { longClick() }
        compose.onNodeWithTag("short-buffering").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
    }

    @Test fun mediaPreparationKeepsLoadingUntilTheFirstFrameWithoutAPlaybackIcon() {
        val loading = mutableStateOf(true)
        val ready = mutableStateOf(false)
        var toggles = 0
        compose.setContent {
            ShortPlaybackControls(false, { toggles++ }, {}, {}, loading = loading.value,
                ready = ready.value, paused = false) {}
        }
        compose.onNodeWithTag("short-buffering").assertIsDisplayed()
        compose.onNodeWithTag("short-playback-controls").performClick()
        compose.runOnIdle { assertEquals(0, toggles); loading.value = false }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("short-buffering").assertIsDisplayed()
        compose.onNodeWithTag("short-paused").assertDoesNotExist()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithTag("short-buffering").assertDoesNotExist()
        compose.onNodeWithTag("short-paused").assertDoesNotExist()
    }

    @Test fun anAccessRestrictionEndsLoadingAndExplainsWhyPlaybackStopped() {
        val notice = mutableStateOf<String?>(null)
        var actions = 0
        compose.setContent {
            ShortPlaybackControls(false, { actions++ }, { actions++ }, {},
                ready = false, notice = notice.value) {}
        }
        compose.onNodeWithTag("short-buffering").assertIsDisplayed()
        compose.runOnIdle { notice.value = compose.activity.getString(com.hhst.youtubelite.R.string.player_payment_required) }
        compose.onNodeWithTag("short-buffering").assertDoesNotExist()
        compose.onNodeWithTag("short-access-notice").assertIsDisplayed()
        compose.onNodeWithTag("short-paused").assertDoesNotExist()
        compose.onNodeWithTag("short-playback-controls").performClick()
        compose.onNodeWithTag("short-playback-controls").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun swipingAndBriefPlaybackTransitionsNeverShowThePauseIndicator() {
        val active = mutableStateOf(false)
        val paused = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ShortPlaybackControls(false, {}, {}, {}, active = active.value, paused = paused.value) {}
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("short-paused").assertDoesNotExist()
        compose.runOnIdle { active.value = true }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { paused.value = false }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("short-paused").assertDoesNotExist()
        compose.runOnIdle { paused.value = true }
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithTag("short-paused").assertIsDisplayed()
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("short-paused").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}
