package com.hhst.youtubelite.post

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.data.PostScenario
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.watch.domain.VideoRating
import com.hhst.youtubelite.post.presentation.PostDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailViewModelTest {
    @Test fun ratingRequestsCaptureTheDisplayedPreviousAndDesiredState() = runTest {
        val identity = PostIdentity("studio", "post")
        val original = FixturePostRepository.sampleDetail(identity)!!.copy(rating = VideoRating.Like)
        val requests = mutableListOf<Pair<VideoRating, VideoRating>>()
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun post(identity: PostIdentity) = original
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail {
                requests += rating to previous
                return original.copy(rating = rating)
            }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostDetailViewModel(repository, SavedStateHandle(), identity)
        advanceUntilIdle()
        vm.onAction(PostDetailAction.Like)
        advanceUntilIdle()
        vm.onAction(PostDetailAction.Dislike)
        advanceUntilIdle()
        vm.onAction(PostDetailAction.Dislike)
        advanceUntilIdle()
        assertEquals(listOf(VideoRating.None to VideoRating.Like, VideoRating.Dislike to VideoRating.None,
            VideoRating.None to VideoRating.Dislike), requests)
    }

    @Test fun authorizationRefreshKeepsContentAndWaitsBeforeWriting() = runTest {
        val identity = PostIdentity("studio", "post")
        val original = FixturePostRepository.sampleDetail(identity)!!
        val opening = kotlinx.coroutines.CompletableDeferred<PostDetail>()
        var reads = 0
        var writes = 0
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun post(identity: PostIdentity): PostDetail = if (++reads == 1) original else opening.await()
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail {
                writes++
                return original.copy(rating = com.hhst.youtubelite.watch.domain.VideoRating.Like)
            }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostDetailViewModel(repository, SavedStateHandle(), identity)
        advanceUntilIdle()
        vm.onAction(PostDetailAction.SelectImage(2))
        vm.refreshAuthorization(true)
        assertEquals(original, vm.uiState.value.post)
        assertTrue(vm.uiState.value.refreshing)
        vm.onAction(PostDetailAction.Like)
        runCurrent()
        assertEquals(0, writes)
        assertEquals(PostDetailAction.Like, vm.uiState.value.pendingAction)
        opening.complete(original)
        advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals(2, reads)
        assertEquals(2, vm.uiState.value.selectedImage)
        assertFalse(vm.uiState.value.refreshing)
        assertNull(vm.uiState.value.pendingAction)
    }

    @Test fun failedRefreshKeepsContentAndBlocksWritesUntilAnExplicitRetrySucceeds() = runTest {
        val identity = PostIdentity("studio", "post")
        val original = FixturePostRepository.sampleDetail(identity)!!
        var fail = false
        var writes = 0
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun post(identity: PostIdentity): PostDetail {
                if (fail) throw java.io.IOException("offline")
                return original
            }
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail { writes++; return original }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostDetailViewModel(repository, SavedStateHandle(), identity)
        advanceUntilIdle()
        fail = true
        vm.refreshAuthorization(true)
        advanceUntilIdle()
        assertEquals(original, vm.uiState.value.post)
        assertFalse(vm.uiState.value.refreshing)
        vm.onAction(PostDetailAction.Like)
        advanceUntilIdle()
        assertEquals(0, writes)
        assertNull(vm.uiState.value.pendingAction)
        fail = false
        vm.onAction(PostDetailAction.Like)
        advanceUntilIdle()
        assertEquals(1, writes)
    }

    @Test fun productionRatingWaitsForConfirmationAndRejectsDuplicateTaps() = runTest {
        val reply = kotlinx.coroutines.CompletableDeferred<PostDetail>()
        var calls = 0
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail { calls++; return reply.await() }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected poll vote")
        }
        val vm = PostDetailViewModel(repository, SavedStateHandle(), PostIdentity("studio", "post"))
        advanceUntilIdle()
        val original = vm.uiState.value.post!!
        vm.onAction(PostDetailAction.Like)
        vm.onAction(PostDetailAction.Like)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.actionPending)
        assertEquals(PostDetailAction.Like, vm.uiState.value.pendingAction)
        assertEquals(original, vm.uiState.value.post)
        reply.complete(original.copy(rating = com.hhst.youtubelite.watch.domain.VideoRating.Like))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
        assertNull(vm.uiState.value.pendingAction)
        assertEquals(com.hhst.youtubelite.watch.domain.VideoRating.Like, vm.uiState.value.post?.rating)
    }

    @Test fun missingPostAuthorizationRequestsSignInWithoutChangingTheRating() = runTest {
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail = throw PostAuthorizationRequiredException()
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = throw PostAuthorizationRequiredException()
        }
        val vm = PostDetailViewModel(repository, SavedStateHandle(), PostIdentity("studio", "post"))
        advanceUntilIdle()
        val original = vm.uiState.value.post
        vm.onAction(PostDetailAction.Like)
        advanceUntilIdle()
        assertEquals(Unit, vm.authorizationRequests.first())
        assertEquals(original, vm.uiState.value.post)
        assertFalse(vm.uiState.value.actionPending)
    }

    @Test fun navigationSessionSurvivesContentLoadingAndSignOut() = runTest {
        val model = PostDetailViewModel(FixturePostRepository(), SavedStateHandle(), PostIdentity("studio", "post"))
        model.updateSignedIn(true)
        advanceUntilIdle()
        assertTrue(model.uiState.value.signedIn)
        model.onAction(PostDetailAction.SelectDestination(PostDestination.Subscriptions))
        assertEquals(PostDetailNavigationRequest.Destination(PostDestination.Subscriptions), model.navigationRequests.first())
        model.updateSignedIn(false)
        assertFalse(model.uiState.value.signedIn)
    }

    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val identity = PostIdentity("studio", "post")

    private fun viewModel(
        scenario: PostScenario = PostScenario.Content,
        saved: SavedStateHandle = SavedStateHandle(),
    ) = PostDetailViewModel(FixturePostRepository(scenario), saved, identity, scenario)

    @Test fun loadsTheRequestedPostIdentity() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(identity, state.identity)
        assertTrue(state.content is PostDetailContent.Ready)
        assertEquals(identity, (state.content as PostDetailContent.Ready).post.identity)
        assertEquals(3, state.post?.images?.size)
    }

    @Test fun bottomNavigationEmitsTheRequestedDestination() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAction(PostDetailAction.SelectDestination(PostDestination.You))
        assertEquals(PostDetailNavigationRequest.Destination(PostDestination.You), vm.navigationRequests.first())
    }

    @Test fun localImageSelectionIsSavedWithThePost() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        advanceUntilIdle()
        vm.onAction(PostDetailAction.SelectImage(2))
        assertEquals(2, vm.uiState.value.selectedImage)
        assertEquals(2, saved.get<Int>("selectedImage"))
        val restored = viewModel(saved = saved)
        assertEquals(2, restored.uiState.value.selectedImage)
    }

    @Test fun feedbackCommentsAndMenuActionsCarryThePostIdentity() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAction(PostDetailAction.Like)
        assertEquals(PostDetailNavigationRequest.Feedback(identity, true), vm.navigationRequests.first())
        vm.onAction(PostDetailAction.Comments)
        assertEquals(PostDetailNavigationRequest.Comments(identity), vm.navigationRequests.first())
        vm.onAction(PostDetailAction.OpenMenu)
        assertEquals(PostDetailOverlay.Menu, vm.uiState.value.overlay)
        vm.onAction(PostDetailAction.MenuSelected(PostMenuAction.Report))
        assertEquals(
            PostDetailNavigationRequest.MenuAction(identity, PostMenuAction.Report),
            vm.navigationRequests.first(),
        )
        assertEquals(PostDetailOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun failedPostRetriesWithoutChangingIdentity() = runTest {
        val vm = viewModel(PostScenario.Error)
        advanceUntilIdle()
        assertEquals(PostDetailContent.Failed, vm.uiState.value.content)
        vm.onAction(PostDetailAction.Retry)
        assertEquals(PostDetailContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(PostDetailContent.Failed, vm.uiState.value.content)
        assertEquals(identity, vm.uiState.value.identity)
    }

    @Test fun aPostLinkResolvesItsAuthorBeforeNavigationAndKeepsThatIdentityOnRetry() = runTest {
        val detail = FixturePostRepository.sampleDetail(identity)!!
        val requested = mutableListOf<PostIdentity>()
        val repository = object : PostRepository by FixturePostRepository() {
            override suspend fun post(identity: PostIdentity): PostDetail {
                requested += identity
                return detail
            }
        }
        val vm = PostDetailViewModel(repository, SavedStateHandle(), identity.copy(channelId = ""))
        advanceUntilIdle()
        assertEquals(identity, vm.uiState.value.identity)
        vm.onAction(PostDetailAction.OpenChannel)
        assertEquals(PostDetailNavigationRequest.Channel(identity.channelId), vm.navigationRequests.first())
        vm.onAction(PostDetailAction.Comments)
        assertEquals(PostDetailNavigationRequest.Comments(identity), vm.navigationRequests.first())
        vm.onAction(PostDetailAction.Retry)
        advanceUntilIdle()
        assertEquals(listOf(identity.copy(channelId = ""), identity), requested)
    }

    @Test fun missingPostShowsFailureAndKeepsIdentity() = runTest {
        val missing = PostIdentity("studio", "missing")
        val vm = PostDetailViewModel(FixturePostRepository(), SavedStateHandle(), missing, PostScenario.Content)
        advanceUntilIdle()
        assertEquals(missing, vm.uiState.value.identity)
        assertEquals(PostDetailContent.Failed, vm.uiState.value.content)
    }
}
