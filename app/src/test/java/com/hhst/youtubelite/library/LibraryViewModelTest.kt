package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.data.FixtureHomeRepository
import com.hhst.youtubelite.home.domain.AppendState
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

    @Test fun queueIsAvailableWithoutAnAccount() = runTest {
        val vm = YouViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content, false)
        vm.onAction(LibraryAction.OpenQueue)
        assertEquals(LibraryNavigationRequest.OpenQueue, vm.navigationRequests.first())
    }

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

    @Test fun theAnonymousAccountMenuRoutesToSignInAndNeverToTheSwitcher() = runTest {
        val loads = mutableListOf<String>()
        val anonymous = YouViewModel(recordingRepository(loads), SavedStateHandle(), LibraryScenario.Content, signedIn = false)
        anonymous.onAction(LibraryAction.AccountSelected(com.hhst.youtubelite.account.domain.AccountMenuAction.SwitchAccount))
        assertEquals(LibraryNavigationRequest.SignIn, anonymous.navigationRequests.first())
        val signedIn = YouViewModel(FixtureLibraryRepository(LibraryScenario.Content), SavedStateHandle(), LibraryScenario.Content, signedIn = true)
        advanceUntilIdle()
        signedIn.onAction(LibraryAction.AccountSelected(com.hhst.youtubelite.account.domain.AccountMenuAction.SwitchAccount))
        assertEquals(LibraryNavigationRequest.SwitchAccount, signedIn.navigationRequests.first())
    }

    @Test fun deviceIdentityCanReturnToASavedAccount() = runTest {
        val repository = object : LibraryRepository by FixtureLibraryRepository(LibraryScenario.Content) {
            override val hasSavedAccounts = true
        }
        val vm = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, signedIn = false)
        advanceUntilIdle()
        vm.onAction(LibraryAction.AccountSelected(com.hhst.youtubelite.account.domain.AccountMenuAction.SwitchAccount))
        assertEquals(LibraryNavigationRequest.SwitchAccount, vm.navigationRequests.first())
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
        assertEquals("open movies", vm.uiState.value.query)
        vm.onAction(LibraryAction.OpenSearch)
        assertEquals("open movies", vm.uiState.value.query)
    }

    @Test fun aFailedYouReadShowsFailureAndRetryRecovers() = runTest {
        val repository = object : LibraryRepository {
            var failHistory = true
            override suspend fun you(): YouPage {
                if (failHistory) throw java.io.IOException("history offline")
                return YouPage(FixtureLibraryRepository.profile(), emptyList(), FixtureLibraryRepository.collections())
            }
            override suspend fun history() = error("unused")
            override suspend fun playlists() = PlaylistCollectionPage(FixtureLibraryRepository.collections())
        }
        val vm = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is LibraryContent.Failed)
        // An account-level failure fails the whole page; MediaLibraryRepository instead isolates shelf failures into YouPage flags.
        repository.failHistory = false
        vm.onAction(LibraryAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is LibraryContent.Ready)
    }

    @Test fun historyAppendsPagesAndKeepsRowsOnFailure() = runTest {
        var failNext = true
        val page1 = listOf(FixtureLibraryRepository.historyEntries().first())
        val page2 = listOf(FixtureLibraryRepository.historyEntries().last())
        val repository = object : LibraryRepository {
            override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), page1, emptyList())
            override suspend fun history() = HistoryPage(page1, "cursor-1")
            override suspend fun playlists() = PlaylistCollectionPage(emptyList())
            override suspend fun historyNext(cursor: String): HistoryPage {
                if (failNext) throw java.io.IOException("offline")
                return HistoryPage(page2, null)
            }
        }
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as HistoryContent.Ready).page.entries.size)
        vm.onAction(LibraryAction.LoadHistoryNext)
        advanceUntilIdle()
        assertEquals(AppendState.Failed, vm.uiState.value.append)
        assertEquals(1, (vm.uiState.value.content as HistoryContent.Ready).page.entries.size)
        failNext = false
        vm.onAction(LibraryAction.LoadHistoryNext)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as HistoryContent.Ready).page
        assertEquals(2, page.entries.size)
        assertNull(page.nextKey)
        assertEquals(AppendState.Idle, vm.uiState.value.append)
    }

    @Test fun collectionAppendsPagesAndACountSwitchResets() = runTest {
        val page1 = listOf(FixtureLibraryRepository.collections().first())
        val page2 = listOf(FixtureLibraryRepository.collections().last())
        val repository = object : LibraryRepository {
            override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList())
            override suspend fun history() = HistoryPage(emptyList())
            override suspend fun playlists() = PlaylistCollectionPage(page1, "cursor-1")
            override suspend fun playlistsNext(cursor: String) = PlaylistCollectionPage(page2, null)
        }
        val vm = CollectionViewModel(repository, SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as CollectionContent.Ready).playlists.size)
        vm.onAction(LibraryAction.LoadCollectionNext)
        advanceUntilIdle()
        assertEquals(2, (vm.uiState.value.content as CollectionContent.Ready).playlists.size)
        vm.updateSession(true, 1) // First snapshot only establishes the key.
        advanceUntilIdle()
        vm.updateSession(true, 2)
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as CollectionContent.Ready).playlists.size)
        assertEquals(AppendState.Idle, vm.uiState.value.append)
    }

    @Test fun youPageReportsFailedShelvesWithoutFailingTheAccount() = runTest {
        // Mirrors MediaLibraryRepository.you(): shelves fail independently of the account read.
        val page = YouPage(FixtureLibraryRepository.profile(), emptyList(), FixtureLibraryRepository.collections(),
            historyFailed = true, playlistsFailed = false)
        assertTrue(page.historyFailed)
        assertTrue(page.history.isEmpty())
        assertFalse(page.playlistsFailed)
        assertTrue(page.playlists.isNotEmpty())
    }

    private fun repository(block: suspend (String) -> HistoryPage) = object : LibraryRepository {
        override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), block("history").entries, FixtureLibraryRepository.collections())
        override suspend fun history() = block("history")
        override suspend fun playlists() = PlaylistCollectionPage(FixtureLibraryRepository.collections())
    }

    private fun recordingRepository(loads: MutableList<String>) = object : LibraryRepository {
        override suspend fun you(): com.hhst.youtubelite.library.domain.YouPage { loads += "you"; return YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList()) }
        override suspend fun history(): HistoryPage { loads += "history"; return HistoryPage(emptyList()) }
        override suspend fun playlists(): com.hhst.youtubelite.library.domain.PlaylistCollectionPage { loads += "playlists"; return com.hhst.youtubelite.library.domain.PlaylistCollectionPage(emptyList()) }
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
        var removalRecord: com.hhst.youtubelite.library.domain.RemovedHistoryEntry? = null
        var restored: com.hhst.youtubelite.library.domain.RemovedHistoryEntry? = null
        override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), entries, FixtureLibraryRepository.collections())
        override suspend fun history() = HistoryPage(entries)
        override suspend fun playlists() = PlaylistCollectionPage(FixtureLibraryRepository.collections())
        override suspend fun removeFromHistory(feedbackToken: String): com.hhst.youtubelite.library.domain.RemovedHistoryEntry? {
            removeError?.let { throw it }
            removed += feedbackToken
            entries = entries.filterNot { it.removeToken == feedbackToken }
            return removalRecord
        }
        override suspend fun restoreHistoryEntry(removed: com.hhst.youtubelite.library.domain.RemovedHistoryEntry) {
            restored = removed
            entries = listOf(removed.entry) + entries
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

    @Test fun deviceLaneRemovalSurfacesUndoAndRestoreReloadsTheStore() = runTest {
        val entry = historyEntry("a", "tokA")
        val repository = LiveLibraryRepository(listOf(entry, historyEntry("b", "tokB"))).apply {
            removalRecord = com.hhst.youtubelite.library.domain.RemovedHistoryEntry(entry, null, "b", 0)
        }
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertEquals("tokA", vm.uiState.value.undo?.entry?.removeToken)
        vm.onAction(LibraryAction.UndoHistoryRemove)
        advanceUntilIdle()
        assertEquals("tokA", repository.restored?.entry?.removeToken)
        assertNull(vm.uiState.value.undo)
        val reloaded = (vm.uiState.value.content as HistoryContent.Ready).page.entries
        assertEquals(listOf("a", "b"), reloaded.map { it.videoId })
        vm.onAction(LibraryAction.OpenMenu("b"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        vm.onAction(LibraryAction.DismissUndo)
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertEquals("tokA", repository.restored?.entry?.removeToken)
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

    private fun identityRepository(signedIn: () -> Boolean) = object : LibraryRepository {
        override val supportsLocal get() = true
        val youReads = ArrayList<Boolean>()
        override suspend fun you(): YouPage {
            youReads += signedIn()
            return if (!signedIn())
                YouPage(AccountProfile("On this device", "Local history and playlists", "", ""), emptyList(), emptyList())
            else YouPage(FixtureLibraryRepository.profile(), emptyList(), FixtureLibraryRepository.collections())
        }
        override suspend fun history() = HistoryPage(emptyList())
        override suspend fun playlists() = PlaylistCollectionPage(emptyList())
    }

    private class PagedHistoryRepository : LibraryRepository {
        override val supportsLocal get() = true
        var firstReads = 0
        override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList())
        override suspend fun history(): HistoryPage {
            firstReads++
            return HistoryPage(listOf(HistoryEntry("h1", "h1", "One", "A", "c", "a", "1:00", section = "Today")), nextKey = "k1")
        }
        override suspend fun historyNext(cursor: String) =
            HistoryPage(listOf(HistoryEntry("h2", "h2", "Two", "A", "c", "a", "1:00",section = "Today")))
        override suspend fun playlists() = PlaylistCollectionPage(emptyList())
    }

    @Test fun watchLaterExitFollowsTheSessionIdentity() = runTest {
        var signedIn = true
        val repository = identityRepository { signedIn }
        val vm = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, true)
        val requests = mutableListOf<LibraryNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        advanceUntilIdle()
        assertEquals("Litube Studio", (vm.uiState.value.content as LibraryContent.Ready).page.account.name)
        vm.onAction(LibraryAction.OpenWatchLater)
        vm.onAction(LibraryAction.OpenLiked)
        advanceUntilIdle()
        assertEquals(listOf(LibraryNavigationRequest.Playlist("WL"), LibraryNavigationRequest.Playlist("LL")), requests)
        signedIn = false
        vm.updateSession(false, 7)
        advanceUntilIdle()
        assertEquals("On this device", (vm.uiState.value.content as LibraryContent.Ready).page.account.name)
        vm.onAction(LibraryAction.OpenWatchLater)
        // Liked has no device lane: it emits nothing while anonymous.
        vm.onAction(LibraryAction.OpenLiked)
        advanceUntilIdle()
        assertEquals(listOf(LibraryNavigationRequest.Playlist("WL"), LibraryNavigationRequest.Playlist("LL"),
            LibraryNavigationRequest.Playlist(com.hhst.youtubelite.data.media.LocalLibraryStore.WATCH_LATER)), requests)
    }

    @Test fun anIdentityChangeReloadsHistoryAndDropsTheStaleCursor() = runTest {
        val repository = PagedHistoryRepository()
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content, signedIn = true, saveRepository = null)
        advanceUntilIdle()
        vm.onAction(LibraryAction.LoadHistoryNext)
        advanceUntilIdle()
        var page = (vm.uiState.value.content as HistoryContent.Ready).page
        assertEquals(listOf("h1", "h2"), page.entries.map { it.id })
        assertNull(page.nextKey)

        vm.updateSession(false, 99)
        advanceUntilIdle()
        page = (vm.uiState.value.content as HistoryContent.Ready).page
        assertEquals(listOf("h1"), page.entries.map { it.id })
        assertEquals("k1", page.nextKey)
        assertEquals(2, repository.firstReads)
        assertEquals(AppendState.Idle, vm.uiState.value.append)
    }

    @Test fun signOutKeepsTheDevicePageBackedContent() = runTest {
        var signedIn = true
        val repository = identityRepository { signedIn }
        val vm = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, true)
        advanceUntilIdle()
        assertEquals("Litube Studio", (vm.uiState.value.content as LibraryContent.Ready).page.account.name)
        signedIn = false
        vm.updateSession(false, 7)
        advanceUntilIdle()
        assertEquals("On this device", (vm.uiState.value.content as LibraryContent.Ready).page.account.name)
    }

    @Test fun historyRefreshResetsAndCancelsAnInterruptedAppend() = runTest {
        val pending = kotlinx.coroutines.CompletableDeferred<HistoryPage>()
        val repository = object : LibraryRepository by FixtureLibraryRepository() {
            override suspend fun history() = HistoryPage(listOf(historyEntry("a", "token")), "next")
            override suspend fun historyNext(cursor: String) = pending.await()
        }
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        vm.onAction(LibraryAction.LoadHistoryNext)
        runCurrent()
        assertEquals(AppendState.Loading, vm.uiState.value.append)
        vm.onAction(LibraryAction.Retry)
        advanceUntilIdle()
        assertEquals(AppendState.Idle, vm.uiState.value.append)
        pending.complete(HistoryPage(emptyList()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HistoryContent.Ready)
        assertEquals("next", (vm.uiState.value.content as HistoryContent.Ready).page.nextKey)
    }

    @Test fun collectionRefreshResetsAndCancelsAnInterruptedAppend() = runTest {
        val pending = kotlinx.coroutines.CompletableDeferred<PlaylistCollectionPage>()
        val repository = object : LibraryRepository by FixtureLibraryRepository() {
            override suspend fun playlists() = PlaylistCollectionPage(FixtureLibraryRepository.collections(), "next")
            override suspend fun playlistsNext(cursor: String) = pending.await()
        }
        val vm = CollectionViewModel(repository, SavedStateHandle(), LibraryScenario.Content)
        advanceUntilIdle()
        vm.onAction(LibraryAction.LoadCollectionNext)
        runCurrent()
        assertEquals(AppendState.Loading, vm.uiState.value.append)
        vm.onAction(LibraryAction.Retry)
        advanceUntilIdle()
        assertEquals(AppendState.Idle, vm.uiState.value.append)
        pending.complete(PlaylistCollectionPage(emptyList()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is CollectionContent.Ready)
    }

    @Test fun historyUndoIsDiscardedWhenTheIdentityChanges() = runTest {
        val entry = historyEntry("a", "token")
        val live = LiveLibraryRepository(listOf(entry, historyEntry("b", "other"))).apply {
            removalRecord = RemovedHistoryEntry(entry, null, "b", 0)
        }
        val repository = object : LibraryRepository by live { override val supportsLocal = true }
        val vm = HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content,
            saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.undo)
        vm.updateSession(false, 41)
        advanceUntilIdle()
        vm.onAction(LibraryAction.UndoHistoryRemove)
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertNull(live.restored)
    }

    @Test fun youPageUndoIsDiscardedWhenTheAccountChanges() = runTest {
        val entry = historyEntry("a", "token")
        val repository = LiveLibraryRepository(listOf(entry, historyEntry("b", "other"))).apply {
            removalRecord = RemovedHistoryEntry(entry, null, "b", 0)
        }
        val vm = YouViewModel(repository, SavedStateHandle(), LibraryScenario.Content, true, FakeSaveRepository())
        vm.updateSession(true, 1)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.undo)
        vm.updateSession(true, 2)
        advanceUntilIdle()
        vm.onAction(LibraryAction.UndoHistoryRemove)
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertNull(repository.restored)
    }
}
