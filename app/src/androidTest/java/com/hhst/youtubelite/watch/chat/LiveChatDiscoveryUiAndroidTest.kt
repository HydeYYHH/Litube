package com.hhst.youtubelite.watch.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveChatDiscoveryUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun composerAppearsAfterLoadingAndReplayRemainsReadOnly() {
        var state by mutableStateOf(LiveChatUiState(canSend = false))
        compose.setContent { LiveChatPanel(state, {}, {}) }
        compose.onNodeWithTag("live-chat-loading").assertExists()
        compose.onNodeWithTag("live-chat-readonly").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(content = LiveChatContent.Ready(listOf(
            LiveChatMessage("message", "Viewer", "hello", ""))), canSend = true) }
        compose.onNodeWithTag("live-chat-input").assertIsDisplayed()
        compose.onNodeWithTag("live-chat-readonly").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(replay = true, canSend = false) }
        compose.onNodeWithTag("live-chat-input").assertDoesNotExist()
        compose.onNodeWithTag("live-chat-ended").assertIsDisplayed()
    }

    @Test fun liveChatDistinguishesUnavailableRetryAndJoin() {
        val base = FixtureWatchRepository.samplePage("live")
        var page by mutableStateOf(base)
        val actions = mutableListOf<WatchAction>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            Box(Modifier.requiredSize(390.dp, 844.dp)) {
                WatchScreen(WatchUiState(videoId = "live", content = WatchContent.Ready(page)), actions::add, {},
                    playerSlot = { Box(it) })
            }
        } }
        compose.onNodeWithTag("watch-comments-card").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("watch-comment-preview", true).assertTextEquals("Live chat isn’t available for this stream.")
        compose.runOnIdle { page = base.copy(video = base.video.copy(chatLookupFailed = true)) }
        compose.onNodeWithTag("watch-comment-preview", true).assertTextEquals("Couldn’t load live chat. Tap to retry.")
        compose.onNodeWithTag("watch-comments-card").performClick()
        assertEquals(WatchAction.Retry, actions.last())
        compose.runOnIdle { page = base.copy(video = base.video.copy(chatKey = "chat-key")) }
        compose.onNodeWithTag("watch-comment-preview", true).assertTextEquals("Join the live chat")
        compose.onNodeWithTag("watch-comments-card").performClick()
        assertEquals(WatchAction.OpenComments("live"), actions.last())
    }
}
