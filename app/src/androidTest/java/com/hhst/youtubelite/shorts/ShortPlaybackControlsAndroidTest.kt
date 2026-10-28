package com.hhst.youtubelite.shorts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.shorts.presentation.ShortPlaybackControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShortPlaybackControlsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
}
