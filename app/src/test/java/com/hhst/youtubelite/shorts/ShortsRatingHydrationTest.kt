package com.hhst.youtubelite.shorts

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.ShortDetail
import com.hhst.youtubelite.shorts.domain.ShortItem
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.domain.ShortsPage
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import com.hhst.youtubelite.watch.domain.VideoActions
import com.hhst.youtubelite.watch.domain.VideoRating
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortsRatingHydrationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val item = ShortItem("abcdefghijk", "Title", "Author", "UCabcdefghijk", "art", "", "", "")
    private fun repository(detail: suspend () -> ShortDetail) = object : ShortsRepository {
        override suspend fun shorts() = ShortsPage(listOf(item))
        override suspend fun hydrate(itemId: String) = detail()
    }
    private fun actions(rate: suspend (VideoRating, VideoRating) -> Unit) = object : VideoActions {
        override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) = rate(rating, previous)
        override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
    }

    @Test fun aServerLikeSeedsTheButtonAndTheFirstTapRemovesIt() = runTest {
        val writes = mutableListOf<Pair<VideoRating, VideoRating>>()
        val vm = ShortsViewModel(repository { ShortDetail(item, rating = VideoRating.Like) },
            SavedStateHandle(), ShortsScenario.Content, item.id, actions { next, previous -> writes += next to previous })
        runCurrent()
        vm.onAction(ShortsAction.SelectItem(item.id))
        vm.onPlaybackReady(item.id)
        runCurrent()
        assertEquals(VideoRating.Like, vm.uiState.value.ratings[item.id])
        vm.onAction(ShortsAction.Like(item.id))
        runCurrent()
        assertEquals(listOf(VideoRating.None to VideoRating.Like), writes)
        assertEquals(VideoRating.None, vm.uiState.value.ratings[item.id])
    }

    @Test fun lateHydrationCannotReplaceANewerConfirmedRating() = runTest {
        val detail = CompletableDeferred<ShortDetail>()
        val vm = ShortsViewModel(repository { detail.await() }, SavedStateHandle(), ShortsScenario.Content,
            item.id, actions { _, _ -> })
        runCurrent()
        vm.onAction(ShortsAction.SelectItem(item.id))
        vm.onPlaybackReady(item.id)
        runCurrent()
        vm.onAction(ShortsAction.Dislike(item.id))
        runCurrent()
        assertEquals(VideoRating.Dislike, vm.uiState.value.ratings[item.id])
        detail.complete(ShortDetail(item, rating = VideoRating.Like))
        advanceUntilIdle()
        assertEquals(VideoRating.Dislike, vm.uiState.value.ratings[item.id])
    }

    @Test fun hydrationCannotSelectTheOldRatingWhileAWriteIsPending() = runTest {
        val detail = CompletableDeferred<ShortDetail>()
        val write = CompletableDeferred<Unit>()
        val vm = ShortsViewModel(repository { detail.await() }, SavedStateHandle(), ShortsScenario.Content,
            item.id, actions { _, _ -> write.await() })
        runCurrent()
        vm.onAction(ShortsAction.SelectItem(item.id))
        vm.onPlaybackReady(item.id)
        runCurrent()
        vm.onAction(ShortsAction.Dislike(item.id))
        runCurrent()
        detail.complete(ShortDetail(item, rating = VideoRating.Like))
        runCurrent()
        assertTrue(item.id in vm.uiState.value.ratingsPending)
        assertNull(vm.uiState.value.ratings[item.id])
        write.complete(Unit)
        advanceUntilIdle()
        assertEquals(VideoRating.Dislike, vm.uiState.value.ratings[item.id])
    }
}
