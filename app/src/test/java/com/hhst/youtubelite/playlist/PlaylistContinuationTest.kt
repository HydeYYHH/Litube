package com.hhst.youtubelite.playlist

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.PlaylistViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistContinuationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun aLongerCursorLoopKeepsTheFinalPagesEntriesAndStopsReads() = runTest {
        val repository = PagedRepository()
        val model = model(repository)
        advanceUntilIdle()
        repeat(5) { model.onAction(PlaylistAction.LoadNext); advanceUntilIdle() }
        assertEquals(listOf("A", "B"), repository.calls)
        assertEquals(listOf("initial", "A", "B"), model.page().entries.map { it.id })
        assertNull(model.page().nextKey)
        assertEquals(AppendState.Idle, model.uiState.value.append)
    }

    @Test fun failedAppendsRemainRetryable() = runTest {
        val repository = PagedRepository()
        val model = model(repository)
        advanceUntilIdle()
        repository.failAppend = true
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals(AppendState.Failed, model.uiState.value.append)
        assertEquals("A", model.page().nextKey)
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals("B", model.page().nextKey)
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals(listOf("A", "A", "B"), repository.calls)
        assertEquals(listOf("initial", "A", "B"), model.page().entries.map { it.id })
        assertNull(model.page().nextKey)
    }

    @Test fun failedRefreshRetainsTheOldPagesCursorHistory() = runTest {
        val repository = PagedRepository()
        val model = model(repository)
        advanceUntilIdle()
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        val retained = model.page()
        repository.failRefresh = true
        model.onAction(PlaylistAction.Retry)
        advanceUntilIdle()
        assertEquals(retained, model.page())
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertNull(model.page().nextKey)
        assertEquals(listOf("A", "B"), repository.calls)
    }

    @Test fun successfulRefreshAllowsCursorsToBeReused() = runTest {
        val repository = PagedRepository()
        val model = model(repository)
        advanceUntilIdle()
        repeat(2) { model.onAction(PlaylistAction.LoadNext); advanceUntilIdle() }
        model.onAction(PlaylistAction.Retry)
        advanceUntilIdle()
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals("B", model.page().nextKey)
        assertEquals(listOf("A", "B", "A"), repository.calls)
    }

    @Test fun knownLoopsCannotTurnIntoSuccessfulBatchActions() = runTest {
        for (immediateLoop in listOf(true, false)) {
            val repository = PagedRepository().apply { immediate = immediateLoop }
            val model = model(repository)
            advanceUntilIdle()
            repeat(2) { model.onAction(PlaylistAction.LoadNext); advanceUntilIdle() }
            assertNull(model.page().nextKey)
            for (action in listOf(PlaylistAction.PlayAll, PlaylistAction.Shuffle, PlaylistAction.Download)) {
                model.onAction(action)
                advanceUntilIdle()
                assertEquals(PlaylistEnumeration.Failed, model.uiState.value.enumeration)
                assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
            }
        }
    }

    @Test fun failedRefreshCannotMakeAnIncompletePlaylistSafeForBatchActions() = runTest {
        val repository = PagedRepository()
        val model = model(repository)
        advanceUntilIdle()
        repeat(2) { model.onAction(PlaylistAction.LoadNext); advanceUntilIdle() }
        repository.failRefresh = true
        model.onAction(PlaylistAction.Retry)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        advanceUntilIdle()
        assertEquals(PlaylistEnumeration.Failed, model.uiState.value.enumeration)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
        repository.complete = true
        model.onAction(PlaylistAction.Retry)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        advanceUntilIdle()
        assertEquals(PlaylistEnumeration.Idle, model.uiState.value.enumeration)
        assertTrue(model.navigationRequests.first() is PlaylistNavigationRequest.Queue)
    }

    private fun model(repository: PlaylistRepository) = PlaylistViewModel(
        repository, SavedStateHandle(), FixturePlaylistRepository.MOVIES,
    )
    private fun PlaylistViewModel.page() = (uiState.value.content as PlaylistContent.Ready).page

    private class PagedRepository : PlaylistRepository {
        val calls = mutableListOf<String>()
        var failAppend = false
        var failRefresh = false
        var immediate = false
        var complete = false
        override suspend fun playlist(id: String): PlaylistPage {
            if (failRefresh) { failRefresh = false; throw IOException("temporary refresh failure") }
            return FixturePlaylistRepository().playlist(id)!!.copy(
                entries = listOf(FixturePlaylistRepository.entries().first().copy(id = "initial")),
                nextKey = if (complete) null else "A",
            )
        }
        override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage {
            calls += cursor
            if (failAppend) { failAppend = false; throw IOException("temporary append failure") }
            return PlaylistAppendPage(
                listOf(FixturePlaylistRepository.entries().first().copy(id = cursor)),
                if (immediate || cursor == "B") "A" else "B",
            )
        }
    }
}
