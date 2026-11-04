package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.library.data.*
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.*
import com.hhst.youtubelite.saves.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryWriteOrderingTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun entry(id: String) = HistoryEntry(id, id, id, "Author", "channel", "", "1:00", section = "History", removeToken = id)
    private val saves = object : SaveRepository {
        override suspend fun choices(videoId: String) = emptyList<SaveChoice>()
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun saveWatchLater(videoId: String) = Unit
        override suspend fun create(title: String, description: String, videoId: String) = SaveChoice("p", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }
    private inner class Repository : LibraryRepository {
        val write = CompletableDeferred<Unit>()
        var rows = listOf(entry("a"), entry("b"))
        var reads = 0
        override suspend fun history(): HistoryPage { reads++; return HistoryPage(rows) }
        override suspend fun you(): YouPage { reads++; return YouPage(FixtureLibraryRepository.profile(), rows, emptyList()) }
        override suspend fun playlists() = PlaylistCollectionPage(emptyList())
        override suspend fun removeFromHistory(feedbackToken: String): RemovedHistoryEntry {
            write.await()
            val removed = rows.first { it.videoId == feedbackToken }
            rows = rows.filterNot { it.videoId == feedbackToken }
            return RemovedHistoryEntry(removed, "a", null, 1)
        }
        override suspend fun clearHistory() { write.await(); rows = emptyList() }
    }

    @Test fun historyRefreshWaitsForRemovalAndRetainsUndo() = runTest {
        val repo = Repository()
        val vm = HistoryViewModel(repo, SavedStateHandle(), LibraryScenario.Content, saveRepository = saves)
        runCurrent()
        vm.onAction(LibraryAction.OpenMenu("b"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        runCurrent()
        vm.onAction(LibraryAction.Retry)
        runCurrent()
        val readsWhileWriting = repo.reads
        repo.write.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, readsWhileWriting)
        assertEquals(listOf("a"), (vm.uiState.value.content as HistoryContent.Ready).page.entries.map { it.videoId })
        assertNotNull(vm.uiState.value.undo)
    }

    @Test fun youRefreshWaitsForRemovalAndRetainsUndo() = runTest {
        val repo = Repository()
        val vm = YouViewModel(repo, SavedStateHandle(), LibraryScenario.Content, true, saves)
        runCurrent()
        vm.onAction(LibraryAction.OpenMenu("b"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        runCurrent()
        vm.onAction(LibraryAction.Retry)
        runCurrent()
        val readsWhileWriting = repo.reads
        repo.write.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, readsWhileWriting)
        assertEquals(listOf("a"), (vm.uiState.value.content as LibraryContent.Ready).page.history.map { it.videoId })
        assertNotNull(vm.uiState.value.undo)
    }

    @Test fun refreshDuringClearCannotRestoreTheClearedRows() = runTest {
        val repo = Repository()
        val vm = HistoryViewModel(repo, SavedStateHandle(), LibraryScenario.Content, saveRepository = saves)
        runCurrent()
        vm.onAction(LibraryAction.RequestClearHistory)
        vm.onAction(LibraryAction.ConfirmClearHistory)
        runCurrent()
        vm.onAction(LibraryAction.Retry)
        runCurrent()
        repo.write.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HistoryContent.Empty)
    }

    @Test fun removalWaitsForTheEarlierHistoryAppendAndPreservesItsCursor() = runTest {
        val append = CompletableDeferred<Unit>()
        var removals = 0
        val repo = object : LibraryRepository {
            override suspend fun history() = HistoryPage(listOf(entry("a"), entry("b")), "next")
            override suspend fun historyNext(cursor: String): HistoryPage {
                append.await()
                return HistoryPage(listOf(entry("b"), entry("c")), "third")
            }
            override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList())
            override suspend fun playlists() = PlaylistCollectionPage(emptyList())
            override suspend fun removeFromHistory(feedbackToken: String): RemovedHistoryEntry? { removals++; return null }
        }
        val vm = HistoryViewModel(repo, SavedStateHandle(), LibraryScenario.Content, saveRepository = saves)
        runCurrent()
        vm.onAction(LibraryAction.LoadHistoryNext)
        runCurrent()
        vm.onAction(LibraryAction.OpenMenu("b"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        runCurrent()
        val removalsBeforeAppend = removals
        append.complete(Unit)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as HistoryContent.Ready).page
        assertEquals(0, removalsBeforeAppend)
        assertEquals(listOf("a", "c"), page.entries.map { it.videoId })
        assertEquals("third", page.nextKey)
    }

    @Test fun accountResetStartsItsReadBeforeTheOldNonCancellableMutationExits() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val repo = object : LibraryRepository {
            override suspend fun history(): HistoryPage { reads++; return HistoryPage(listOf(entry("a"))) }
            override suspend fun you() = YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList())
            override suspend fun playlists() = PlaylistCollectionPage(emptyList())
            override suspend fun removeFromHistory(feedbackToken: String): RemovedHistoryEntry? {
                withContext(NonCancellable) { old.await(); throw com.hhst.youtubelite.watch.domain.SignInRequiredException() }
            }
        }
        val vm = HistoryViewModel(repo, SavedStateHandle(), LibraryScenario.Content, saveRepository = saves)
        vm.updateSession(true, 7)
        val notices = mutableListOf<com.hhst.youtubelite.core.AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.toList(notices) }
        runCurrent()
        vm.onAction(LibraryAction.OpenMenu("a"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        runCurrent()
        vm.onAction(LibraryAction.Retry)
        runCurrent()
        vm.updateSession(true, 8)
        runCurrent()
        val readsBeforeOldFailure = reads
        val busyBeforeOldFailure = vm.writes.pending.value.isNotEmpty()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, readsBeforeOldFailure)
        assertFalse(busyBeforeOldFailure)
        assertEquals(listOf("a"), (vm.uiState.value.content as HistoryContent.Ready).page.entries.map { it.videoId })
        assertNull(vm.uiState.value.undo)
        assertTrue(notices.isEmpty())
    }
}
