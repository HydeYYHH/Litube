package com.hhst.youtubelite.ui.design

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.presentation.ChannelAboutPanel
import com.hhst.youtubelite.watch.chat.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BottomSheetDragAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun channelHeaderTracksTheFingerAndShortDragsReturnBeforeSwipeDismissal() {
        var open by mutableStateOf(true)
        var closes = 0
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalMotionScale provides 1f) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    if (open) ChannelAboutPanel(FixtureChannelRepository.profile(), 390f, 844f, {
                        if (it == ChannelAction.CloseOverlay) { closes++; open = false }
                    })
                }
            }
        } }
        val header = compose.onNodeWithTag("channel-about-drag-header")
        val panel = compose.onNodeWithTag("channel-about-panel")
        val top = panel.fetchSemanticsNode().boundsInRoot.top
        header.performTouchInput { down(Offset(180f, 24f)); moveBy(Offset(0f, 36f), 300) }
        assertTrue(panel.fetchSemanticsNode().boundsInRoot.top > top + 8f)
        header.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(top, panel.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(0, closes)
        // Body scrolling never invokes the header's dismiss gesture.
        compose.onNodeWithTag("channel-about-content").performTouchInput { swipeDown() }
        assertEquals(0, closes)
        header.performTouchInput { swipe(Offset(180f, 24f), Offset(180f, 180f), 500) }
        compose.waitForIdle()
        panel.assertDoesNotExist()
        assertEquals(1, closes)
    }

    @Test fun liveChatHeaderDismissesItsBottomSheet() {
        var open by mutableStateOf(true)
        var closes = 0
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalMotionScale provides 0f) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    if (open) LiveChatPanel(LiveChatUiState(), {}, { closes++; open = false })
                }
            }
        } }
        compose.onNodeWithTag("live-chat-drag-header").performTouchInput {
            swipe(Offset(180f, 20f), Offset(180f, 160f), 500)
        }
        compose.onNodeWithTag("live-chat").assertDoesNotExist()
        assertEquals(1, closes)
    }

    @Test fun cancelledHeaderGestureReturnsAndCloseButtonStillWorks() {
        var open by mutableStateOf(true)
        var closes = 0
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalMotionScale provides 0f) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    if (open) ChannelAboutPanel(FixtureChannelRepository.profile(), 390f, 844f, {
                        if (it == ChannelAction.CloseOverlay) { closes++; open = false }
                    })
                }
            }
        } }
        val panel = compose.onNodeWithTag("channel-about-panel")
        val top = panel.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("channel-about-drag-header").performTouchInput {
            down(Offset(180f, 20f)); moveBy(Offset(0f, 100f)); cancel()
        }
        assertEquals(top, panel.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(0, closes)
        compose.onNodeWithContentDescription(compose.activity.getString(com.hhst.youtubelite.R.string.a11y_close_channel_details)).performClick()
        panel.assertDoesNotExist()
        assertEquals(1, closes)
    }
}
