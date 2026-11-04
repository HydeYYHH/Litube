package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.library.data.*
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryWriteContinuityTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun entry(id: String) = HistoryEntry(id, id, id, "Author", "channel", "", "1:00", section = "Today", removeToken = id)
    private val saves = object : com.hhst.youtubelite.saves.domain.SaveRepository {
        override suspend fun choices(videoId: String) = emptyList<com.hhst.youtubelite.saves.domain.SaveChoice>()
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun saveWatchLater(videoId: String) = Unit
        override suspend fun create(title: String, description: String, videoId: String) =
            com.hhst.youtubelite.saves.domain.SaveChoice("new", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }
    private inner class Repository : LibraryRepository {
        var reads = 0
        var restoresFail = false
        override suspend fun history(): HistoryPage { reads++; return HistoryPage(listOf(entry("a"), entry("b")), "next") }
        override suspend fun historyNext(cursor: String) = HistoryPage(listOf(entry("c")), "third")
        override suspend fun you(): YouPage { reads++; return YouPage(FixtureLibraryRepository.profile(), listOf(entry("a"), entry("b")), emptyList()) }
        override suspend fun playlists() = PlaylistCollectionPage(emptyList())
        override suspend fun removeFromHistory(feedbackToken: String) = RemovedHistoryEntry(entry(feedbackToken), "a", "c", 1)
        override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry) { if (restoresFail) error("offline") }
        override suspend fun clearHistory() = Unit
    }

    @Test fun undoRestoresTheRowBetweenItsNeighboursWithoutReloadingOrLosingLaterPages() = runTest {
        val repo = Repository()
        val vm = HistoryViewModel(repo, SavedStateHandle(), LibraryScenario.Content, saveRepository = saves)
        advanceUntilIdle()
        vm.onAction(LibraryAction.LoadHistoryNext)
        advanceUntilIdle()
        vm.onAction(LibraryAction.OpenMenu("b"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), (vm.uiState.value.content as HistoryContent.Ready).page.entries.map { it.videoId })
        repo.restoresFail = true
        vm.onAction(LibraryAction.UndoHistoryRemove)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.undo)
        repo.restoresFail = false
        vm.onAction(LibraryAction.UndoHistoryRemove)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as HistoryContent.Ready).page
        assertEquals(listOf("a", "b", "c"), page.entries.map { it.videoId })
        assertEquals("third", page.nextKey)
        assertEquals(1, repo.reads)
        assertNull(vm.uiState.value.undo)
        vm.onAction(LibraryAction.RequestClearHistory)
        vm.onAction(LibraryAction.ConfirmClearHistory)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HistoryContent.Empty)
        assertEquals(1, repo.reads)
    }

    @Test fun undoOnTheYouPageKeepsTheExistingShelvesAndDoesNotReadTheWholePage() = runTest {
        val repo = Repository()
        val vm = YouViewModel(repo, SavedStateHandle(), LibraryScenario.Content, true, saves)
        advanceUntilIdle()
        val before = (vm.uiState.value.content as LibraryContent.Ready).page
        vm.onAction(LibraryAction.OpenMenu("b"))
        vm.onAction(LibraryAction.MenuSelected(HistoryMenuAction.RemoveFromHistory))
        advanceUntilIdle()
        assertEquals(listOf("a"), (vm.uiState.value.content as LibraryContent.Ready).page.history.map { it.videoId })
        vm.onAction(LibraryAction.UndoHistoryRemove)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as LibraryContent.Ready).page
        assertEquals(before, page)
        assertSame(before.playlists, page.playlists)
        assertEquals(1, repo.reads)
    }
}
