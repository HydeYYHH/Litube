package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.CollectionViewModel
import com.hhst.youtubelite.library.presentation.HistoryViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryContinuationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun historyAndCollectionsStopLongerLoopsAfterKeepingTheLastPagesRows() = runTest {
        for (history in listOf(true, false)) {
            val screen = Screen(history)
            advanceUntilIdle()
            repeat(5) { screen.loadNext(); advanceUntilIdle() }
            assertEquals(listOf("A", "B"), screen.calls)
            assertEquals(listOf("initial", "A", "B"), screen.ids())
            assertNull(screen.nextKey())
            assertEquals(AppendState.Idle, screen.append())
        }
    }

    @Test fun aFailedContinuationCanBeRetriedWithoutConsumingItsCursor() = runTest {
        for (history in listOf(true, false)) {
            val screen = Screen(history)
            advanceUntilIdle()
            screen.failNext = true
            screen.loadNext()
            advanceUntilIdle()
            assertEquals(AppendState.Failed, screen.append())
            assertEquals("A", screen.nextKey())
            assertEquals(listOf("initial"), screen.ids())
            screen.loadNext()
            advanceUntilIdle()
            assertEquals("B", screen.nextKey())
            screen.loadNext()
            advanceUntilIdle()
            assertEquals(listOf("A", "A", "B"), screen.calls)
            assertEquals(listOf("initial", "A", "B"), screen.ids())
            assertNull(screen.nextKey())
        }
    }

    @Test fun refreshingStartsANewCursorHistory() = runTest {
        for (history in listOf(true, false)) {
            val screen = Screen(history)
            advanceUntilIdle()
            screen.loadNext()
            advanceUntilIdle()
            screen.refresh()
            advanceUntilIdle()
            screen.loadNext()
            advanceUntilIdle()
            assertEquals("B", screen.nextKey())
            screen.loadNext()
            advanceUntilIdle()
            assertEquals(listOf("A", "A", "B"), screen.calls)
            assertEquals(listOf("initial", "A", "B"), screen.ids())
            assertNull(screen.nextKey())
        }
    }

    private class Screen(history: Boolean) {
        val calls = mutableListOf<String>()
        var failNext = false
        private suspend fun next(cursor: String): String {
            calls += cursor
            if (failNext) { failNext = false; throw IOException("temporary failure") }
            return if (cursor == "A") "B" else "A"
        }
        private val repository = object : LibraryRepository by FixtureLibraryRepository() {
            override suspend fun history() = HistoryPage(
                listOf(FixtureLibraryRepository.historyEntries().first().copy(id = "initial")), "A")
            override suspend fun historyNext(cursor: String) = HistoryPage(
                listOf(FixtureLibraryRepository.historyEntries().first().copy(id = cursor)), next(cursor))
            override suspend fun playlists() = PlaylistCollectionPage(
                listOf(FixtureLibraryRepository.collections().first().copy(id = "initial")), "A")
            override suspend fun playlistsNext(cursor: String) = PlaylistCollectionPage(
                listOf(FixtureLibraryRepository.collections().first().copy(id = cursor)), next(cursor))
        }
        private val historyModel = if (history) HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content) else null
        private val collectionModel = if (history) null else CollectionViewModel(repository, SavedStateHandle(), LibraryScenario.Content)

        fun loadNext() {
            historyModel?.onAction(LibraryAction.LoadHistoryNext)
            collectionModel?.onAction(LibraryAction.LoadCollectionNext)
        }
        fun refresh() {
            historyModel?.onAction(LibraryAction.Retry)
            collectionModel?.onAction(LibraryAction.Retry)
        }
        fun append() = historyModel?.uiState?.value?.append ?: collectionModel!!.uiState.value.append
        fun ids(): List<String> = historyModel?.let { (it.uiState.value.content as HistoryContent.Ready).page.entries.map { it.id } }
            ?: (collectionModel!!.uiState.value.content as CollectionContent.Ready).playlists.map { it.id }
        fun nextKey(): String? = if (historyModel != null) (historyModel.uiState.value.content as HistoryContent.Ready).page.nextKey
            else (collectionModel!!.uiState.value.content as CollectionContent.Ready).nextKey
    }
}
