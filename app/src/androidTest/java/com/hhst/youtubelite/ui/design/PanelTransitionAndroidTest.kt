package com.hhst.youtubelite.ui.design

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.presentation.ChannelAboutPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PanelTransitionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bottomPanelBackDuringEntryFinishesOneExit() = checkExit(true)
    @Test fun sidePanelBackDuringEntryFinishesOneExit() = checkExit(false)

    @Test fun channelAboutScrimFadesAlongWithThePanelOnEntryAndExit() {
        assumeTrue(readMotionScale(compose.activity.contentResolver) == 1f)
        var open by mutableStateOf(true)
        var closes = 0
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                AppTheme { CompositionLocalProvider(LocalDensity provides Density(1f), LocalMotionScale provides 1f) {
                    Box(Modifier.requiredSize(390.dp, 844.dp).background(Color.White).testTag("channel-about-host")) {
                        if (open) ChannelAboutPanel(FixtureChannelRepository.profile(), 390f, 844f, { action ->
                            if (action == ChannelAction.CloseOverlay) { closes++; open = false }
                        })
                    }
                } }
            }
            fun scrimPixel(): Color {
                // Android draw is driven separately from the frozen Compose clock.
                compose.waitForIdle()
                return compose.onNodeWithTag("channel-about-host").captureToImage().toPixelMap()[10, 10]
            }
            compose.mainClock.advanceTimeBy(48)
            val entering = scrimPixel()
            compose.mainClock.advanceTimeBy(240)
            val settled = scrimPixel()
            assertTrue("The scrim must fade in with the panel", entering.green > settled.green && entering.green < 1f)
            // The panel covers the scrim center; tap its exposed top corner.
            compose.onNodeWithTag("channel-about-scrim").performTouchInput { click(Offset(10f, 10f)) }
            // Commit closing before measuring the exit: its animation receives t=0 on the next frame; the remaining 48ms reaches a visible midpoint.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("channel-about-panel").assertExists()
            assertEquals(0, closes)
            val exiting = scrimPixel()
            assertTrue("The scrim must fade before dismissal: settled=$settled, exiting=$exiting, clock=${compose.mainClock.currentTime}",
                exiting.green > settled.green && exiting.green < 1f)
            compose.mainClock.advanceTimeBy(240)
            compose.onNodeWithTag("channel-about-panel").assertDoesNotExist()
            assertEquals(1, closes)
            assertEquals(Color.White, scrimPixel())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun backBelongsToTheCommentMenuBeforeItsParentPanel() {
        var menuOpen by mutableStateOf(true)
        var closes = 0
        compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 0f) {
            BackHandler { menuOpen = false }
            PanelTransition(false, { closes++ }, backEnabled = !menuOpen) { motion, _ ->
                Box(Modifier.size(240.dp).then(motion))
            }
        } } }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(false, menuOpen); assertEquals(0, closes) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    private fun checkExit(bottom: Boolean) {
        var open by mutableStateOf(true)
        var closes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { CompositionLocalProvider(LocalMotionScale provides 1f) {
            if (open) PanelTransition(bottom, { closes++; open = false }) { motion, _ ->
                Box(Modifier.size(240.dp, 240.dp).then(motion).testTag("motion-panel"))
            }
        } } }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("motion-panel").assertExists()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("motion-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes) }
        compose.mainClock.autoAdvance = true
    }
}
