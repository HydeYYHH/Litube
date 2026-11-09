package com.hhst.youtubelite.ui

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.PlaylistViewModel
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshFailureContinuityViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun searchRefreshFailureKeepsResultsButNewQueriesAndInitialFailuresDoNot() = runTest {
        var fail = false
        var title = "Original"
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters): SearchPage {
                if (fail) error("offline")
                return SearchPage(query, listOf(SearchResult("video", title, "Author", "channel", "1 view", "today", "1:00", "bunny")))
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "query")
        advanceUntilIdle()
        val painted = vm.uiState.value.content
        fail = true
        vm.onAction(SearchAction.Retry)
        assertEquals(SearchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(painted, vm.uiState.value.content)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
        fail = false
        title = "Updated"
        vm.onAction(SearchAction.Retry)
        advanceUntilIdle()
        assertEquals("Updated", (vm.uiState.value.content as SearchContent.Results).page.results.single().title)
        fail = true
        vm.updateQuery("different")
        advanceUntilIdle()
        assertEquals(SearchContent.Failed, vm.uiState.value.content)
        val fresh = SearchResultsViewModel(repository, SavedStateHandle(), "query")
        advanceUntilIdle()
        assertEquals(SearchContent.Failed, fresh.uiState.value.content)
    }

    @Test fun playlistRefreshFailureKeepsPageButAccountChangesAndInitialFailuresDoNot() = runTest {
        var fail = false
        val base = FixturePlaylistRepository()
        val repository = object : PlaylistRepository by base {
            override suspend fun playlist(id: String): PlaylistPage? {
                if (fail) error("offline")
                return base.playlist(id)
            }
        }
        val vm = PlaylistViewModel(repository, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        vm.updateSession(true, 1L)
        advanceUntilIdle()
        val painted = vm.uiState.value.content
        fail = true
        vm.onAction(PlaylistAction.Retry)
        assertEquals(PlaylistContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(painted, vm.uiState.value.content)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
        fail = false
        vm.onAction(PlaylistAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is PlaylistContent.Ready)
        fail = true
        vm.updateSession(false, 2L)
        advanceUntilIdle()
        assertEquals(PlaylistContent.Failed, vm.uiState.value.content)
        val fresh = PlaylistViewModel(repository, SavedStateHandle(), FixturePlaylistRepository.MOVIES)
        advanceUntilIdle()
        assertEquals(PlaylistContent.Failed, fresh.uiState.value.content)
    }

    @Test fun channelRefreshFailureKeepsPageButAccountChangesAndInitialFailuresDoNot() = runTest {
        var fail = false
        val base = FixtureChannelRepository()
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String): ChannelPage {
                if (fail) error("offline")
                return base.channel(channelId)
            }
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        val painted = vm.uiState.value.content
        fail = true
        vm.onAction(ChannelAction.Retry)
        assertEquals(ChannelContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(painted, vm.uiState.value.content)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
        fail = false
        vm.onAction(ChannelAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
        fail = true
        vm.refreshSession()
        advanceUntilIdle()
        assertEquals(ChannelContent.Failed, vm.uiState.value.content)
        val fresh = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        assertEquals(ChannelContent.Failed, fresh.uiState.value.content)
    }

    @Test fun channelSectionRefreshFailureKeepsRowsAndCursorButAnInitialFailureDoesNot() = runTest {
        var fail = false
        val base = FixtureChannelRepository()
        val repository = object : ChannelRepository by base {
            override suspend fun shorts(channelId: String): ChannelShortsPage {
                if (fail) error("offline")
                return base.shorts(channelId).copy(nextKey = "next")
            }
            override suspend fun shorts(channelId: String, opened: ChannelPage) = shorts(channelId)
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Shorts))
        advanceUntilIdle()
        val painted = vm.uiState.value.shorts
        assertTrue(painted is ChannelSection.Ready)
        fail = true
        vm.onAction(ChannelAction.RetrySection(ChannelTab.Shorts))
        assertEquals(ChannelSection.Loading, vm.uiState.value.shorts)
        advanceUntilIdle()
        assertEquals(painted, vm.uiState.value.shorts)
        assertEquals("next", vm.uiState.value.shortsNextKey)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
        val fresh = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        fresh.onAction(ChannelAction.SelectTab(ChannelTab.Shorts))
        advanceUntilIdle()
        assertEquals(ChannelSection.Failed, fresh.uiState.value.shorts)
    }

    @Test fun channelSearchRefreshFailureKeepsRowsAndCursorButANewQueryDoesNot() = runTest {
        var fail = false
        val base = FixtureChannelRepository()
        val repository = object : ChannelRepository by base {
            override val serverSearch = true
            override suspend fun searchVideos(channelId: String, query: String): ChannelSearchResults {
                if (fail) error("offline")
                return ChannelSearchResults(FixtureChannelRepository.sampleVideos(), "next")
            }
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.QueryChanged("query"))
        vm.onAction(ChannelAction.SubmitSearch)
        advanceUntilIdle()
        val painted = vm.uiState.value.searchResults
        assertTrue(painted is ChannelSection.Ready)
        fail = true
        vm.onAction(ChannelAction.RetrySearch)
        assertEquals(ChannelSection.Loading, vm.uiState.value.searchResults)
        advanceUntilIdle()
        assertEquals(painted, vm.uiState.value.searchResults)
        assertEquals("next", vm.uiState.value.searchNextKey)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
        vm.onAction(ChannelAction.QueryChanged("different"))
        vm.onAction(ChannelAction.SubmitSearch)
        advanceUntilIdle()
        assertEquals(ChannelSection.Failed, vm.uiState.value.searchResults)
    }

    @Test fun channelWholeRefreshFailureKeepsTheOpenedShortsAndItsCursor() = runTest {
        var failPage = false
        val base = FixtureChannelRepository()
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String): ChannelPage {
                if (failPage) error("offline")
                return base.channel(channelId)
            }
            override suspend fun shorts(channelId: String) = base.shorts(channelId).copy(nextKey = "next")
            override suspend fun shorts(channelId: String, opened: ChannelPage) = shorts(channelId)
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Shorts))
        advanceUntilIdle()
        val page = vm.uiState.value.content
        val shorts = vm.uiState.value.shorts
        assertTrue(shorts is ChannelSection.Ready)
        failPage = true
        vm.onAction(ChannelAction.Retry)
        advanceUntilIdle()
        assertEquals(page, vm.uiState.value.content)
        assertEquals(ChannelTab.Shorts, vm.uiState.value.selectedTab)
        assertEquals(shorts, vm.uiState.value.shorts)
        assertEquals("next", vm.uiState.value.shortsNextKey)
        assertTrue(vm.actionNotices.first() is AppNotice.Message)
    }

    @Test fun channelWholeRefreshFailureResumesAnInterruptedVisibleSectionRead() = runTest {
        var failPage = false
        var sectionReads = 0
        val firstSection = kotlinx.coroutines.CompletableDeferred<ChannelShortsPage>()
        val base = FixtureChannelRepository()
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String): ChannelPage {
                if (failPage) error("offline")
                return base.channel(channelId)
            }
            override suspend fun shorts(channelId: String): ChannelShortsPage {
                sectionReads++
                return if (sectionReads == 1) firstSection.await()
                    else base.shorts(channelId).copy(nextKey = "resumed")
            }
            override suspend fun shorts(channelId: String, opened: ChannelPage) = shorts(channelId)
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Shorts))
        runCurrent()
        assertEquals(ChannelSection.Loading, vm.uiState.value.shorts)
        failPage = true
        vm.onAction(ChannelAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
        assertTrue(vm.uiState.value.shorts is ChannelSection.Ready)
        assertEquals("resumed", vm.uiState.value.shortsNextKey)
        assertEquals(2, sectionReads)
        firstSection.complete(ChannelShortsPage(emptyList(), "stale"))
        runCurrent()
        assertEquals("resumed", vm.uiState.value.shortsNextKey)
    }
}
