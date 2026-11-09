package com.hhst.youtubelite.post

import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.post.presentation.PostCardActionsViewModel
import com.hhst.youtubelite.watch.domain.VideoRating
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostCardActionsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val identity = PostIdentity("studio", "post")
    private val original = FixturePostRepository.sampleDetail(identity)!!

    @Test fun eachCardTapPassesItsDisplayedRatingAndPollSelection() = runTest {
        val ratings = mutableListOf<Pair<VideoRating, VideoRating>>()
        val selections = mutableListOf<Boolean>()
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail {
                ratings += rating to previous
                return original.copy(rating = rating)
            }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail {
                selections += selected
                return original
            }
        }
        val vm = PostCardActionsViewModel(repository)
        vm.onAction(identity, PostDetailAction.Like)
        advanceUntilIdle()
        vm.onAction(identity, PostDetailAction.Like)
        advanceUntilIdle()
        vm.onAction(identity, PostDetailAction.Vote(0, false))
        advanceUntilIdle()
        assertEquals(listOf(VideoRating.Like to VideoRating.None, VideoRating.None to VideoRating.Like), ratings)
        assertEquals(listOf(false), selections)
    }

    @Test fun cardWriteShowsItsOwnProgressRejectsDuplicatesAndUpdatesOnlyThatCard() = runTest {
        val gate = CompletableDeferred<PostDetail>()
        var writes = 0
        var reads = 0
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun post(identity: PostIdentity): PostDetail { reads++; return original }
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail { writes++; return gate.await() }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostCardActionsViewModel(repository)
        vm.onAction(identity, PostDetailAction.Like)
        vm.onAction(identity, PostDetailAction.Dislike)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(PostDetailAction.Like, vm.uiState.value[identity.postId]?.pendingAction)
        assertNull(vm.uiState.value[identity.postId]?.post)
        gate.complete(original.copy(rating = VideoRating.Like, likes = "1.3K"))
        advanceUntilIdle()
        assertEquals(VideoRating.Like, vm.uiState.value[identity.postId]?.post?.rating)
        assertNull(vm.uiState.value[identity.postId]?.pendingAction)
        assertEquals(setOf(identity.postId), vm.uiState.value.keys)
        assertEquals(0, reads)
    }

    @Test fun authorizationDoesNotReplayAndNextTapRefreshesCommandsBeforeWriting() = runTest {
        var authorized = false
        var reads = 0
        var writes = 0
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun post(identity: PostIdentity): PostDetail { reads++; return original }
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail {
                if (!authorized) throw PostAuthorizationRequiredException()
                assertEquals(1, reads)
                writes++
                return original.copy(rating = VideoRating.Like)
            }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostCardActionsViewModel(repository)
        vm.onAction(identity, PostDetailAction.Like)
        advanceUntilIdle()
        assertEquals(Unit, vm.authorizationRequests.first())
        authorized = true
        advanceUntilIdle()
        assertEquals(0, writes)
        vm.onAction(identity, PostDetailAction.Like)
        advanceUntilIdle()
        assertEquals(1, writes)
    }

    @Test fun failedWriteKeepsConfirmedStateAndClearsProgress() = runTest {
        val gate = CompletableDeferred<PostDetail>()
        var fail = false
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail {
                if (fail) throw java.io.IOException("offline")
                return gate.await()
            }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostCardActionsViewModel(repository)
        vm.onAction(identity, PostDetailAction.Like)
        gate.complete(original.copy(rating = VideoRating.Like))
        advanceUntilIdle()
        fail = true
        vm.onAction(identity, PostDetailAction.Dislike)
        advanceUntilIdle()
        assertEquals(VideoRating.Like, vm.uiState.value[identity.postId]?.post?.rating)
        assertNull(vm.uiState.value[identity.postId]?.pendingAction)
        vm.reset()
        assertTrue(vm.uiState.value.isEmpty())
    }

    @Test fun sessionChangeRejectsEvenANonCancellableLateResponse() = runTest {
        val gate = CompletableDeferred<PostDetail>()
        val repository = object : PostRepository by FixturePostRepository(), PostActions {
            override suspend fun rate(identity: PostIdentity, rating: VideoRating, previous: VideoRating): PostDetail =
                withContext(NonCancellable) { gate.await() }
            override suspend fun vote(identity: PostIdentity, option: Int, selected: Boolean): PostDetail = error("Unexpected vote")
        }
        val vm = PostCardActionsViewModel(repository)
        vm.onAction(identity, PostDetailAction.Like)
        runCurrent()
        vm.reset()
        gate.complete(original.copy(rating = VideoRating.Like))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmpty())
    }
}
