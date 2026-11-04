package com.hhst.youtubelite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.presentation.ChannelHeader
import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.saves.domain.NewPlaylistDraft
import com.hhst.youtubelite.saves.presentation.NewPlaylistSheet
import com.hhst.youtubelite.search.domain.SearchChannel
import com.hhst.youtubelite.search.presentation.ChannelResultsList
import com.hhst.youtubelite.ui.design.AppTheme
import com.hhst.youtubelite.ui.design.SubscribeButton
import com.hhst.youtubelite.watch.chat.*
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchScreen
import org.junit.Rule
import org.junit.Test

/** Exercise the visible waiting state, including controls whose clicks used to be silently ignored. */
class WriteFeedbackAndroidTest {
    @get:Rule val compose = createComposeRule()

    private fun render(content: @Composable () -> Unit) {
        compose.setContent { AppTheme { Box(Modifier.fillMaxSize()) { content() } } }
    }

    private fun progressInside(tag: String) = compose.onNode(
        hasTestTag("write-progress") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    @Test fun subscriptionProgressIsVisibleAndDisappearsWhenTheRequestFinishes() {
        val pending = mutableStateOf(true)
        render { SubscribeButton(false, {}, pending = pending.value, testTag = "subscribe") }
        progressInside("subscribe").assertIsDisplayed()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("subscribe")), useUnmergedTree = true)
            .assertIsNotEnabled()
        compose.runOnIdle { pending.value = false }
        progressInside("subscribe").assertDoesNotExist()
    }

    @Test fun videoRatingShowsProgressOnTheTappedButton() {
        render {
            WatchScreen(WatchUiState("bunny", content = WatchContent.Ready(FixtureWatchRepository.samplePage("bunny")),
                actionPending = true, pendingVideoAction = WatchAction.Like), {}, {})
        }
        progressInside("watch-action-like").assertIsDisplayed()
        compose.onNodeWithTag("watch-action-like").assertIsNotEnabled()
        compose.onNodeWithTag("watch-action-dislike").assertIsNotEnabled()
    }

    @Test fun liveChatKeepsTheDraftAndShowsSendingProgress() {
        render { LiveChatPanel(LiveChatUiState(content = LiveChatContent.Ready(emptyList()), draft = "Message", sending = true), {}, {}) }
        progressInside("live-chat-send").assertIsDisplayed()
        compose.onNodeWithTag("live-chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("live-chat-input").assertTextEquals("Message")
    }

    @Test fun deletingACommentShowsProgressAndDisablesTheConfirmation() {
        render { CommentsPanel(CommentsUiState(videoId = "v", overlay = CommentsOverlay.DeleteConfirm("c", null),
            actionPending = true), "1", {}, {}) }
        progressInside("comment-delete-confirm").assertIsDisplayed()
        compose.onNodeWithTag("comment-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("comment-delete-cancel").assertIsNotEnabled()
    }

    @Test fun creatingAPlaylistShowsProgressAndKeepsItsTitle() {
        render { NewPlaylistSheet(NewPlaylistDraft("My playlist", ""), {}, {}, {}, pending = true) }
        progressInside("new-playlist-create").assertIsDisplayed()
        compose.onNodeWithTag("new-playlist-create").assertIsNotEnabled()
        compose.onNodeWithTag("new-playlist-title").assertTextEquals("My playlist")
    }

    @Test fun channelHeaderShowsSubscriptionProgress() {
        render { ChannelHeader(FixtureChannelRepository.samplePage(), 390f, {}, subscribePending = true) }
        progressInside("channel-subscribe").assertIsDisplayed()
        compose.onNodeWithTag("channel-subscribe").assertIsNotEnabled()
    }

    @Test fun searchSubscriptionRendersConfirmedMembershipAfterProgress() {
        val pending = mutableStateOf(setOf("c"))
        val subscriptions = mutableStateOf(emptyMap<String, Boolean>())
        render { ChannelResultsList(listOf(SearchChannel("c", "Channel", "", "", "")), rememberLazyListState(), {},
            subscriptions = subscriptions.value, subscriptionsPending = pending.value) }
        progressInside("search-channel-subscribe-c").assertIsDisplayed()
        compose.runOnIdle { subscriptions.value = mapOf("c" to true); pending.value = emptySet() }
        progressInside("search-channel-subscribe-c").assertDoesNotExist()
        compose.onNodeWithText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(com.hhst.youtubelite.R.string.subscribed)).assertIsDisplayed()
    }
    @Test fun shortsRatingShowsProgressAndDisablesTheTappedControl() {
        val item = com.hhst.youtubelite.shorts.data.FixtureShortsRepository.sample().first()
        render { com.hhst.youtubelite.shorts.presentation.ShortsScreen(
            com.hhst.youtubelite.shorts.domain.ShortsUiState(
                content = com.hhst.youtubelite.shorts.domain.ShortsContent.Ready(
                    com.hhst.youtubelite.shorts.domain.ShortsPage(listOf(item))),
                startItemId = item.id, currentItemId = item.id, ratingsPending = setOf(item.id)), {}) }
        compose.onNodeWithTag("write-progress", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Like ${item.title}").assertIsNotEnabled()
    }

    @Test fun backgroundProgressStaysVisibleUntilTheMenuWriteFinishes() {
        val feedback = com.hhst.youtubelite.core.WriteFeedback()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        render {
            scope = androidx.compose.runtime.rememberCoroutineScope()
            com.hhst.youtubelite.ui.design.WriteProgressOverlay(feedback)
        }
        compose.onNodeWithTag("background-write-progress").assertDoesNotExist()
        compose.runOnIdle { feedback.launch(scope) { gate.await() } }
        compose.onNodeWithTag("background-write-progress").assertIsDisplayed()
        compose.runOnIdle { gate.complete(Unit) }
        compose.onNodeWithTag("background-write-progress").assertDoesNotExist()
    }

}
