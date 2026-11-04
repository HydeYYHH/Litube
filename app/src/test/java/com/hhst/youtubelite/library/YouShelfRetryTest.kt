package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.YouViewModel
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouShelfRetryTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Repository : LibraryRepository {
        var youReads = 0
        var historyReads = 0
        var playlistReads = 0
        val initial = YouPage(FixtureLibraryRepository.profile(), emptyList(), emptyList(), true, true)
        val refreshed = YouPage(initial.account.copy(name = "New account"), FixtureLibraryRepository.historyEntries(), FixtureLibraryRepository.collections())
        var historyRead: suspend () -> HistoryPage = { HistoryPage(FixtureLibraryRepository.historyEntries()) }
        var playlistsRead: suspend () -> PlaylistCollectionPage = { PlaylistCollectionPage(FixtureLibraryRepository.collections()) }
        override suspend fun you() = if (++youReads == 1) initial else refreshed
        override suspend fun history(): HistoryPage { historyReads++; return historyRead() }
        override suspend fun playlists(): PlaylistCollectionPage { playlistReads++; return playlistsRead() }
    }

    private fun ready(vm: YouViewModel) = (vm.uiState.value.content as LibraryContent.Ready).page
    private fun model(repo: Repository) = YouViewModel(repo, SavedStateHandle(), LibraryScenario.Content, true)

    @Test fun eachShelfRetriesOnlyItsReadAndCoalescesRepeatedTaps() = runTest {
        for (history in listOf(true, false)) {
            val repo = Repository()
            val gate = CompletableDeferred<Unit>()
            if (history) repo.historyRead = { gate.await(); HistoryPage(FixtureLibraryRepository.historyEntries()) }
            else repo.playlistsRead = { gate.await(); PlaylistCollectionPage(FixtureLibraryRepository.collections()) }
            val vm = model(repo)
            advanceUntilIdle()
            val before = ready(vm)
            val action = if (history) LibraryAction.RetryHistoryShelf else LibraryAction.RetryPlaylistsShelf
            repeat(5) { vm.onAction(action) }
            runCurrent()
            assertEquals(1, repo.youReads)
            assertEquals(if (history) 1 else 0, repo.historyReads)
            assertEquals(if (history) 0 else 1, repo.playlistReads)
            assertEquals(history, vm.uiState.value.historyRetrying)
            assertEquals(!history, vm.uiState.value.playlistsRetrying)
            assertEquals(before, ready(vm))
            gate.complete(Unit)
            advanceUntilIdle()
            val after = ready(vm)
            assertEquals(before.account, after.account)
            assertEquals(!history, after.historyFailed)
            assertEquals(history, after.playlistsFailed)
            assertFalse(vm.uiState.value.historyRetrying)
            assertFalse(vm.uiState.value.playlistsRetrying)
            vm.onAction(action)
            advanceUntilIdle()
            assertEquals(1, repo.historyReads + repo.playlistReads)
        }
    }

    @Test fun aShelfFailureDoesNotCancelOrOverwriteTheOtherShelf() = runTest {
        val repo = Repository()
        repo.historyRead = { throw IOException("history rejected") }
        val vm = model(repo)
        advanceUntilIdle()
        vm.onAction(LibraryAction.RetryHistoryShelf)
        vm.onAction(LibraryAction.RetryPlaylistsShelf)
        advanceUntilIdle()
        assertTrue(ready(vm).historyFailed)
        assertFalse(ready(vm).playlistsFailed)
        assertEquals(FixtureLibraryRepository.collections(), ready(vm).playlists)
        assertFalse(vm.uiState.value.historyRetrying)
        repo.historyRead = { HistoryPage(FixtureLibraryRepository.historyEntries()) }
        vm.onAction(LibraryAction.RetryHistoryShelf)
        advanceUntilIdle()
        assertFalse(ready(vm).historyFailed)
        assertEquals(FixtureLibraryRepository.collections(), ready(vm).playlists)
        assertEquals(1, repo.youReads)
        assertEquals(2, repo.historyReads)
        assertEquals(1, repo.playlistReads)
    }

    @Test fun refreshAndIdentitySwitchCancelShelfReadsAndRejectLateResults() = runTest {
        for (switchIdentity in listOf(false, true)) {
            val repo = Repository()
            val gate = CompletableDeferred<Unit>()
            var cancelled = false
            repo.historyRead = {
                try { withContext(NonCancellable) { gate.await() }; HistoryPage(emptyList()) }
                finally { cancelled = !currentCoroutineContext().isActive }
            }
            val vm = model(repo)
            vm.updateSession(true, 1)
            advanceUntilIdle()
            vm.onAction(LibraryAction.RetryHistoryShelf)
            runCurrent()
            if (switchIdentity) vm.updateSession(true, 2) else vm.onAction(LibraryAction.Retry)
            runCurrent()
            assertEquals(repo.refreshed, ready(vm))
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(cancelled)
            assertEquals(repo.refreshed, ready(vm))
            assertFalse(vm.uiState.value.historyRetrying)
            assertEquals(2, repo.youReads)
            assertEquals(1, repo.historyReads)
        }
    }
}
