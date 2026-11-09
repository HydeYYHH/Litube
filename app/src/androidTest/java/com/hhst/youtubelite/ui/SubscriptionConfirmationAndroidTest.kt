package com.hhst.youtubelite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelAction
import com.hhst.youtubelite.channel.presentation.ChannelHeader
import com.hhst.youtubelite.search.domain.SearchAction
import com.hhst.youtubelite.search.domain.SearchChannel
import com.hhst.youtubelite.search.presentation.ChannelResultsList
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.domain.*
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import com.hhst.youtubelite.ui.design.AppTheme
import com.hhst.youtubelite.ui.design.SubscribeButton
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubscriptionConfirmationAndroidTest {
    @get:Rule val compose = createComposeRule()

    private fun render(content: @Composable () -> Unit) {
        compose.setContent { AppTheme { Box(Modifier.fillMaxSize()) { content() } } }
    }

    private fun tapSubscription(tag: String) {
        compose.onNode(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true).performClick()
    }

    private fun checkConfirmation(tag: String, calls: () -> Int) {
        tapSubscription(tag)
        compose.onNodeWithTag("unsubscribe-dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls()) }
        compose.onNodeWithTag("unsubscribe-cancel").performClick()
        compose.onNodeWithTag("unsubscribe-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls()) }
        tapSubscription(tag)
        compose.onNodeWithTag("unsubscribe-confirm").performClick()
        compose.onNodeWithTag("unsubscribe-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls()) }
    }

    @Test fun watchRequiresConfirmationBeforeUnsubscribing() {
        var writes = 0
        val page = FixtureWatchRepository.samplePage("bunny")
        render { WatchScreen(WatchUiState("bunny", content = WatchContent.Ready(
            page.copy(video = page.video.copy(subscribed = true)))), {
                if (it == WatchAction.Subscribe) writes++
            }, {}) }
        checkConfirmation("watch-action-subscribe") { writes }
    }

    @Test fun shortsRequireConfirmationBeforeUnsubscribing() {
        var writes = 0
        val item = FixtureShortsRepository.sample().first()
        render { ShortsScreen(ShortsUiState(content = ShortsContent.Ready(ShortsPage(listOf(item))),
            currentItemId = item.id, startItemId = item.id, subscriptions = mapOf(item.channelId to true)), {
                if (it is ShortsAction.Subscribe) writes++
            }) }
        checkConfirmation("short-subscribe-${item.id}") { writes }
    }

    @Test fun searchRequiresConfirmationBeforeUnsubscribing() {
        var writes = 0
        render { ChannelResultsList(listOf(SearchChannel("channel", "Channel", "", "", "")),
            rememberLazyListState(), { if (it is SearchAction.Subscribe) writes++ },
            subscriptions = mapOf("channel" to true)) }
        checkConfirmation("search-channel-subscribe-channel") { writes }
    }

    @Test fun channelHeaderRequiresConfirmationBeforeUnsubscribing() {
        var writes = 0
        val page = FixtureChannelRepository.samplePage()
        render { ChannelHeader(page.copy(profile = page.profile.copy(subscribed = true)), 390f, {
            if (it == ChannelAction.Subscribe) writes++
        }) }
        checkConfirmation("channel-subscribe") { writes }
    }

    @Test fun subscribingRemainsImmediate() {
        var writes = 0
        render { SubscribeButton(false, { writes++ }, testTag = "subscribe") }
        tapSubscription("subscribe")
        compose.onNodeWithTag("unsubscribe-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun aPendingWriteClosesTheConfirmationAndBlocksAnotherTap() {
        var writes = 0
        val pending = mutableStateOf(false)
        render { SubscribeButton(true, { writes++ }, pending = pending.value, testTag = "subscribe") }
        tapSubscription("subscribe")
        compose.onNodeWithTag("unsubscribe-dialog").assertIsDisplayed()
        compose.runOnIdle { pending.value = true }
        compose.onNodeWithTag("unsubscribe-dialog").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("subscribe")), useUnmergedTree = true)
            .assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun changingChannelsCannotConfirmThePreviousChannelsUnsubscribe() {
        var writes = 0
        val channel = mutableStateOf("first")
        render { SubscribeButton(true, { writes++ }, channelId = channel.value, testTag = "subscribe") }
        tapSubscription("subscribe")
        compose.onNodeWithTag("unsubscribe-dialog").assertIsDisplayed()
        compose.runOnIdle { channel.value = "second" }
        compose.onNodeWithTag("unsubscribe-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun aConfirmedOrdinaryVideoRatingHasVisibleSelectionAndSeparateCounts() {
        val page = FixtureWatchRepository.samplePage("bunny")
        val video = mutableStateOf(page.video.copy(rating = VideoRating.Like, likeCount = "101", dislikeCount = "10"))
        render { WatchScreen(WatchUiState("bunny", content = WatchContent.Ready(page.copy(video = video.value))), {}, {}) }
        compose.onNodeWithTag("watch-action-like").assertIsSelected()
        compose.onNodeWithTag("watch-action-dislike").assertIsNotSelected()
        compose.onNodeWithTag("watch-action-like-count", useUnmergedTree = true).assertTextEquals("101")
        compose.runOnIdle { video.value = video.value.copy(rating = VideoRating.Dislike, likeCount = "100", dislikeCount = "11") }
        compose.onNodeWithTag("watch-action-like").assertIsNotSelected()
        compose.onNodeWithTag("watch-action-dislike").assertIsSelected()
        compose.onNodeWithTag("watch-action-like-count", useUnmergedTree = true).assertTextEquals("100")
        compose.onNodeWithTag("watch-action-dislike-count", useUnmergedTree = true).assertTextEquals("11")
    }
}
