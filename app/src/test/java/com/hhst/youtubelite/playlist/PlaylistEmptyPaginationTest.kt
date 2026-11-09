package com.hhst.youtubelite.playlist

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.PlaylistViewModel
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
class PlaylistEmptyPaginationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun anEmptyFirstPageCanResumeAfterBoundedEmptyContinuations() = runTest {
        val base = FixturePlaylistRepository()
        var calls = 0
        var recover = false
        val repository = object : PlaylistRepository {
            override suspend fun playlist(id: String) = base.playlist(id)!!.copy(entries = emptyList(), nextKey = "page-1")
            override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage {
                calls++
                return PlaylistAppendPage(if (recover) FixturePlaylistRepository.entries() else emptyList(),
                    if (recover) null else "page-${calls + 1}")
            }
        }
        val model = PlaylistViewModel(repository, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        repeat(10) {
            if (model.uiState.value.append == AppendState.Idle) model.onAction(PlaylistAction.LoadNext)
            advanceUntilIdle()
        }
        assertEquals(3, calls)
        assertEquals(AppendState.Failed, model.uiState.value.append)
        recover = true
        model.onAction(PlaylistAction.LoadNext)
        advanceUntilIdle()
        assertEquals(4, calls)
        assertEquals(AppendState.Idle, model.uiState.value.append)
        val page = (model.uiState.value.content as PlaylistContent.Ready).page
        assertTrue(page.entries.isNotEmpty())
        assertNull(page.nextKey)
    }

    @Test fun batchEnumerationCannotLoopThroughUnboundedEmptyPages() = runTest {
        val base = FixturePlaylistRepository()
        var calls = 0
        val repository = object : PlaylistRepository {
            override suspend fun playlist(id: String) = base.playlist(id)!!.copy(nextKey = "page-1")
            override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage {
                check(++calls <= 4) { "Enumeration did not pause" }
                return PlaylistAppendPage(emptyList(), "page-${calls + 1}")
            }
        }
        val model = PlaylistViewModel(repository, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        model.onAction(PlaylistAction.PlayAll)
        advanceUntilIdle()
        assertEquals(3, calls)
        assertEquals(PlaylistEnumeration.Failed, model.uiState.value.enumeration)
        assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
    }
}
