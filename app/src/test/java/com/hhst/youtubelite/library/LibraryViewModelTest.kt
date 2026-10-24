package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.data.FixtureHomeRepository
import com.hhst.youtubelite.library.data.*
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.CollectionViewModel
import com.hhst.youtubelite.library.presentation.HistoryViewModel
import com.hhst.youtubelite.library.presentation.YouViewModel
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.ui.components.MainTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun loginRefreshesAndLogoutDiscardsPendingAccountContent() = runTest {
        val loads = mutableListOf<String>()
        val vm = YouViewModel(recordingRepository(loads), SavedStateHandle(), LibraryScenario.Content, false)
        vm.updateSession(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is LibraryContent.Ready)
        assertTrue(vm.uiState.value.signedIn)
        vm.updateSession(false)
        assertTrue(vm.uiState.value.content is LibraryContent.SignedOut)
        assertFalse(vm.uiState.value.signedIn)
    }

    @Test fun signedOutYouPageShowsTheSampledPromptWithoutLoading() = runTest {
        val loads = mutableListOf<String>()
        val vm = YouViewModel(recordingRepository(loads), SavedStateHandle(), LibraryScenario.Content, signedIn = false)
        advanceUntilIdle()
        val content = vm.uiState.value.content
        assertTrue(content is LibraryContent.SignedOut)
        assertEquals("Don't miss new videos", (content as LibraryContent.SignedOut).prompt.title)
        assertTrue(loads.isEmpty())
    }

    @Test fun youPageLoadsSectionsAndEmitsTypedExits() = runTest {
        val vm = YouViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content, signedIn = true)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as LibraryContent.Ready).page
        assertEquals(4, page.playlists.size)
        assertTrue(page.history.isNotEmpty())
        assertEquals("WL", page.playlists[0].id)
        assertEquals("LL", page.playlists[1].id)

        vm.onAction(LibraryAction.OpenWatchLater)
        assertEquals(LibraryNavigationRequest.Playlist(FixturePlaylistRepository.WATCH_LATER), vm.navigationRequests.first())
        vm.onAction(LibraryAction.OpenLiked)
        assertEquals(LibraryNavigationRequest.Playlist(FixturePlaylistRepository.LIKED), vm.navigationRequests.first())
        vm.onAction(LibraryAction.OpenHistory)
        assertEquals(LibraryNavigationRequest.OpenHistory, vm.navigationRequests.first())
        vm.onAction(LibraryAction.OpenDownloads)
        assertEquals(LibraryNavigationRequest.OpenDownloads, vm.navigationRequests.first())
        vm.onAction(LibraryAction.Destination(MainTab.Subscriptions))
        assertEquals(LibraryNavigationRequest.Destination(MainTab.Subscriptions), vm.navigationRequests.first())
    }

    @Test fun selectingYourOwnTabDoesNotNavigateAway() = runTest {
        val vm = YouViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content, signedIn = true)
        advanceUntilIdle()
        val requests = mutableListOf<LibraryNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(LibraryAction.Destination(MainTab.You))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun historyMenuEmitsOneRequestAndCloses() = runTest {
        val vm = HistoryViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("bunny"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        assertEquals(LibraryNavigationRequest.MenuAction("bunny", HistoryMenuAction.RemoveFromHistory), vm.navigationRequests.first())
        assertEquals(LibraryOverlay.None, vm.uiState.value.overlay)
        // The menu emits a request only; nothing is removed from the local history sample.
        assertTrue((vm.uiState.value.content as HistoryContent.Ready).page.entries.isNotEmpty())
    }

    @Test fun historyEmptyAndErrorStatesStayTyped() = runTest {
        val empty = HistoryViewModel(FixtureLibraryRepository(LibraryScenario.HistoryEmpty), SavedStateHandle(), LibraryScenario.HistoryEmpty)
        advanceUntilIdle()
        assertTrue(empty.uiState.value.content is HistoryContent.Empty)
        val failed = HistoryViewModel(repository { error("offline") }, SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        assertEquals(HistoryContent.Failed, failed.uiState.value.content)
        failed.onAction(LibraryAction.Retry)
        advanceUntilIdle()
        assertEquals(HistoryContent.Failed, failed.uiState.value.content)
    }

    @Test fun collectionEmptiesAndOpensPlaylistIdentity() = runTest {
        val empty = CollectionViewModel(FixtureLibraryRepository(LibraryScenario.PlaylistsEmpty), SavedStateHandle(), LibraryScenario.PlaylistsEmpty)
        advanceUntilIdle()
        assertTrue(empty.uiState.value.content is CollectionContent.Empty)
        val vm = CollectionViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenPlaylist("WL"))
        assertEquals(LibraryNavigationRequest.Playlist("WL"), vm.navigationRequests.first())
    }

    @Test fun searchOverlayRestoresQueryAfterClose() = runTest {
        val vm = YouViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content, signedIn = true)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenSearch)
        vm.onAction(LibraryAction.QueryChanged("open movies"))
        vm.onAction(LibraryAction.CloseOverlay)
        assertEquals(LibraryOverlay.None, vm.uiState.value.overlay)
        // The draft survives the close and is still there when the panel reopens.
        assertEquals("open movies", vm.uiState.value.query)
        vm.onAction(LibraryAction.OpenSearch)
        assertEquals("open movies", vm.uiState.value.query)
    }

    private fun repository(block: suspend (String) -> HistoryPage) = object : LibraryRepository {
        override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), block("history").entries, FixtureLibraryRepository.collections())
        override suspend fun history() = block("history")
        override suspend fun playlists() = FixtureLibraryRepository.collections()
    }

    private fun recordingRepository(loads: MutableList<String>) = object : LibraryRepository {
        override suspend fun you(): com.hhst.youtubelite.library.domain.YouPage { loads += "you"; return YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList()) }
        override suspend fun history(): HistoryPage { loads += "history"; return HistoryPage(emptyList()) }
        override suspend fun playlists(): List<com.hhst.youtubelite.playlist.domain.PlaylistSummary> { loads += "playlists"; return emptyList() }
    }

    @Test fun submittedSearchAndOpenOverlayRestoreWithoutExposingSignedOutContent() = runTest {
        val handle = SavedStateHandle()
        val vm = YouViewModel(FixtureLibraryRepository(), handle, LibraryScenario.Content, true)
        vm.onAction(LibraryAction.SubmitSearch("open movies"))
        vm.onAction(LibraryAction.OpenSearch)
        val restored = YouViewModel(FixtureLibraryRepository(), handle, LibraryScenario.Content, true)
        assertEquals("open movies", restored.uiState.value.query)
        assertEquals(LibraryOverlay.Search, restored.uiState.value.overlay)
        val loads = mutableListOf<String>()
        val anonymous = YouViewModel(recordingRepository(loads), SavedStateHandle(), LibraryScenario.Content, false)
        anonymous.onAction(LibraryAction.Retry)
        advanceUntilIdle()
        assertTrue(anonymous.uiState.value.content is LibraryContent.SignedOut)
        assertTrue(loads.isEmpty())
    }

    private class LiveLibraryRepository(
        var entries: List<HistoryEntry>,
        var removeError: Exception? = null,
    ) : LibraryRepository {
        val removed = ArrayList<String>()
        var paused: Boolean? = null
        var cleared = 0
        override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), entries, FixtureLibraryRepository.collections())
        override suspend fun history() = HistoryPage(entries)
        override suspend fun playlists() = FixtureLibraryRepository.collections()
        override suspend fun removeFromHistory(feedbackToken: String) {
            removeError?.let { throw it }
            removed += feedbackToken
        }
        override suspend fun setHistoryPaused(paused: Boolean) {
            removeError?.let { throw it }
            this.paused = paused
        }
        override suspend fun clearHistory() {
            removeError?.let { throw it }
            cleared++
            entries = emptyList()
        }
    }

    private class FakeSaveRepository : com.hhst.youtubelite.saves.domain.SaveRepository {
        var watchLaterCalls = 0
        override suspend fun choices(videoId: String) =
            listOf(com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun saveWatchLater(videoId: String) { watchLaterCalls++ }
        override suspend fun create(title: String, description: String, videoId: String) =
            com.hhst.youtubelite.saves.domain.SaveChoice("PLnew", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }

    private fun historyEntry(videoId: String, token: String? = null) =
        HistoryEntry(videoId, videoId, "Title $videoId", "Author", "chan", "", "10:00", null, "History", removeToken = token)

    @Test fun liveHistoryMenuWatchLaterAndSaveRunThroughTheSaveSheet() = runTest {
        val saves = FakeSaveRepository()
        val vm = HistoryViewModel(LiveLibraryRepository(listOf(historyEntry("a", "tokA"))), SavedStateHandle(),
            LibraryScenario.Content, saveRepository = saves)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(1, saves.watchLaterCalls)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        assertEquals("a", vm.saveSheet?.state?.value?.videoId)
    }

    @Test fun liveRemoveFromHistoryWritesTheTokenAndDropsTheEntry() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a", "tokA"), historyEntry("b", "tokB")))
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertEquals(listOf("tokA"), repository.removed)
        val entries = (vm.uiState.value.content as HistoryContent.Ready).page.entries
        assertEquals(listOf("b"), entries.map { it.videoId })
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun unconfirmedRemovalKeepsTheEntryAndNotifies() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a", "tokA"))).apply {
            removeError = com.hhst.youtubelite.watch.domain.UnconfirmedActionException()
        }
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as HistoryContent.Ready).page.entries.size)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
    }

    @Test fun removalWithoutTokenNotifiesAndWritesNothing() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a")))
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertTrue(repository.removed.isEmpty())
        assertEquals(1, (vm.uiState.value.content as HistoryContent.Ready).page.entries.size)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
    }

    @Test fun manageHistoryPausesAndResumesThroughTheServer() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a", "tokA")))
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.live)
        vm.onAction(LibraryAction.OpenManageHistory)
        assertEquals(LibraryOverlay.ManageHistory, vm.uiState.value.overlay)
        vm.onAction(LibraryAction.PauseHistory)
        advanceUntilIdle()
        assertEquals(true, repository.paused)
        assertEquals(LibraryOverlay.None, vm.uiState.value.overlay)
        assertEquals(AppNotice.Message("Watch history paused."), vm.actionNotices.first())
        vm.onAction(LibraryAction.ResumeHistory)
        advanceUntilIdle()
        assertEquals(false, repository.paused)
    }

    @Test fun clearHistoryRequiresConfirmationAndRefreshes() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a", "tokA")))
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenManageHistory)
        vm.onAction(LibraryAction.RequestClearHistory)
        assertTrue(vm.uiState.value.confirmClear)
        assertEquals(0, repository.cleared)
        vm.onAction(LibraryAction.ConfirmClearHistory)
        advanceUntilIdle()
        assertEquals(1, repository.cleared)
        assertFalse(vm.uiState.value.confirmClear)
        assertEquals(LibraryOverlay.None, vm.uiState.value.overlay)
        assertTrue(vm.uiState.value.content is HistoryContent.Empty)
        assertEquals(AppNotice.Message("Watch history cleared."), vm.actionNotices.first())
    }

    @Test fun closeOverlayDisarmsTheClearConfirmation() = runTest {
        val vm = HistoryViewModel(LiveLibraryRepository(listOf(historyEntry("a"))), SavedStateHandle(),
            LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenManageHistory)
        vm.onAction(LibraryAction.RequestClearHistory)
        assertTrue(vm.uiState.value.confirmClear)
        vm.onAction(LibraryAction.CloseOverlay)
        assertFalse(vm.uiState.value.confirmClear)
        assertEquals(LibraryOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun accountChangesCloseOldLibrarySaveChoicesAndClearConfirmation() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a", "tokA")))
        val you = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, true, FakeSaveRepository())
        val history = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content,
            saveRepository = FakeSaveRepository())
        you.updateSession(true, 1)
        history.updateSession(true, 1)
        advanceUntilIdle()
        you.saveSheet!!.open("a")
        history.saveSheet!!.open("a")
        history.onAction(LibraryAction.OpenManageHistory)
        history.onAction(LibraryAction.RequestClearHistory)
        advanceUntilIdle()
        you.updateSession(true, 2)
        history.updateSession(true, 2)
        advanceUntilIdle()
        assertNull(you.saveSheet.state.value)
        assertNull(history.saveSheet.state.value)
        assertFalse(history.uiState.value.confirmClear)
        assertEquals(LibraryOverlay.None, history.uiState.value.overlay)
    }

    @Test fun youShelfMenuSavesAndRemovesThroughTheLiveSheet() = runTest {
        val repository = LiveLibraryRepository(listOf(historyEntry("a", "tokA"), historyEntry("b", "tokB")))
        val saves = FakeSaveRepository()
        val vm = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, signedIn = true, saveRepository = saves)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        assertEquals("a", vm.saveSheet?.state?.value?.videoId)
        vm.saveSheet?.close()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertEquals(listOf("tokA"), repository.removed)
        val history = (vm.uiState.value.content as LibraryContent.Ready).page.history
        assertEquals(listOf("b"), history.map { it.videoId })
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

}
