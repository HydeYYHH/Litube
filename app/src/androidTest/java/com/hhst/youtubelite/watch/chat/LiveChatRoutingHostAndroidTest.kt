package com.hhst.youtubelite.watch.chat

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.PhoneWindow
import com.hhst.youtubelite.watch.WatchActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-level live routing: the comment slot renders the live chat panel only for a live page with a
 * chat continuation; a page without a continuation never fabricates a chat. Routing comes from
 * WatchActivity's combined watch/player state, not from the panel itself.
 */
@RunWith(AndroidJUnit4::class)
class LiveChatRoutingHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var watch: ActivityScenario<WatchActivity>? = null

    @Before fun enterPhoneWindow() = PhoneWindow.enter()
    @After fun exitPhoneWindow() = PhoneWindow.exit()

    @After fun close() {
        watch?.close()
        watch = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun launch(videoId: String, chatKey: String? = null) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), WatchActivity::class.java)
            .putExtra(WatchActivity.EXTRA_VIDEO_ID, videoId)
            .putExtra(WatchActivity.EXTRA_SCENARIO, com.hhst.youtubelite.watch.data.WatchScenario.Content.argument)
        chatKey?.let { intent.putExtra(WatchActivity.EXTRA_CHAT_KEY, it) }
        watch = ActivityScenario.launch(intent)
        awaitNode("watch-metadata")
    }

    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }
    private fun awaitGone(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) == 0 }

    private fun systemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun aLivePageWithAChatContinuationOpensTheChatPanelInsteadOfComments() {
        launch("live", chatKey = "fixture-chat")
        compose.onNodeWithTag("watch-comments-card").performClick()
        awaitNode("live-chat-panel-host")
        awaitNode("live-chat-empty")
        assertEquals("The comments panel must not render beside the chat", 0, nodeCount("comments-panel-host"))
        watch?.onActivity { activity ->
            assertTrue("Routing must open the chat stream",
                activity.chatViewModel.uiState.value.content is LiveChatContent.Ready)
        }

        systemBack()
        awaitGone("live-chat-panel-host")
        compose.onNodeWithTag("watch-screen").assertExists()
        watch?.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun aLivePageWithoutAChatContinuationShowsUnavailable() {
        launch("live")
        compose.onNodeWithTag("watch-comments-card").assertIsNotEnabled()
        assertEquals(0, nodeCount("comments-panel-host"))
        assertEquals("No continuation must never fabricate a chat", 0, nodeCount("live-chat-panel-host"))
        watch?.onActivity { activity ->
            assertFalse(activity.chatViewModel.uiState.value.content is LiveChatContent.Ready)
        }
        watch?.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun avodPageStaysOnCommentsEvenWhenAChatKeyIsPresent() {
        launch("bunny", chatKey = "archived-chat")
        compose.onNodeWithTag("watch-comments-card").performClick()
        awaitNode("comments-panel-host")
        awaitNode("comments-list")
        assertEquals(0, nodeCount("live-chat-panel-host"))
        watch?.onActivity { assertFalse(it.isFinishing) }
    }
}
