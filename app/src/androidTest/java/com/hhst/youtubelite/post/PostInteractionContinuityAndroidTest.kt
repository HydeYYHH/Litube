package com.hhst.youtubelite.post

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelSection
import com.hhst.youtubelite.channel.presentation.ChannelPostsTab
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.home.presentation.FeedCard
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.post.presentation.*
import com.hhst.youtubelite.ui.design.AppTheme
import com.hhst.youtubelite.watch.domain.VideoRating
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PostInteractionContinuityAndroidTest {
    @get:Rule val compose = createComposeRule()
    private var model: ViewModel? = null
    private val identity = PostIdentity("studio", "post")
    private val original = FixturePostRepository.sampleDetail(identity)!!
    @After fun cleanup() { compose.runOnIdle { model?.viewModelScope?.cancel() } }

    private fun assertRedOutline(tag: String) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        var redPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.red > 0.7f && pixel.green < 0.35f && pixel.blue < 0.35f) redPixels++
        }
        assertTrue("Selected action should use the video's red outline", redPixels > 10)
    }

    private fun repository(gate: CompletableDeferred<PostDetail>, detail: PostDetail = original) =
        object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun post(identity: PostIdentity) = detail
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating) = gate.await()
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean) = gate.await()
        }

    @Test fun detailRatingAndAuthorizationRefreshKeepTheViewportAndShowLocalProgress() {
        val write = CompletableDeferred<PostDetail>()
        val refresh = CompletableDeferred<PostDetail>()
        var reads = 0
        val repository = object : PostRepository by repository(write), PostActions {
            override suspend fun post(identity: PostIdentity): PostDetail = if (++reads == 1) original else refresh.await()
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating) = write.await()
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean) = error("Unexpected vote")
        }
        lateinit var vm: PostDetailViewModel
        compose.runOnUiThread { vm = PostDetailViewModel(repository, SavedStateHandle(), identity); model = vm }
        compose.setContent {
            val state by vm.uiState.collectAsState()
            PostDetailScreen(state, vm::onAction, {})
        }
        compose.onNodeWithTag("post-detail-like").performScrollTo()
        val before = compose.onNodeWithTag("post-detail-feedback").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("post-detail-like").performClick()
        compose.onNode(hasTestTag("write-progress") and hasAnyAncestor(hasTestTag("post-detail-like")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("post-detail-loading").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("post-detail-feedback").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { write.complete(original.copy(rating = VideoRating.Like)) }
        compose.onNodeWithTag("post-detail-like").assertIsSelected()
        assertRedOutline("post-detail-like")
        compose.runOnIdle { vm.refreshAuthorization(true) }
        compose.onNodeWithTag("post-detail-refreshing").assertIsDisplayed()
        compose.onNodeWithTag("post-detail-loading").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("post-detail-feedback").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { refresh.complete(original.copy(rating = VideoRating.Like)) }
        compose.onNodeWithTag("post-detail-refreshing").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("post-detail-feedback").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun homeCardRatesInPlaceWithoutEmittingNavigation() {
        val gate = CompletableDeferred<PostDetail>()
        lateinit var vm: PostCardActionsViewModel
        compose.runOnUiThread { vm = PostCardActionsViewModel(repository(gate)); model = vm }
        var navigations = 0
        compose.setContent { AppTheme {
            val interactions by vm.uiState.collectAsState()
            FeedCard(FeedItem.Post("post", "Author", "Today", "Post body", "", emptyList(), "10", "2", "studio", "post"),
                compact = true, windowWidth = 390f, live = true, onAction = { navigations++ },
                postInteractions = PostCardInteractions(interactions, vm::onAction))
        } }
        val before = compose.onNodeWithTag("post-like-post").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("post-like-post").performClick()
        compose.onNode(hasTestTag("write-progress") and hasAnyAncestor(hasTestTag("post-like-post")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("post-like-post").assertIsNotEnabled()
        assertEquals(before, compose.onNodeWithTag("post-like-post").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { gate.complete(original.copy(rating = VideoRating.Like)) }
        compose.onNodeWithTag("post-like-post").assertIsSelected()
        assertRedOutline("post-like-post")
        compose.runOnIdle { assertEquals(0, navigations) }
    }

    @Test fun channelPollVotesInPlaceAndDoesNotMoveTheCard() {
        val gate = CompletableDeferred<PostDetail>()
        val poll = PostPollData(listOf(PostPollOption("First"), PostPollOption("Second")), "10 votes")
        val detail = original.copy(images = emptyList(), poll = poll)
        val summary = FixturePostRepository.samplePosts("studio").first().copy(images = emptyList(), poll = poll)
        lateinit var vm: PostCardActionsViewModel
        compose.runOnUiThread { vm = PostCardActionsViewModel(repository(gate, detail)); model = vm }
        var navigations = 0
        compose.setContent { AppTheme {
            val interactions by vm.uiState.collectAsState()
            ChannelPostsTab(FixtureChannelRepository.samplePage("studio"), ChannelSection.Ready(listOf(summary)), 390f,
                rememberLazyListState(), emptySet(), { navigations++ }, production = true,
                postInteractions = PostCardInteractions(interactions, vm::onAction))
        } }
        compose.onNodeWithTag("post-poll-option-0").performScrollTo()
        val before = compose.onNodeWithTag("post-poll-option-0").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("post-poll-option-0").performClick()
        compose.onNode(hasTestTag("write-progress") and hasAnyAncestor(hasTestTag("post-poll-option-0")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("post-poll-option-0").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { gate.complete(detail.copy(poll = poll.copy(voted = true,
            options = listOf(PostPollOption("First", percentage = "60%", selected = true), PostPollOption("Second", percentage = "40%"))))) }
        compose.onNodeWithTag("post-poll-option-0").assertIsSelected()
        assertEquals(before, compose.onNodeWithTag("post-poll-option-0").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(0, navigations) }
    }

    @Test fun homeCardOpensTheClickedImageDirectly() {
        val images = listOf("dream", "sintel", "bunny")
        var opened: Pair<List<String>, Int>? = null
        var navigations = 0
        compose.setContent { AppTheme {
            FeedCard(FeedItem.Post("post", "Author", "Today", "Post body", "", images, "10", "2", "studio", "post"),
                compact = true, windowWidth = 390f, live = true, onAction = { navigations++ },
                postInteractions = PostCardInteractions(emptyMap(), { _, _ -> }, { urls, index -> opened = urls to index }))
        } }
        compose.onNodeWithTag("post-images-post").performScrollToIndex(2)
        compose.onNodeWithTag("post-image-post-2").performClick()
        compose.runOnIdle { assertEquals(images to 2, opened); assertEquals(0, navigations) }
    }

    @Test fun channelCardOpensTheSelectedImageAndUsesRedDislike() {
        val summary = FixturePostRepository.samplePosts("studio").first()
        var opened: Pair<List<String>, Int>? = null
        var navigations = 0
        compose.setContent { AppTheme {
            ChannelPostsTab(FixtureChannelRepository.samplePage("studio"), ChannelSection.Ready(listOf(summary)), 390f,
                rememberLazyListState(), emptySet(), { navigations++ }, production = true,
                postInteractions = PostCardInteractions(mapOf("post" to PostCardInteraction(original.copy(rating = VideoRating.Dislike))),
                    { _, _ -> }, { urls, index -> opened = urls to index }))
        } }
        compose.onNodeWithTag("channel-post-dislike-post").performScrollTo()
        assertRedOutline("channel-post-dislike-post")
        compose.onNodeWithTag("post-image-0").performScrollTo()
        compose.onNodeWithTag("post-images").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("post-image-1").performClick()
        compose.runOnIdle { assertEquals(summary.images to 1, opened); assertEquals(0, navigations) }
    }
}
