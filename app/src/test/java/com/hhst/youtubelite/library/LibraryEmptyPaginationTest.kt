package com.hhst.youtubelite.library

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.library.data.FixtureLibraryRepository
import com.hhst.youtubelite.library.data.LibraryRepository
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.CollectionViewModel
import com.hhst.youtubelite.library.presentation.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryEmptyPaginationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun emptyHistoryAndCollectionKeepTheirCursorsAndPauseUnproductiveAutomaticLoads() = runTest {
        for (history in listOf(true, false)) {
            var calls = 0
            var recover = false
            fun nextCursor(): String? { calls++; return if (recover) null else "page-${calls + 1}" }
            val base = FixtureLibraryRepository()
            val repository = object : LibraryRepository by base {
                override suspend fun history() = HistoryPage(emptyList(), "page-1")
                override suspend fun historyNext(cursor: String): HistoryPage {
                    val next = nextCursor()
                    return HistoryPage(if (recover) FixtureLibraryRepository.historyEntries() else emptyList(), next)
                }
                override suspend fun playlists() = PlaylistCollectionPage(emptyList(), "page-1")
                override suspend fun playlistsNext(cursor: String): PlaylistCollectionPage {
                    val next = nextCursor()
                    return PlaylistCollectionPage(if (recover) FixtureLibraryRepository.collections() else emptyList(), next)
                }
            }
            val historyModel = if (history) HistoryViewModel(repository, SavedStateHandle(), LibraryScenario.Content) else null
            val collectionModel = if (history) null else CollectionViewModel(repository, SavedStateHandle(), LibraryScenario.Content)
            advanceUntilIdle()
            if (history) assertTrue(historyModel!!.uiState.value.content is HistoryContent.Ready)
            else assertTrue(collectionModel!!.uiState.value.content is CollectionContent.Ready)
            fun append() = historyModel?.uiState?.value?.append ?: collectionModel!!.uiState.value.append
            fun load() {
                if (history) historyModel!!.onAction(LibraryAction.LoadHistoryNext)
                else collectionModel!!.onAction(LibraryAction.LoadCollectionNext)
            }
            repeat(10) {
                if (append() == AppendState.Idle) load()
                advanceUntilIdle()
            }
            assertEquals(3, calls)
            assertEquals(AppendState.Failed, append())
            recover = true
            load()
            advanceUntilIdle()
            assertEquals(4, calls)
            assertEquals(AppendState.Idle, append())
            if (history) {
                val page = (historyModel!!.uiState.value.content as HistoryContent.Ready).page
                assertTrue(page.entries.isNotEmpty())
                assertNull(page.nextKey)
            } else {
                val content = collectionModel!!.uiState.value.content as CollectionContent.Ready
                assertTrue(content.playlists.isNotEmpty())
                assertNull(content.nextKey)
            }
        }
    }
}
