package com.hhst.youtubelite.playlist

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.playlist.data.*
import com.hhst.youtubelite.playlist.domain.*
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
}
