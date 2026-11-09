package com.hhst.youtubelite.playlist

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.playlist.data.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.playlist.presentation.PlaylistViewModel
import com.hhst.youtubelite.share.PlaylistShare
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {
    @Test fun navigationSessionTracksSignInAndSignOutWithoutDiscardingThePlaylistIdentity() = runTest {
        val model = vm()
        model.updateSession(true, 1L)
        advanceUntilIdle()
        assertTrue(model.uiState.value.signedIn)
        assertEquals(FixturePlaylistRepository.MOVIES, model.uiState.value.playlistId)
        model.onAction(PlaylistAction.Destination(PlaylistDestination.Subscriptions))
        assertEquals(PlaylistNavigationRequest.Destination(PlaylistDestination.Subscriptions), model.navigationRequests.first())
        model.updateSession(false, 2L)
        advanceUntilIdle()
        assertFalse(model.uiState.value.signedIn)
        assertEquals(FixturePlaylistRepository.MOVIES, model.uiState.value.playlistId)
    }

    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun vm(saved: SavedStateHandle = SavedStateHandle(), scenario: PlaylistScenario = PlaylistScenario.Content) =
        PlaylistViewModel(FixturePlaylistRepository(scenario), saved, FixturePlaylistRepository.MOVIES, scenario)

    @Test fun fixtureLoadsWithoutConvertingPlaylistIdentityIntoAVideo() = runTest {
        val model = vm()
        assertEquals(PlaylistContent.Loading, model.uiState.value.content)
        advanceUntilIdle()
        val page = (model.uiState.value.content as PlaylistContent.Ready).page
        model.onAction(PlaylistAction.OpenVideo(page.entries[1].id))
        assertEquals(PlaylistNavigationRequest.Watch("dream"), model.navigationRequests.first())
        model.onAction(PlaylistAction.OpenVideo("not-an-entry"))
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
        val studio = PlaylistViewModel(FixturePlaylistRepository(), SavedStateHandle(), FixturePlaylistRepository.STUDIO)
        advanceUntilIdle()
        studio.onAction(PlaylistAction.OpenOwner)
        assertEquals(PlaylistNavigationRequest.Channel("studio"), studio.navigationRequests.first())
    }

    @Test fun queuesRemainRequestsWithStableOrderAndNoMutations() = runTest {
        val model = vm()
        advanceUntilIdle()
        val before = model.uiState.value.content
        model.onAction(PlaylistAction.PlayAll)
        assertEquals(PlaylistNavigationRequest.Queue(FixturePlaylistRepository.MOVIES, listOf("bunny", "dream", "sintel"), false), model.navigationRequests.first())
        model.onAction(PlaylistAction.OpenMenu)
        model.onAction(PlaylistAction.Shuffle)
        assertEquals(PlaylistNavigationRequest.Queue(FixturePlaylistRepository.MOVIES, listOf("bunny", "dream", "sintel"), true), model.navigationRequests.first())
        assertEquals(PlaylistOverlay.None, model.uiState.value.overlay)
        assertEquals(before, model.uiState.value.content)
    }

    @Test fun menuActionsCarryEntryAndVideoIdentity() = runTest {
        val model = vm()
        advanceUntilIdle()
        model.onAction(PlaylistAction.OpenVideoMenu("dream-1"))
        model.onAction(PlaylistAction.VideoMenuAction(PlaylistVideoAction.WatchLater))
        assertEquals(PlaylistNavigationRequest.VideoAction(FixturePlaylistRepository.MOVIES, "dream-1", "dream", PlaylistVideoAction.WatchLater), model.navigationRequests.first())
        model.onAction(PlaylistAction.OpenVideoMenu("sintel-1"))
        model.onAction(PlaylistAction.VideoMenuAction(PlaylistVideoAction.Save))
        assertEquals(PlaylistNavigationRequest.VideoAction(FixturePlaylistRepository.MOVIES, "sintel-1", "sintel", PlaylistVideoAction.Save), model.navigationRequests.first())
        model.onAction(PlaylistAction.Share)
        assertEquals(PlaylistNavigationRequest.Share(FixturePlaylistRepository.MOVIES), model.navigationRequests.first())
    }

    @Test fun descriptionExpansionIsLocalAndSurvivesRecreation() = runTest {
        val saved = SavedStateHandle()
        val model = vm(saved)
        advanceUntilIdle()
        assertFalse(model.uiState.value.descriptionExpanded)
        model.onAction(PlaylistAction.ToggleDescription)
        assertTrue(model.uiState.value.descriptionExpanded)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
        val restored = vm(saved)
        advanceUntilIdle()
        assertTrue(restored.uiState.value.descriptionExpanded)
        restored.onAction(PlaylistAction.ToggleDescription)
        assertFalse(restored.uiState.value.descriptionExpanded)
    }

    @Test fun overlaysQueryAndIdentityRestore() = runTest {
        val saved = SavedStateHandle()
        val model = vm(saved)
        advanceUntilIdle()
        model.onAction(PlaylistAction.OpenSearch)
        model.onAction(PlaylistAction.QueryChanged("open movie"))
        val restored = vm(saved)
        advanceUntilIdle()
        assertEquals("open movie", restored.uiState.value.query)
        assertEquals(PlaylistOverlay.Search, restored.uiState.value.overlay)
        restored.onAction(PlaylistAction.Search("  open movie  "))
        assertEquals(PlaylistNavigationRequest.Search("open movie"), restored.navigationRequests.first())
        assertEquals(PlaylistOverlay.None, restored.uiState.value.overlay)
        restored.onAction(PlaylistAction.OpenVideoMenu("bunny-1"))
        val menu = vm(saved)
        advanceUntilIdle()
        assertEquals(PlaylistOverlay.VideoMenu("bunny-1"), menu.uiState.value.overlay)
    }

    @Test fun emptyUnavailableAndFailureAreSeparate() = runTest {
        val empty = vm(scenario = PlaylistScenario.Empty)
        val unavailable = vm(scenario = PlaylistScenario.Unavailable)
        val failed = vm(scenario = PlaylistScenario.Error)
        advanceUntilIdle()
        assertTrue((empty.uiState.value.content as PlaylistContent.Ready).page.entries.isEmpty())
        assertEquals(0, (empty.uiState.value.content as PlaylistContent.Ready).page.summary.videoCount)
        assertEquals(PlaylistContent.Unavailable, unavailable.uiState.value.content)
        assertEquals(PlaylistContent.Failed, failed.uiState.value.content)
        empty.onAction(PlaylistAction.PlayAll)
        assertNull(withTimeoutOrNull(1) { empty.navigationRequests.first() })
    }

    @Test fun retryAndCancellationKeepLatestResult() = runTest {
        var calls = 0
        val repo = object : PlaylistRepository {
            override suspend fun playlist(id: String): PlaylistPage? {
                val request = ++calls
                if (request == 1) { delay(500); error("cancelled old request") }
                return FixturePlaylistRepository().playlist(id)
            }
        }
        val model = PlaylistViewModel(repo, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        runCurrent()
        model.onAction(PlaylistAction.Retry)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(model.uiState.value.content is PlaylistContent.Ready)
    }

    @Test fun publicPlaylistLinksRejectLocalNamesAndQueryInjection() {
        assertEquals("https://www.youtube.com/playlist?list=${FixturePlaylistRepository.MOVIES}", PlaylistShare.urlFor(FixturePlaylistRepository.MOVIES))
        assertNull(PlaylistShare.urlFor("movies"))
        assertNull(PlaylistShare.urlFor("${FixturePlaylistRepository.MOVIES}&x=1"))
        assertNull(PlaylistShare.urlFor("PL"))
    }

    private class PagedRepository(
        private val failAppend: Boolean = false,
        var loopCursor: Boolean = false,
    ) : PlaylistRepository {
        var nextCalls = 0
        val page1 = listOf(
            PlaylistEntry("a1", "video-a", "A", "Owner", "", "art", "1:00", "", ""),
            PlaylistEntry("b1", "video-b", "B", "Owner", "", "art", "2:00", "", ""))
        private val page2 = listOf(
            PlaylistEntry("c1", "video-c", "C", "Owner", "", "art", "3:00", "", ""),
            PlaylistEntry("b1", "video-b", "B (repeat)", "Owner", "", "art", "2:00", "", ""),
            PlaylistEntry("d1", "video-d", "D", "Owner", "", "art", "4:00", "", ""))
        override suspend fun playlist(id: String) =
            PlaylistPage(PlaylistSummary(id, "Paged", "Owner", "", null, "art", "", 0), "", page1, "cursor-1")
        override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage {
            nextCalls++
            if (failAppend) error("append offline")
            delay(100)
            if (loopCursor) return PlaylistAppendPage(page2.take(1), cursor) // never progresses
            return PlaylistAppendPage(page2, null)
        }
    }

    @Test fun playlistAppendsIncrementallyAndKeepsRowsOnFailure() = runTest {
        val repo = PagedRepository()
        val model = PlaylistViewModel(repo, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        val ready = model.uiState.value.content as PlaylistContent.Ready
        assertEquals(2, ready.page.entries.size)
        assertEquals("cursor-1", ready.page.nextKey)
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        val appended = model.uiState.value.content as PlaylistContent.Ready
        assertEquals(4, appended.page.entries.size) // duplicate video-b merges
        assertNull(appended.page.nextKey)
        assertEquals(AppendState.Idle, model.uiState.value.append)
        val broken = PlaylistViewModel(PagedRepository(failAppend = true), SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        broken.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals(2, ((broken.uiState.value.content as PlaylistContent.Ready).page.entries.size))
        assertEquals(AppendState.Failed, broken.uiState.value.append)
    }

    @Test fun playAllEnumeratesTheFullPlaylistBeforeQueueing() = runTest {
        val model = PlaylistViewModel(PagedRepository(), SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        runCurrent()
        assertEquals(PlaylistEnumeration.Running(2, null), model.uiState.value.enumeration)
        advanceUntilIdle()
        val request = model.navigationRequests.first()
        assertEquals(PlaylistNavigationRequest.Queue(FixturePlaylistRepository.MOVIES,
            listOf("video-a", "video-b", "video-c", "video-d"), false), request)
        assertEquals(PlaylistEnumeration.Idle, model.uiState.value.enumeration)
        val page = (model.uiState.value.content as PlaylistContent.Ready).page
        assertEquals(4, page.summary.videoCount)
        assertNull(page.nextKey)
    }

    @Test fun failedEnumerationRefusesToQueueAPartialList() = runTest {
        val model = PlaylistViewModel(PagedRepository(failAppend = true), SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        advanceUntilIdle()
        assertEquals(PlaylistEnumeration.Failed, model.uiState.value.enumeration)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
        assertEquals(2, (model.uiState.value.content as PlaylistContent.Ready).page.entries.size)
    }

    @Test fun enumerationCanBeCancelledAndNeverRunsTheAction() = runTest {
        val model = PlaylistViewModel(PagedRepository(), SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        runCurrent()
        assertTrue(model.uiState.value.enumeration is PlaylistEnumeration.Running)
        model.onAction(PlaylistAction.CancelEnumeration)
        advanceUntilIdle()
        assertEquals(PlaylistEnumeration.Idle, model.uiState.value.enumeration)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
    }

    @Test fun aRepeatingCursorFailsWithoutQueueingAPartialListAndCanBeRetried() = runTest {
        val repo = PagedRepository(loopCursor = true)
        val model = PlaylistViewModel(repo, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        advanceUntilIdle()
        assertEquals(1, repo.nextCalls)
        assertEquals(PlaylistEnumeration.Failed, model.uiState.value.enumeration)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
        val incomplete = (model.uiState.value.content as PlaylistContent.Ready).page
        assertEquals(repo.page1, incomplete.entries)
        assertEquals("cursor-1", incomplete.nextKey)
        repo.loopCursor = false
        model.onAction(PlaylistAction.RetryEnumeration)
        advanceUntilIdle()
        assertEquals(2, repo.nextCalls)
        assertEquals(PlaylistNavigationRequest.Queue(FixturePlaylistRepository.MOVIES,
            listOf("video-a", "video-b", "video-c", "video-d"), false), model.navigationRequests.first())
        assertEquals(PlaylistEnumeration.Idle, model.uiState.value.enumeration)
        assertNull((model.uiState.value.content as PlaylistContent.Ready).page.nextKey)
    }

    @Test fun anEmptyMultiPageCursorCycleCannotDownloadAPartialListAndCanBeRetried() = runTest {
        val initial = PagedRepository().playlist(FixturePlaylistRepository.MOVIES)
        var loop = true
        val cursors = mutableListOf<String>()
        val repo = object : PlaylistRepository {
            override suspend fun playlist(id: String) = initial
            override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage {
                cursors += cursor
                return if (loop) PlaylistAppendPage(emptyList(), if (cursor == "cursor-1") "cursor-2" else "cursor-1")
                else PlaylistAppendPage(listOf(initial.entries.first().copy(id = "c1", videoId = "video-c")), null)
            }
        }
        val model = PlaylistViewModel(repo, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.Download)
        advanceUntilIdle()
        assertEquals(listOf("cursor-1", "cursor-2"), cursors)
        assertEquals(PlaylistEnumeration.Failed, model.uiState.value.enumeration)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
        assertEquals(initial, (model.uiState.value.content as PlaylistContent.Ready).page)
        loop = false
        model.onAction(PlaylistAction.RetryEnumeration)
        advanceUntilIdle()
        assertEquals(listOf("cursor-1", "cursor-2", "cursor-1"), cursors)
        assertEquals(PlaylistNavigationRequest.Download(FixturePlaylistRepository.MOVIES), model.navigationRequests.first())
        assertEquals(PlaylistEnumeration.Idle, model.uiState.value.enumeration)
        val complete = (model.uiState.value.content as PlaylistContent.Ready).page
        assertEquals(3, complete.entries.size)
        assertNull(complete.nextKey)
    }

    @Test fun ordinaryPaginationStillEndsOnARepeatingCursor() = runTest {
        val repo = PagedRepository(loopCursor = true)
        val model = PlaylistViewModel(repo, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals(1, repo.nextCalls)
        val appended = (model.uiState.value.content as PlaylistContent.Ready).page
        assertEquals(3, appended.entries.size)
        assertNull(appended.nextKey)
        assertEquals(AppendState.Idle, model.uiState.value.append)
    }
}
