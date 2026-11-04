package com.hhst.youtubelite.watch

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String) -> WatchPage) = object : WatchRepository {
        override suspend fun watch(videoId: String) = block(videoId)
    }

    @Test fun deferredPageLoadsOnlyTheCurrentVideoAfterPlaybackStarts() = runTest {
        val reads = mutableListOf<String>()
        val vm = WatchViewModel(repository { id -> reads += id; FixtureWatchRepository.samplePage(id) },
            SavedStateHandle(), "first", loadOnStart = false)
        vm.selectVideo("second")
        vm.refreshSession()
        advanceUntilIdle()
        assertTrue(reads.isEmpty())
        vm.startLoading()
        vm.startLoading()
        advanceUntilIdle()
        assertEquals(listOf("second"), reads)
    }

    @Test fun explicitPageActionReleasesTheStartupWait() = runTest {
        var reads = 0
        val vm = WatchViewModel(repository { reads++; FixtureWatchRepository.samplePage(it) },
            SavedStateHandle(), "bunny", loadOnStart = false)
        vm.onAction(WatchAction.OpenComments("bunny"))
        advanceUntilIdle()
        assertEquals(1, reads)
        assertTrue(vm.uiState.value.commentsOpen)
    }

    @Test fun signedInRelatedCardsLoadFeedbackEvenWithoutAnInlineMenuEndpoint() = runTest {
        val page = FixtureWatchRepository.samplePage("bunny")
        val card = page.related.first().copy(feedbackGeneration = 7, menuEndpoint = null)
        var reads = 0
        val repo = object : WatchRepository {
            override suspend fun watch(videoId: String) = page.copy(related = listOf(card))
            override suspend fun menu(video: WatchRelated): WatchRelated {
                reads++
                return video.copy(notInterestedToken = "ni", dontRecommendToken = "dc")
            }
        }
        val vm = WatchViewModel(repo, SavedStateHandle(), "bunny", saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        assertEquals(0, reads)
        vm.onAction(WatchAction.OpenItemMenu(card.id))
        advanceUntilIdle()
        assertEquals(1, reads)
        val loaded = (vm.uiState.value.content as WatchContent.Ready).page.related.single()
        assertTrue(watchVideoMenuActions(true, loaded).containsAll(listOf(WatchVideoMenuAction.NotInterested, WatchVideoMenuAction.DontRecommend)))
    }

    @Test fun deferredFeedbackCapabilitiesAppearForTheOpenMenu() = runTest {
        val page = FixtureWatchRepository.samplePage("bunny")
        val card = page.related.first().copy(menuEndpoint = com.hhst.youtubelite.home.domain.FeedMenuEndpoint("panel", "params"))
        val pending = kotlinx.coroutines.CompletableDeferred<WatchRelated>()
        val repo = object : WatchRepository {
            override suspend fun watch(videoId: String) = page.copy(related = listOf(card))
            override suspend fun menu(video: WatchRelated) = pending.await()
        }
        val vm = WatchViewModel(repo, SavedStateHandle(), "bunny", saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu(card.id))
        runCurrent()
        assertTrue(vm.uiState.value.menuLoading)
        pending.complete(card.copy(notInterestedToken = "ni", dontRecommendToken = "dc"))
        advanceUntilIdle()
        val loaded = (vm.uiState.value.content as WatchContent.Ready).page.related.single()
        assertTrue(watchVideoMenuActions(true, loaded).containsAll(listOf(WatchVideoMenuAction.NotInterested, WatchVideoMenuAction.DontRecommend)))
        assertFalse(vm.uiState.value.menuLoading)
    }

    @Test fun closingTheMenuCancelsItsDeferredRead() = runTest {
        val page = FixtureWatchRepository.samplePage("bunny")
        val card = page.related.first().copy(menuEndpoint = com.hhst.youtubelite.home.domain.FeedMenuEndpoint("panel", "params"))
        var cancelled = false
        val repo = object : WatchRepository {
            override suspend fun watch(videoId: String) = page.copy(related = listOf(card))
            override suspend fun menu(video: WatchRelated): WatchRelated {
                try { kotlinx.coroutines.awaitCancellation() }
                finally { cancelled = true }
            }
        }
        val vm = WatchViewModel(repo, SavedStateHandle(), "bunny", saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu(card.id))
        runCurrent()
        vm.onAction(WatchAction.CloseOverlay)
        advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(vm.uiState.value.menuLoading)
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun relatedPagesAppendWithoutDuplicatesAndStopRepeatedCursors() = runTest {
        var calls = 0
        val first = FixtureWatchRepository.samplePage("bunny")
        val repository = object : WatchRepository {
            override suspend fun watch(videoId: String) = first.copy(nextKey = "page-two")
            override suspend fun next(videoId: String, cursor: String): RelatedPage {
                calls++
                return RelatedPage(listOf(first.related.first(), first.related.first().copy(id = "new")), "page-two")
            }
        }
        val vm = WatchViewModel(repository, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(WatchAction.LoadRelated)
        vm.onAction(WatchAction.LoadRelated)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as WatchContent.Ready).page
        assertEquals(first.related.size + 1, page.related.size)
        assertNull(page.nextKey)
        vm.onAction(WatchAction.LoadRelated)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun failedRelatedPageKeepsLoadedContentAndCanBeRetried() = runTest {
        var fail = true
        val first = FixtureWatchRepository.samplePage("bunny")
        val repository = object : WatchRepository {
            override suspend fun watch(videoId: String) = first.copy(nextKey = "next")
            override suspend fun next(videoId: String, cursor: String): RelatedPage {
                if (fail) throw java.io.IOException("offline")
                return RelatedPage(listOf(first.related.first().copy(id = "new")), null)
            }
        }
        val vm = WatchViewModel(repository, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(WatchAction.LoadRelated)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.relatedFailed)
        assertEquals(first.related, (vm.uiState.value.content as WatchContent.Ready).page.related)
        fail = false
        vm.onAction(WatchAction.LoadRelated)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.relatedFailed)
        assertEquals(first.related.size + 1, (vm.uiState.value.content as WatchContent.Ready).page.related.size)
    }

    @Test fun accountChangesReloadSubscriptionStateAndCloseOldSaveChoices() = runTest {
        var subscribed = false
        val vm = WatchViewModel(repository {
            val page = FixtureWatchRepository.samplePage(it)
            page.copy(video = page.video.copy(subscribed = subscribed))
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(WatchAction.Save)
        assertTrue(vm.uiState.value.overlay is WatchOverlay.Save)
        subscribed = true
        vm.refreshSession()
        advanceUntilIdle()
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
        assertTrue((vm.uiState.value.content as WatchContent.Ready).page.video.subscribed)
    }

    @Test fun confirmingARatingKeepsTheLatestCommentPreview() = runTest {
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = object : VideoActions {
            override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) { result.await() }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
        }
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(WatchAction.Like)
        runCurrent()
        val preview = WatchComment("Reader", "A comment from the service", "avatar")
        vm.updateCommentsPreview("bunny", WatchCommentsStatus.Ready, "5.2K", preview)
        result.complete(Unit)
        advanceUntilIdle()
        val video = (vm.uiState.value.content as WatchContent.Ready).page.video
        assertEquals(preview, video.topComment)
        assertEquals("5.2K", video.commentCount)
        assertEquals(VideoRating.Like, video.rating)
        vm.selectVideo("sintel")
        advanceUntilIdle()
        vm.updateCommentsPreview("bunny", WatchCommentsStatus.Failed, "", preview)
        assertNotEquals(preview, (vm.uiState.value.content as WatchContent.Ready).page.video.topComment)
    }

    @Test fun ratingChangesOnlyAfterConfirmationAndBlocksDuplicateTaps() = runTest {
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val actions = object : VideoActions {
            override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) { calls++; result.await() }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
        }
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(WatchAction.Like)
        vm.onAction(WatchAction.Like)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.actionPending)
        assertEquals(WatchAction.Like, vm.uiState.value.pendingVideoAction)
        assertEquals(VideoRating.None, (vm.uiState.value.content as WatchContent.Ready).page.video.rating)
        result.complete(Unit)
        advanceUntilIdle()
        assertEquals(VideoRating.Like, (vm.uiState.value.content as WatchContent.Ready).page.video.rating)
        assertFalse(vm.uiState.value.actionPending)
        assertNull(vm.uiState.value.pendingVideoAction)
    }

    @Test fun failedRatingKeepsTheServerStateAndRequestsSignIn() = runTest {
        val actions = object : VideoActions {
            override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) { throw SignInRequiredException() }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
        }
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(WatchAction.Dislike)
        advanceUntilIdle()
        assertEquals(VideoRating.None, (vm.uiState.value.content as WatchContent.Ready).page.video.rating)
        assertEquals(AppNotice.SignIn, vm.actionNotices.first())
    }

    @Test fun switchingVideosCancelsAnUnfinishedRating() = runTest {
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = object : VideoActions {
            override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) { result.await() }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
        }
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(WatchAction.Like)
        runCurrent()
        vm.selectVideo("sintel")
        result.complete(Unit)
        advanceUntilIdle()
        assertEquals("sintel", vm.uiState.value.videoId)
        assertEquals(VideoRating.None, (vm.uiState.value.content as WatchContent.Ready).page.video.rating)
    }

    @Test fun cancelledAccountActionCannotClearTheNewAccountsPendingAction() = runTest {
        val oldResult = kotlinx.coroutines.CompletableDeferred<Unit>()
        val newResult = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val actions = object : VideoActions {
            override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) {
                val result = if (++calls == 1) oldResult else newResult
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { result.await() }
            }
            override suspend fun subscribe(channelId: String, subscribed: Boolean) = Unit
        }
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(WatchAction.Like)
        runCurrent()
        vm.refreshSession()
        runCurrent()
        vm.onAction(WatchAction.Dislike)
        runCurrent()
        assertTrue(vm.uiState.value.actionPending)
        oldResult.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.actionPending)
        assertEquals(VideoRating.None, (vm.uiState.value.content as WatchContent.Ready).page.video.rating)
        newResult.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
        assertEquals(VideoRating.Dislike, (vm.uiState.value.content as WatchContent.Ready).page.video.rating)
    }

    @Test fun ordinaryEntryLoadsTheLocalPageForTheRequestedVideo() = runTest {
        val ids = mutableListOf<String>()
        val vm = WatchViewModel(repository { id -> ids += id; FixtureWatchRepository.samplePage(id) }, SavedStateHandle(), "bunny")
        assertEquals(WatchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(listOf("bunny"), ids)
        val page = (vm.uiState.value.content as WatchContent.Ready).page
        assertEquals("bunny", page.video.id)
        assertEquals(FixtureWatchRepository.samplePage("bunny").related, page.related)
        assertTrue(page.related.none { it.id == "bunny" })
    }

    @Test fun pastBroadcastFixturesKeepTheirListMetadata() {
        com.hhst.youtubelite.channel.data.FixtureChannelRepository.sampleLive().past.forEach { broadcast ->
            val video = FixtureWatchRepository.sampleVideo(broadcast.id)
            assertEquals(broadcast.id, video.id)
            assertEquals(broadcast.title, video.title)
            assertEquals(broadcast.views, video.views)
            assertEquals(broadcast.artwork, video.artwork)
        }
    }

    @Test fun failedRequestKeepsLoadingOutAndRetryRecovers() = runTest {
        var fail = true
        val vm = WatchViewModel(repository { id ->
            if (fail) error("offline")
            FixtureWatchRepository.samplePage(id)
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertEquals(WatchContent.Failed, vm.uiState.value.content)
        fail = false
        vm.onAction(WatchAction.Retry)
        assertEquals(WatchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
    }

    @Test fun pinnedScenariosDoNotCallTheRepository() = runTest {
        var calls = 0
        for (scenario in listOf(WatchScenario.Loading, WatchScenario.Error)) {
            val vm = WatchViewModel(repository { calls++; FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", scenario)
            advanceUntilIdle()
            assertEquals(scenario.initialContent(), vm.uiState.value.content)
        }
        assertEquals(0, calls)
    }

    @Test fun everyActionEmitsOnlyATypedRequest() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        val expectations: List<Pair<WatchAction, WatchNavigationRequest>> = listOf(
            WatchAction.VoiceSearch to WatchNavigationRequest.VoiceSearch,
            WatchAction.OpenPageMenu to WatchNavigationRequest.PageMenu,
            WatchAction.Like to WatchNavigationRequest.Like("harbour"),
            WatchAction.Dislike to WatchNavigationRequest.Dislike("harbour"),
            WatchAction.Download to WatchNavigationRequest.Download("harbour"),
            WatchAction.Queue to WatchNavigationRequest.Queue("harbour"),
            WatchAction.OpenWith to WatchNavigationRequest.OpenWith("harbour"),
            WatchAction.Share to WatchNavigationRequest.Share("harbour"),
            WatchAction.Subscribe to WatchNavigationRequest.Subscribe("northlight"),
            WatchAction.OpenChannel("studio") to WatchNavigationRequest.Channel("studio"),
            WatchAction.OpenComments("harbour") to WatchNavigationRequest.Comments("harbour"),
            WatchAction.OpenRelated("meadow") to WatchNavigationRequest.Watch("meadow"),
            WatchAction.SeekToTimestamp(42) to WatchNavigationRequest.SeekTo(42),
            WatchAction.OpenDescriptionLink("https://www.blender.org") to WatchNavigationRequest.OpenLink("https://www.blender.org"),
        )
        for ((action, expected) in expectations) {
            vm.onAction(action)
            assertEquals(expected, vm.navigationRequests.first())
            assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        }
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
        assertEquals("harbour", vm.uiState.value.videoId)
    }

    @Test fun relatedCardMenuKeepsSaveLocalAndEmitsTypedWatchLater() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu("studio"))
        assertEquals(WatchOverlay.ItemMenu("studio"), vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.Share))
        assertEquals(WatchNavigationRequest.Share("studio"), vm.navigationRequests.first())
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
        vm.onAction(WatchAction.OpenItemMenu("meadow"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.WatchLater))
        assertEquals(WatchNavigationRequest.SaveToWatchLater("meadow"), vm.navigationRequests.first())
        vm.onAction(WatchAction.OpenItemMenu("meadow"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.SaveToPlaylist))
        assertEquals(WatchOverlay.Save("meadow"), vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(WatchAction.OpenItemMenu("orbit"))
        vm.onAction(WatchAction.CloseOverlay)
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun reportAndRecommendationRowsOpenLocalReasonChoosers() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.Report)
        assertEquals(WatchOverlay.Feedback("harbour", WatchFeedbackKind.Report), vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(WatchAction.FeedbackReason("Harmful content"))
        assertEquals(
            WatchNavigationRequest.Feedback("harbour", WatchFeedbackKind.Report, "Harmful content"),
            vm.navigationRequests.first(),
        )
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)

        vm.onAction(WatchAction.OpenItemMenu("meadow"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.NotInterested))
        assertEquals(WatchOverlay.Feedback("meadow", WatchFeedbackKind.NotInterested), vm.uiState.value.overlay)
        vm.onAction(WatchAction.FeedbackReason("It's repetitive"))
        assertEquals(
            WatchNavigationRequest.Feedback("meadow", WatchFeedbackKind.NotInterested, "It's repetitive"),
            vm.navigationRequests.first(),
        )
        vm.onAction(WatchAction.OpenItemMenu("orbit"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.DontRecommend))
        assertEquals(WatchOverlay.Feedback("orbit", WatchFeedbackKind.DontRecommend), vm.uiState.value.overlay)
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
    }

    @Test fun saveChooserAndNewPlaylistOnlyEmitTypedRequests() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.Save)
        assertEquals(WatchOverlay.Save("harbour"), vm.uiState.value.overlay)
        vm.onAction(WatchAction.SaveDestinationSelected(
            com.hhst.youtubelite.saves.domain.SaveDestination.Playlist("LL", "Liked videos")))
        assertEquals(WatchNavigationRequest.SaveToPlaylist("harbour", "LL", "Liked videos"), vm.navigationRequests.first())
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)

        vm.onAction(WatchAction.Save)
        vm.onAction(WatchAction.SaveDestinationSelected(com.hhst.youtubelite.saves.domain.SaveDestination.WatchLater))
        assertEquals(WatchNavigationRequest.SaveToWatchLater("harbour"), vm.navigationRequests.first())

        vm.onAction(WatchAction.Save)
        vm.onAction(WatchAction.OpenNewPlaylist)
        assertEquals(WatchOverlay.Save("harbour", newPlaylist = true), vm.uiState.value.overlay)
        vm.onAction(WatchAction.SubmitNewPlaylist)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(WatchAction.SaveDraftChanged("Trip", "Later"))
        vm.onAction(WatchAction.SubmitNewPlaylist)
        assertEquals(WatchNavigationRequest.CreatePlaylist("harbour", "Trip", "Later"), vm.navigationRequests.first())
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
    }

    @Test fun relatedCardMenuRestoresThroughSavedState() = runTest {
        val handle = SavedStateHandle()
        val vm = WatchViewModel(FixtureWatchRepository(), handle, "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu("studio"))
        var restored = WatchViewModel(
            FixtureWatchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(WatchOverlay.ItemMenu("studio"), restored.uiState.value.overlay)
        vm.onAction(WatchAction.CloseOverlay)
        restored = WatchViewModel(
            FixtureWatchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(WatchOverlay.None, restored.uiState.value.overlay)
    }

    @Test fun subscribeWithoutLoadedContentEmitsNothing() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", WatchScenario.Loading)
        vm.onAction(WatchAction.Subscribe)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun descriptionExpansionPersistsThroughSavedState() {
        val handle = SavedStateHandle()
        val vm = WatchViewModel(FixtureWatchRepository(), handle, "bunny")
        assertFalse(vm.uiState.value.descriptionExpanded)
        vm.onAction(WatchAction.ToggleDescription)
        assertTrue(vm.uiState.value.descriptionExpanded)
        val restored = WatchViewModel(
            FixtureWatchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "other",
            WatchScenario.Loading,
        )
        assertTrue(restored.uiState.value.descriptionExpanded)
        assertEquals("bunny", restored.uiState.value.videoId)
    }


    @Test fun searchDraftRestoresAndOnlySubmittingEmitsSearch() = runTest {
        val saved = SavedStateHandle()
        val vm = WatchViewModel(FixtureWatchRepository(), saved, "bunny")
        vm.onAction(WatchAction.OpenSearch)
        vm.onAction(WatchAction.QueryChanged("  meadow  "))
        assertTrue(vm.uiState.value.searchOpen)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        val restored = WatchViewModel(FixtureWatchRepository(), SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), "ignored")
        assertEquals("  meadow  ", restored.uiState.value.draftQuery)
        assertTrue(restored.uiState.value.searchOpen)
        restored.onAction(WatchAction.SubmitSearch(restored.uiState.value.draftQuery))
        assertEquals(WatchNavigationRequest.Search("meadow"), restored.navigationRequests.first())
        assertFalse(restored.uiState.value.searchOpen)
        assertEquals("bunny", restored.uiState.value.videoId)
    }

    @Test fun aTypingEventAfterSubmitCannotReopenTheSearchOverlay() = runTest {
        val vm = WatchViewModel(FixtureWatchRepository(), SavedStateHandle(), "bunny")
        vm.onAction(WatchAction.OpenSearch)
        vm.onAction(WatchAction.QueryChanged("meadow"))
        vm.onAction(WatchAction.SubmitSearch(vm.uiState.value.draftQuery))
        assertFalse(vm.uiState.value.searchOpen)
        assertEquals(WatchNavigationRequest.Search("meadow"), vm.navigationRequests.first())
        // A stale IME commit can outlive the submit; it must not re-open the overlay.
        vm.onAction(WatchAction.QueryChanged("meadow"))
        assertFalse(vm.uiState.value.searchOpen)
        assertEquals("meadow", vm.uiState.value.draftQuery)
    }

    @Test fun shortWindowsLeaveTheBodyReachable() {
        assertFalse(WatchLayout.pinnedPlayer(320f, 240f))
        assertFalse(WatchLayout.pinnedPlayer(390f, 240f))
        assertFalse(WatchLayout.pinnedPlayer(1280f, 320f))
        assertEquals(WatchRelatedVariant.List, WatchLayout.relatedVariant(528f, 300f))
    }

    @Test fun playerAndColumnFollowTheBreakpointMatrix() {
        // Phone / grid / stacked: the player is fixed at the column's 16:9.
        assertEquals(390f, WatchLayout.columnWidth(390f, 775f), 0.01f)
        assertEquals(390f * 9f / 16f, WatchLayout.playerHeight(390f, 775f), 0.01f)
        assertTrue(WatchLayout.pinnedPlayer(390f, 775f))
        assertTrue(WatchLayout.pinnedPlayer(840f, 725f))
        // Wide-short: the player scrolls with the content.
        assertFalse(WatchLayout.pinnedPlayer(844f, 342f))
        assertTrue(WatchLayout.wideShort(844f, 342f))
        assertFalse(WatchLayout.wideShort(415f, 342f))
        assertFalse(WatchLayout.wideShort(931f, 600f))
        // Sidebar needs width ≥931 AND landscape.
        assertTrue(WatchLayout.sidebar(931f, 744f))
        assertTrue(WatchLayout.sidebar(1600f, 915f))
        assertFalse(WatchLayout.sidebar(1000f, 1200f))
        assertFalse(WatchLayout.sidebar(930f, 900f))
        assertEquals(256f, WatchLayout.sidebarWidth(931f), 0.01f)
        assertEquals(256f, WatchLayout.sidebarWidth(1100f), 0.01f)
        assertEquals(500f, WatchLayout.sidebarWidth(1280f), 0.01f)
        assertEquals(675f, WatchLayout.columnWidth(931f, 744f), 0.01f)
        assertEquals(780f, WatchLayout.columnWidth(1280f, 725f), 0.01f)
        assertEquals(1100f, WatchLayout.columnWidth(1600f, 915f), 0.01f)
        assertEquals(675f * 9f / 16f, WatchLayout.playerHeight(931f, 744f), 0.01f)
    }

    @Test fun gridColumnsFollowTheSampledFormula() {
        assertEquals(1, WatchLayout.gridColumns(390f))
        assertEquals(2, WatchLayout.gridColumns(550f))
        assertEquals(2, WatchLayout.gridColumns(750f))
        assertEquals(3, WatchLayout.gridColumns(780f))
        assertEquals(3, WatchLayout.gridColumns(840f))
        assertEquals(267f, WatchLayout.gridCell(550f), 0.01f)
        assertEquals(292f, WatchLayout.gridCell(600f), 0.01f)
        assertEquals(342f, WatchLayout.gridCell(700f), 0.01f)
        assertEquals(367f, WatchLayout.gridCell(750f), 0.01f)
        assertEquals(252f, WatchLayout.gridCell(780f), 0.01f)
        assertEquals(272f, WatchLayout.gridCell(840f), 0.01f)
    }

    @Test fun relatedVariantSelectionMatchesTheZoneMatrix() {
        val expectations = listOf(
            Triple(320f, 658f, WatchRelatedVariant.Phone),
            Triple(390f, 775f, WatchRelatedVariant.Phone),
            Triple(411f, 714f, WatchRelatedVariant.Phone),
            Triple(550f, 828f, WatchRelatedVariant.Grid),
            Triple(840f, 914f, WatchRelatedVariant.Grid),
            Triple(844f, 342f, WatchRelatedVariant.List),
            Triple(840f, 725f, WatchRelatedVariant.Stacked),
            Triple(931f, 744f, WatchRelatedVariant.SidebarCompact),
            Triple(1100f, 725f, WatchRelatedVariant.SidebarCompact),
            Triple(1280f, 725f, WatchRelatedVariant.SidebarWide),
            Triple(1280f, 1104f, WatchRelatedVariant.SidebarWide),
            Triple(1600f, 915f, WatchRelatedVariant.SidebarWide),
        )
        for ((width, height, expected) in expectations) {
            assertEquals("$width × $height", expected, WatchLayout.relatedVariant(width, height))
        }
    }

    @Test fun phoneCoverKeepsTheSampledPlusThirteen() {
        assertEquals(193f, WatchLayout.phoneCoverHeight(320f), 0.01f)
        assertEquals(390f * 9f / 16f + 13f, WatchLayout.phoneCoverHeight(390f), 0.01f)
        assertEquals(411f * 9f / 16f + 13f, WatchLayout.phoneCoverHeight(411f), 0.01f)
        assertEquals(244.1875f, WatchLayout.phoneCoverHeight(411f), 0.01f)
    }

    @Test fun metadataIncludesIdentityAndSeparateActions() {
        assertEquals(0f, WatchLayout.TopBarHeight, 0.01f)
        assertEquals(70f, WatchLayout.InfoBlockHeight, 0.01f)
        assertEquals(166f, WatchLayout.MetadataSectionHeight, 0.01f)
        assertEquals(48f, WatchLayout.ActionBarHeight, 0.01f)
        assertEquals(113f, WatchLayout.CommentsSectionHeight, 0.01f)
        assertEquals(92f, WatchLayout.CommentsCardHeight, 0.01f)
        assertEquals(200f, WatchLayout.ListCoverWidth, 0.01f)
        assertEquals(112f, WatchLayout.ListCoverHeight, 0.01f)
    }

    @Test fun fixtureKeepsEnglishMetadataAndLocalArtwork() {
        val page = FixtureWatchRepository.samplePage("bunny")
        val video = page.video
        assertEquals("110K likes", video.likes)
        assertEquals("5.2K", video.commentCount)
        assertTrue(video.title.isNotBlank() && video.channel.isNotBlank() && video.handle.isNotBlank())
        assertTrue(video.views.endsWith("views"))
        assertTrue(video.description.isNotBlank())
        assertEquals("Maya K.", video.topComment.author)

        val related = FixtureWatchRepository.sampleRelated()
        assertTrue(related.size >= 6)
        assertEquals(related.size, related.map { it.id }.distinct().size)
        assertEquals(listOf("bunny", "dream", "sintel"), related.map { it.artwork }.distinct().sorted())
        assertTrue(related.all { it.title.isNotBlank() && it.channelId.isNotBlank() })
        assertTrue(related.all { it.views.endsWith("views") && it.duration.isNotBlank() })
        assertEquals(listOf("4K"), related.mapNotNull { it.badge })
        assertTrue(related.any { it.progress != null })
        assertTrue(FixtureWatchRepository.samplePage("harbour").related.any { it.id == "bunny" })
        assertTrue(FixtureWatchRepository.samplePage("harbour").related.none { it.id == "harbour" })
    }

    private class FakeSaveRepository(
        var choicesResult: List<com.hhst.youtubelite.saves.domain.SaveChoice> = listOf(
            com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false),
            com.hhst.youtubelite.saves.domain.SaveChoice("PL1", "Music", true),
        ),
        var choicesError: Exception? = null,
        var writeError: Exception? = null,
    ) : com.hhst.youtubelite.saves.domain.SaveRepository {
        val writes = ArrayList<Triple<String, String, Boolean>>()
        var watchLaterCalls = 0
        override suspend fun choices(videoId: String) = choicesError?.let { throw it } ?: choicesResult
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {
            writeError?.let { throw it }
            writes.add(Triple(playlistId, videoId, contains))
        }
        override suspend fun saveWatchLater(videoId: String) {
            writeError?.let { throw it }
            watchLaterCalls++
        }
        override suspend fun create(title: String, description: String, videoId: String): com.hhst.youtubelite.saves.domain.SaveChoice {
            writeError?.let { throw it }
            return com.hhst.youtubelite.saves.domain.SaveChoice("PLnew", title, true)
        }
        override suspend fun savePlaylistToLibrary(playlistId: String) {
            writeError?.let { throw it }
        }
    }

    private fun liveSaveViewModel(saves: FakeSaveRepository): WatchViewModel =
        WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", saveRepository = saves)

    @Test fun relatedVideoSaveUsesItsOwnServerChoices() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveSaveViewModel(saves)
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu("studio"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        val save = vm.uiState.value.overlay as WatchOverlay.Save
        assertTrue(save.live)
        assertEquals("studio", save.videoId)
        assertEquals(saves.choicesResult, save.choices)
        vm.onAction(WatchAction.SaveChoiceToggled(save.choices!!.first()))
        advanceUntilIdle()
        assertEquals(listOf(Triple("WL", "studio", true)), saves.writes)
    }

    @Test fun liveSaveSheetLoadsServerChoicesAndTogglesAfterConfirmation() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveSaveViewModel(saves)
        advanceUntilIdle()
        vm.onAction(WatchAction.Save)
        advanceUntilIdle()
        val loaded = vm.uiState.value.overlay as WatchOverlay.Save
        assertTrue(loaded.live)
        assertEquals(listOf("WL", "PL1"), loaded.choices!!.map { it.id })
        assertTrue(loaded.choices!![1].contains)
        vm.onAction(WatchAction.SaveChoiceToggled(loaded.choices!![0]))
        advanceUntilIdle()
        assertEquals(listOf(Triple("WL", "bunny", true)), saves.writes)
        val after = vm.uiState.value.overlay as WatchOverlay.Save
        assertTrue(after.choices!![0].contains)
        assertFalse(after.pending)
    }

    @Test fun unconfirmedSaveToggleKeepsTheServerStateAndNotifies() = runTest {
        val saves = FakeSaveRepository(writeError = UnconfirmedActionException())
        val vm = liveSaveViewModel(saves)
        advanceUntilIdle()
        vm.onAction(WatchAction.Save)
        advanceUntilIdle()
        val loaded = vm.uiState.value.overlay as WatchOverlay.Save
        vm.onAction(WatchAction.SaveChoiceToggled(loaded.choices!![1]))
        advanceUntilIdle()
        val after = vm.uiState.value.overlay as WatchOverlay.Save
        assertTrue(after.choices!![1].contains)
        assertFalse(after.pending)
        val notice = withTimeoutOrNull(500) { vm.actionNotices.first() }
        assertTrue(notice is AppNotice.Failure)
        assertEquals("The server did not confirm this change. Please refresh before trying again.",
            (notice as AppNotice.Failure).text)
        assertTrue(notice.report.text.contains("Outcome: unconfirmed"))
    }

    @Test fun guestSaveClosesTheSheetAndRequestsSignIn() = runTest {
        val saves = FakeSaveRepository(choicesError = SignInRequiredException())
        val vm = liveSaveViewModel(saves)
        advanceUntilIdle()
        vm.onAction(WatchAction.Save)
        advanceUntilIdle()
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
        assertEquals(AppNotice.SignIn, withTimeoutOrNull(500) { vm.actionNotices.first() })
    }

    @Test fun createdPlaylistAppearsCheckedInTheOpenSheet() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveSaveViewModel(saves)
        advanceUntilIdle()
        vm.onAction(WatchAction.Save)
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenNewPlaylist)
        vm.onAction(WatchAction.SaveDraftChanged("Night walks", "city footage"))
        vm.onAction(WatchAction.SubmitNewPlaylist)
        advanceUntilIdle()
        val after = vm.uiState.value.overlay as WatchOverlay.Save
        assertFalse(after.newPlaylist)
        assertEquals(listOf("WL", "PL1", "PLnew"), after.choices!!.map { it.id })
        assertTrue(after.choices!!.last().contains)
    }

    @Test fun watchLaterMenuRowWritesThroughTheRepository() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveSaveViewModel(saves)
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu("bunny"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(1, saves.watchLaterCalls)
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun descriptionTimestampSeeksThroughTheHostBoundary() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.SeekToTimestamp(42))
        assertEquals(WatchNavigationRequest.SeekTo(42), vm.navigationRequests.first())
    }

    @Test fun liveVideoTimestampExplainsInsteadOfSeeking() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "live")
        advanceUntilIdle()
        assertTrue((vm.uiState.value.content as WatchContent.Ready).page.video.live)
        vm.onAction(WatchAction.SeekToTimestamp(42))
        assertNull(withTimeoutOrNull(500) { vm.navigationRequests.first() })
        assertTrue(withTimeoutOrNull(500) { vm.actionNotices.first() } is AppNotice.Message)
    }

    @Test fun trafficChallengeReportsVerificationAndRetriesOnceOnCompletion() = runTest {
        var challenged = false
        val challenge = com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
            com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION,
            "traffic_challenge", 302, detail = "https://www.google.com/sorry/index?q=x")
        val vm = WatchViewModel(repository {
            if (challenged) FixtureWatchRepository.samplePage(it)
            else {
                challenged = true
                throw com.hhst.youtubelite.data.media.MediaException(challenge)
            }
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertEquals(WatchContent.Failed, vm.uiState.value.content)
        assertEquals("https://www.google.com/sorry/index?q=x", vm.uiState.value.verification?.challengeUrl)
        vm.verificationCompleted()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
        assertNull(vm.uiState.value.verification)
    }

    @Test fun authFailureNeverReportsVerification() = runTest {
        val vm = WatchViewModel(repository {
            throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.AUTH, "http_403", 403))
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertEquals(WatchContent.Failed, vm.uiState.value.content)
        assertNull(vm.uiState.value.verification)
    }

    @Test fun aManualReloadClearsVerificationAndIgnoresItsLateResult() = runTest {
        var calls = 0
        val url = "https://www.google.com/sorry/index?q=old-read"
        val vm = WatchViewModel(repository {
            if (++calls == 1) throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION, "traffic_challenge", detail = url))
            FixtureWatchRepository.samplePage(it)
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.verification)
        vm.onAction(WatchAction.Retry)
        advanceUntilIdle()
        assertNull(vm.uiState.value.verification)
        vm.verificationCompleted(url)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun provisionalSummaryPaintsTheLoadingPageAndYieldsToRealContent() = runTest {
        val result = kotlinx.coroutines.CompletableDeferred<WatchPage>()
        val vm = WatchViewModel(repository { result.await() }, SavedStateHandle(), "bunny",
            provisional = WatchProvisional(videoId = "bunny", title = "Cached title",
                channel = "Known channel", channelId = "ch-1", artwork = "https://img/bunny",
                views = "1.2M views", age = "3 days ago", duration = "4:08"))
        runCurrent()
        assertTrue(vm.uiState.value.content is WatchContent.Loading)
        val known = vm.uiState.value.provisional
        assertEquals("Cached title", known?.title)
        assertEquals("Known channel", known?.channel)
        assertEquals("4:08", known?.duration)
        result.complete(FixtureWatchRepository.samplePage("bunny"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
    }

    @Test fun thinWatchResponsesKeepKnownCardMetadataAndFreshViewerState() = runTest {
        val page = FixtureWatchRepository.samplePage("bunny")
        var reads = 0
        val vm = WatchViewModel(repository {
            reads++
            page.copy(video = page.video.copy(title = "", channel = "", channelId = "", artwork = "",
                views = if (reads == 1) "2M views" else "", age = "", avatar = "",
                rating = if (reads == 1) VideoRating.Like else VideoRating.None, subscribed = reads == 1))
        }, SavedStateHandle(), "bunny", provisional = WatchProvisional("bunny", title = "Card title",
            channel = "Card channel", channelId = "channel-id", artwork = "cover", views = "1M views",
            age = "2 days ago", avatar = "channel-avatar"))
        advanceUntilIdle()
        var video = (vm.uiState.value.content as WatchContent.Ready).page.video
        assertEquals("Card title", video.title)
        assertEquals("Card channel", video.channel)
        assertEquals("2M views", video.views)
        assertEquals("2 days ago", video.age)
        assertEquals("channel-avatar", video.avatar)
        vm.onAction(WatchAction.Retry)
        advanceUntilIdle()
        video = (vm.uiState.value.content as WatchContent.Ready).page.video
        assertEquals("2M views", video.views)
        assertEquals(VideoRating.None, video.rating)
        assertFalse(video.subscribed)
    }

    @Test fun richerRelatedDuplicatesFillMissingFieldsWithoutDuplicatingCards() = runTest {
        val page = FixtureWatchRepository.samplePage("bunny")
        val card = page.related.first().copy(views = "", age = "", avatar = null)
        val repo = object : WatchRepository {
            override suspend fun watch(videoId: String) = page.copy(related = listOf(card), nextKey = "next")
            override suspend fun next(videoId: String, cursor: String) = RelatedPage(listOf(
                card.copy(title = "Different title", views = "1K views", age = "1 day ago", avatar = "channel-avatar")), null)
        }
        val vm = WatchViewModel(repo, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(WatchAction.LoadRelated)
        advanceUntilIdle()
        val merged = (vm.uiState.value.content as WatchContent.Ready).page.related.single()
        assertEquals(card.title, merged.title)
        assertEquals("1K views", merged.views)
        assertEquals("1 day ago", merged.age)
        assertEquals("channel-avatar", merged.avatar)
    }

    @Test fun aLiveStreamWithoutAChatKeyDoesNotOpenTheCommentsPanel() = runTest {
        fun liveRepository() = repository { id ->
            val page = FixtureWatchRepository.samplePage(id)
            page.copy(video = page.video.copy(live = true, chatKey = null))
        }
        val vm = WatchViewModel(liveRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenComments("bunny"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.commentsOpen)
        assertTrue(withTimeoutOrNull(1) { vm.navigationRequests.first() } == null)
        val plain = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        plain.onAction(WatchAction.OpenComments("bunny"))
        assertEquals(WatchNavigationRequest.Comments("bunny"), plain.navigationRequests.first())
        assertTrue(plain.uiState.value.commentsOpen)
    }

    @Test fun switchingVideosWithoutACardClearsTheStaleProvisionalSummary() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny",
            provisional = WatchProvisional(videoId = "bunny", title = "Cached title"))
        advanceUntilIdle()
        vm.selectVideo("sintel")
        advanceUntilIdle()
        assertNull(vm.uiState.value.provisional)
    }
}
