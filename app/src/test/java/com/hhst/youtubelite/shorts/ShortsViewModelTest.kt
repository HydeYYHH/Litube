package com.hhst.youtubelite.shorts

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.domain.*
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun loadsTheSharedShortsFixture() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        val content = vm.uiState.value.content as ShortsContent.Ready
        assertEquals(8, content.page.items.size)
        assertEquals("short-1", content.page.items.first().id)
    }

    @Test fun playbackEndAdvancesOnlyTheCurrentItemAndWaitsForPanelsToClose() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, "short-1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.OpenComments("short-1"))
        vm.onPlaybackEnded("short-1")
        assertEquals("short-1", vm.uiState.value.currentItemId)
        vm.onAction(ShortsAction.CloseComments)
        vm.onPlaybackEnded("short-1")
        assertEquals("short-2", vm.uiState.value.currentItemId)
        vm.onPlaybackEnded("short-1")
        assertEquals("short-2", vm.uiState.value.currentItemId)
        vm.onAction(ShortsAction.SelectItem("short-8"))
        vm.onPlaybackEnded("short-8")
        assertEquals("short-8", vm.uiState.value.currentItemId)
    }

    @Test fun playbackEndWaitsForPaginationAndDoesNotOverrideASwipe() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<ShortsPage>()
        val samples = FixtureShortsRepository.sample()
        var requests = 0
        val vm = ShortsViewModel(object : ShortsRepository {
            override suspend fun shorts() = ShortsPage(samples.take(2), "next")
            override suspend fun shortsNext(cursor: String): ShortsPage { requests++; return gate.await() }
        }, SavedStateHandle(), ShortsScenario.Content, "short-2")
        advanceUntilIdle()
        vm.onPlaybackEnded("short-2")
        runCurrent()
        assertEquals("short-2", vm.uiState.value.currentItemId)
        assertTrue(vm.uiState.value.appendLoading)
        vm.onPlaybackEnded("short-2")
        gate.complete(ShortsPage(samples.drop(2)))
        advanceUntilIdle()
        vm.onPlaybackEnded("short-2")
        assertEquals("short-3", vm.uiState.value.currentItemId)
        assertEquals(1, requests)
        vm.onAction(ShortsAction.SelectItem("short-1"))
        vm.onPlaybackEnded("short-2")
        assertEquals("short-1", vm.uiState.value.currentItemId)
    }

    @Test fun likeAndDislikeStayTypedRateRequestsWithoutStateChange() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        val requests = mutableListOf<ShortsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(ShortsAction.Like("short-3"))
        vm.onAction(ShortsAction.Dislike("short-3"))
        advanceUntilIdle()
        assertEquals(listOf(ShortsNavigationRequest.Rate("short-3", true), ShortsNavigationRequest.Rate("short-3", false)), requests)
        val page = (vm.uiState.value.content as ShortsContent.Ready).page
        assertEquals("19K", page.items.first { it.id == "short-3" }.likes)
    }

    @Test fun commentsOverlayOpensAndClosesWithoutNavigation() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        vm.onAction(ShortsAction.OpenComments("short-2"))
        assertEquals(ShortsOverlay.Comments("short-2"), vm.uiState.value.overlay)
        vm.onAction(ShortsAction.CloseComments)
        assertEquals(ShortsOverlay.None, vm.uiState.value.overlay)
        val requests = mutableListOf<ShortsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun menuEmitsTheTypedActionAndCloses() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        val requests = mutableListOf<ShortsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(ShortsAction.OpenMenu("short-4"))
        vm.onAction(ShortsAction.MenuSelected(FeedMenuAction.NotInterested))
        advanceUntilIdle()
        assertEquals(listOf(ShortsNavigationRequest.MenuAction("short-4", FeedMenuAction.NotInterested)), requests)
        assertEquals(ShortsOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun saveToPlaylistNeedsASaveRepositoryAndRoutesThroughTheSharedSheet() = runTest {
        val withoutSaves = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        assertNull(withoutSaves.saveSheet)
        withoutSaves.onAction(ShortsAction.OpenSaveToPlaylist("short-5"))
        advanceUntilIdle()
        assertNull(withoutSaves.saveSheet?.state?.value)

        val saves = RecordingSaves()
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null, saves = saves)
        advanceUntilIdle()
        vm.onAction(ShortsAction.OpenSaveToPlaylist("short-5"))
        advanceUntilIdle()
        assertEquals("short-5", vm.saveSheet?.state?.value?.videoId)
        assertEquals(listOf("short-5"), saves.choicesFor)
    }

    /** Records the choices read; the sheet's own write paths are covered by the saves suite. */
    private class RecordingSaves : com.hhst.youtubelite.saves.domain.SaveRepository {
        val choicesFor = mutableListOf<String>()
        override suspend fun choices(videoId: String): List<com.hhst.youtubelite.saves.domain.SaveChoice> {
            choicesFor += videoId
            return listOf(com.hhst.youtubelite.saves.domain.SaveChoice("pl-1", "Playlist one", false))
        }
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {}
        override suspend fun saveWatchLater(videoId: String) {}
        override suspend fun create(title: String, description: String, videoId: String) =
            com.hhst.youtubelite.saves.domain.SaveChoice("pl-new", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) {}
    }

    @Test fun errorScenarioShowsFailureAndRetryRecovers() = runTest {
        var fail = true
        val vm = ShortsViewModel(object : ShortsRepository {
            override suspend fun shorts(): ShortsPage {
                if (fail) throw java.io.IOException("offline")
                return ShortsPage(FixtureShortsRepository.sample())
            }
        }, SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ShortsContent.Failed)
        fail = false
        vm.onAction(ShortsAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ShortsContent.Ready)
    }

    @Test fun startItemIdOpensThePagerOnThatShort() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, "short-5")
        advanceUntilIdle()
        assertEquals("short-5", vm.uiState.value.startItemId)
    }

    @Test fun currentItemAndCommentTargetSurviveRecreation() = runTest {
        val handle = SavedStateHandle()
        val vm = ShortsViewModel(FixtureShortsRepository(), handle, ShortsScenario.Content, "short-2")
        vm.onAction(ShortsAction.SelectItem("short-5"))
        vm.onAction(ShortsAction.OpenComments("short-5"))
        val restored = ShortsViewModel(FixtureShortsRepository(), handle, ShortsScenario.Content, "short-2")
        advanceUntilIdle()
        assertEquals("short-5", restored.uiState.value.currentItemId)
        assertEquals(ShortsOverlay.Comments("short-5"), restored.uiState.value.overlay)
    }

    @Test fun emptyPageHasAnExplicitStateAndChannelItemsKeepTheirIds() = runTest {
        val vm = ShortsViewModel(FixtureShortsRepository(ShortsScenario.Empty), SavedStateHandle(), ShortsScenario.Empty, null)
        advanceUntilIdle()
        assertEquals(ShortsContent.Empty, vm.uiState.value.content)
        val items = FixtureShortsRepository(channelId = "studio").shorts().items
        assertEquals(12, items.size)
        assertEquals("shorts-11", items[10].id)
        assertTrue(items.all { it.channelId == "studio" })
    }

    @Test fun appendFailureRequiresExplicitRetryAndRepeatedCursorsStop() = runTest {
        var calls = 0
        val samples = FixtureShortsRepository.sample()
        val vm = ShortsViewModel(object : ShortsRepository {
            override suspend fun shorts() = ShortsPage(listOf(samples[0]), "next")
            override suspend fun shortsNext(cursor: String): ShortsPage {
                if (++calls == 1) error("offline")
                return ShortsPage(listOf(samples[0], samples[1], samples[1]), "next")
            }
        }, SavedStateHandle(), ShortsScenario.Content, null)
        advanceUntilIdle()
        vm.onAction(ShortsAction.LoadMore)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.appendFailed)
        assertEquals(1, (vm.uiState.value.content as ShortsContent.Ready).page.items.size)
        vm.onAction(ShortsAction.LoadMore)
        advanceUntilIdle()
        assertEquals(1, calls)
        vm.onAction(ShortsAction.RetryMore)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.appendFailed)
        val page = (vm.uiState.value.content as ShortsContent.Ready).page
        assertEquals(2, page.items.size)
        assertNull(page.nextKey)
        vm.onAction(ShortsAction.LoadMore)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun serverSubscriptionStateSeedsTheMapThroughHydration() = runTest {
        val vm = ShortsViewModel(object : ShortsRepository {
            override suspend fun shorts() = ShortsPage(listOf(
                ShortItem("short-1", "T", "Author", "ch-1", "", "", "", ""),
                ShortItem("short-2", "T", "Author", "ch-2", "", "", "", "")))
            override suspend fun hydrate(itemId: String): ShortDetail {
                val channel = if (itemId == "short-1") "ch-1" else "ch-2"
                return ShortDetail(ShortItem(itemId, "T", "Author", channel, "", "", "", ""),
                    subscribed = itemId == "short-1")
            }
        }, SavedStateHandle(), ShortsScenario.Content, null, videoActions = NoopActions())
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("short-1"))
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("short-2"))
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.subscriptions["ch-1"])
        assertEquals(false, vm.uiState.value.subscriptions["ch-2"])
    }

    @Test fun subscribeShowsPendingUntilTheServerConfirms() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = object : com.hhst.youtubelite.watch.domain.VideoActions {
            val calls = mutableListOf<Pair<String, Boolean>>()
            override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating, previous: com.hhst.youtubelite.watch.domain.VideoRating) {}
            override suspend fun subscribe(channelId: String, subscribed: Boolean) {
                calls += channelId to subscribed
                gate.await()
            }
        }
        val vm = ShortsViewModel(object : ShortsRepository {
            override suspend fun shorts() = ShortsPage(listOf(ShortItem("short-1", "T", "Author", "ch-1", "", "", "", "")))
        }, SavedStateHandle(), ShortsScenario.Content, null, videoActions = actions)
        advanceUntilIdle()
        vm.onAction(ShortsAction.Subscribe("ch-1"))
        runCurrent()
        assertTrue("channel is pending while the write is in flight", "ch-1" in vm.uiState.value.subscriptionsPending)
        vm.onAction(ShortsAction.Subscribe("ch-1"))
        runCurrent()
        assertEquals(1, actions.calls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf("ch-1" to true), vm.uiState.value.subscriptions)
        assertTrue(vm.uiState.value.subscriptionsPending.isEmpty())
    }

    @Test fun pendingRatingsAreVisiblePerVideoAndDoNotBlockSubscriptions() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ratings = mutableListOf<String>()
        var subscriptions = 0
        val actions = object : com.hhst.youtubelite.watch.domain.VideoActions {
            override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating,
                previous: com.hhst.youtubelite.watch.domain.VideoRating) { ratings += videoId; gate.await() }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) { subscriptions++ }
        }
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null, videoActions = actions)
        advanceUntilIdle()
        vm.onAction(ShortsAction.Like("short-1"))
        assertEquals(setOf("short-1"), vm.uiState.value.ratingsPending)
        vm.onAction(ShortsAction.Like("short-1"))
        vm.onAction(ShortsAction.Like("short-2"))
        vm.onAction(ShortsAction.Subscribe("channel"))
        runCurrent()
        assertEquals(listOf("short-1", "short-2"), ratings)
        assertEquals(1, subscriptions)
        assertTrue(vm.uiState.value.ratings.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ratingsPending.isEmpty())
        assertEquals(com.hhst.youtubelite.watch.domain.VideoRating.Like, vm.uiState.value.ratings["short-1"])
        assertEquals(com.hhst.youtubelite.watch.domain.VideoRating.Like, vm.uiState.value.ratings["short-2"])
    }

    @Test fun accountSwitchClearsPendingRatingsWithoutPublishingOldResults() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = object : com.hhst.youtubelite.watch.domain.VideoActions {
            override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating,
                previous: com.hhst.youtubelite.watch.domain.VideoRating) { gate.await() }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) {}
        }
        val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null, videoActions = actions)
        advanceUntilIdle()
        vm.onAction(ShortsAction.Dislike("short-1"))
        runCurrent()
        vm.updateSession(com.hhst.youtubelite.data.media.MediaSession.snapshot().generation + 1)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ratingsPending.isEmpty())
        assertTrue(vm.uiState.value.ratings.isEmpty())
    }

    @Test fun accountSwitchDiscardsLateRatingFailuresFromThePreviousSession() = runTest {
        for (failure in listOf(
            com.hhst.youtubelite.watch.domain.SignInRequiredException(),
            com.hhst.youtubelite.watch.domain.UnconfirmedActionException(),
            java.io.IOException("late transport failure"),
        )) {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val notices = mutableListOf<com.hhst.youtubelite.core.AppNotice>()
            val actions = object : com.hhst.youtubelite.watch.domain.VideoActions {
                override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating,
                    previous: com.hhst.youtubelite.watch.domain.VideoRating) {
                    // A transport's cleanup/readback can fail after cancellation was requested.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        gate.await()
                        throw failure
                    }
                }
                override suspend fun subscribe(channelId: String, subscribed: Boolean) {}
            }
            val vm = ShortsViewModel(FixtureShortsRepository(), SavedStateHandle(), ShortsScenario.Content, null, videoActions = actions)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.collect { notices += it } }
            advanceUntilIdle()
            vm.onAction(ShortsAction.Like("short-1"))
            runCurrent()

            vm.updateSession(com.hhst.youtubelite.data.media.MediaSession.snapshot().generation + 1)
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue("An old ${failure.javaClass.simpleName} must not prompt the new account", notices.isEmpty())
            assertTrue(vm.uiState.value.ratingsPending.isEmpty())
            assertTrue(vm.uiState.value.ratings.isEmpty())
        }
    }

    private class NoopActions : com.hhst.youtubelite.watch.domain.VideoActions {
        override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating, previous: com.hhst.youtubelite.watch.domain.VideoRating) {}
        override suspend fun subscribe(channelId: String, subscribed: Boolean) {}
    }

}
