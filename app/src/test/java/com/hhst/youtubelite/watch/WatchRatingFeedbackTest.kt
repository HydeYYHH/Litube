package com.hhst.youtubelite.watch

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaVideoActions
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchViewModel
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRatingFeedbackTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun model(
        initial: VideoRating = VideoRating.None,
        votes: VoteCountRepository = VoteCountRepository { VoteCounts(100, 10) },
        actions: VideoActions = actions(),
    ): WatchViewModel {
        val repository = object : WatchRepository {
            override suspend fun watch(videoId: String): WatchPage {
                val page = FixtureWatchRepository.samplePage(videoId)
                return page.copy(video = page.video.copy(rating = initial))
            }
        }
        return WatchViewModel(repository, SavedStateHandle(), "abcdefghijk", voteRepository = votes, videoActions = actions)
            .also { it.refreshVoteCounts(true, true) }
    }

    private fun actions(write: suspend (VideoRating, VideoRating) -> Unit = { _, _ -> }) = object : VideoActions {
        override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) = write(rating, previous)
        override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
    }

    private fun WatchViewModel.video() = (uiState.value.content as WatchContent.Ready).page.video

    @Test fun allRatingTransitionsUpdateBothCountsOnlyAfterConfirmation() = runTest {
        for (initial in VideoRating.entries) for (selection in listOf(VideoRating.Like, VideoRating.Dislike)) {
            val gate = CompletableDeferred<Unit>()
            val vm = model(initial, actions = actions { _, _ -> gate.await() })
            advanceUntilIdle()
            vm.onAction(if (selection == VideoRating.Like) WatchAction.Like else WatchAction.Dislike)
            runCurrent()
            assertEquals(initial, vm.video().rating)
            assertEquals("100", vm.video().likeCount)
            assertEquals("10", vm.video().dislikeCount)
            assertTrue(vm.uiState.value.actionPending)
            gate.complete(Unit)
            advanceUntilIdle()
            val next = if (initial == selection) VideoRating.None else selection
            assertEquals(next, vm.video().rating)
            assertEquals((100 + (if (next == VideoRating.Like) 1 else 0) - (if (initial == VideoRating.Like) 1 else 0)).toString(), vm.video().likeCount)
            assertEquals((10 + (if (next == VideoRating.Dislike) 1 else 0) - (if (initial == VideoRating.Dislike) 1 else 0)).toString(), vm.video().dislikeCount)
            assertFalse(vm.uiState.value.actionPending)
        }
    }

    @Test fun lateAggregateResponsePreservesTheConfirmedVoteAndRepeatedTapsDoNotAccumulateCounts() = runTest {
        val counts = CompletableDeferred<VoteCounts>()
        val vm = model(votes = VoteCountRepository { counts.await() })
        runCurrent()
        vm.onAction(WatchAction.Like)
        runCurrent()
        assertEquals(VideoRating.Like, vm.video().rating)
        counts.complete(VoteCounts(100, 10))
        advanceUntilIdle()
        assertEquals("101", vm.video().likeCount)
        vm.onAction(WatchAction.Like)
        advanceUntilIdle()
        assertEquals("100", vm.video().likeCount)
        vm.onAction(WatchAction.Dislike)
        advanceUntilIdle()
        assertEquals("100", vm.video().likeCount)
        assertEquals("11", vm.video().dislikeCount)
        vm.onAction(WatchAction.Dislike)
        advanceUntilIdle()
        assertEquals("10", vm.video().dislikeCount)
    }

    @Test fun displayPreferencesKeepTheSnapshotAndItsConfirmedDeltaWithoutAnotherRead() = runTest {
        var reads = 0
        val vm = model(votes = VoteCountRepository { reads++; VoteCounts(100, 10) })
        advanceUntilIdle()
        vm.onAction(WatchAction.Like)
        advanceUntilIdle()
        vm.refreshVoteCounts(false, false)
        assertNull(vm.video().likeCount)
        assertNull(vm.video().dislikeCount)
        vm.refreshVoteCounts(true, true)
        advanceUntilIdle()
        assertEquals("101", vm.video().likeCount)
        assertEquals("10", vm.video().dislikeCount)
        assertEquals(1, reads)
    }

    @Test fun switchingVideosDropsThePreviousVideosAdjustment() = runTest {
        val vm = model()
        advanceUntilIdle()
        vm.onAction(WatchAction.Like)
        advanceUntilIdle()
        assertEquals("101", vm.video().likeCount)
        vm.selectVideo("lmnopqrstuv")
        advanceUntilIdle()
        assertEquals(VideoRating.None, vm.video().rating)
        assertEquals("100", vm.video().likeCount)
    }

    @Test fun missingCountsStayUnknownAndZeroCountsNeverBecomeNegative() = runTest {
        val vm = model(VideoRating.Like, votes = VoteCountRepository { VoteCounts(0, null) })
        advanceUntilIdle()
        vm.onAction(WatchAction.Dislike)
        advanceUntilIdle()
        assertEquals("0", vm.video().likeCount)
        assertNull(vm.video().dislikeCount)
    }

    private fun client(server: MockWebServer) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
    }.build()) { RequestContext(accountId = "account", accessToken = "access", generation = 7) }

    @Test fun anActualWriteAndMatchingServerReadbackUpdateTheOrdinaryVideo() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"responseContext":{}}"""))
            server.enqueue(MockResponse().setBody("""{"likeStatusEntity":{"likeStatus":"LIKE"}}"""))
            val vm = model(actions = MediaVideoActions(client(server)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.onAction(WatchAction.Like)
            awaitAction(vm)
            assertEquals(VideoRating.Like, vm.video().rating)
            assertEquals("101", vm.video().likeCount)
            assertTrue(server.takeRequest().path.orEmpty().endsWith("/like/like"))
            assertTrue(server.takeRequest().path.orEmpty().endsWith("/next"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun mismatchedServerReadbackLeavesBothRatingAndCountsUnchangedAndReportsFailure() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"responseContext":{}}"""))
                repeat(2) { server.enqueue(MockResponse().setBody("""{"likeStatusEntity":{"likeStatus":"INDIFFERENT"}}""")) }
            }
            val vm = model(actions = MediaVideoActions(client(server)))
            dispatcher.scheduler.advanceUntilIdle()
            vm.onAction(WatchAction.Like)
            awaitAction(vm)
            assertEquals(VideoRating.None, vm.video().rating)
            assertEquals("100", vm.video().likeCount)
            assertEquals("10", vm.video().dislikeCount)
            assertTrue(vm.actionNotices.first() is AppNotice.Failure)
            assertEquals(6, server.requestCount)
        }
    }

    /** Real HTTP workers must finish before virtual confirmation deadlines advance. */
    private suspend fun awaitAction(vm: WatchViewModel) {
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) {
            while (vm.uiState.value.actionPending) {
                delay(10)
                dispatcher.scheduler.advanceTimeBy(10)
                dispatcher.scheduler.runCurrent()
            }
        }
    }
}
