package com.hhst.youtubelite.home

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeDiscoveryViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun video(id: String) = FeedItem.Video(id, id, "Author", "", "", "", "channel")
    private fun repository(read: suspend (String?) -> HomePage) = object : HomeRepository {
        override suspend fun loadHome(filter: String, continuation: String?) = read(continuation)
    }
    private fun page(id: String, next: String? = null, failed: Boolean = false) = HomePage(
        listOf(video(id)), nextKey = next,
        catalog = HomeCatalog.Popular, partialFailure = failed,
    )

    @Test fun paginationKeepsThePublicModeWithoutDuplicatingCards() = runTest {
        val vm = HomeViewModel(repository { key ->
            if (key == null) page("first", "next") else page("second").let { it.copy(items = listOf(video("first")) + it.items) }
        }, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("first", "second"), feed.page.items.map { it.id })
        assertEquals(HomeCatalog.Popular, feed.page.catalog)
        assertEquals(HomeCatalog.filters, vm.uiState.value.chips)
        assertNull(feed.page.nextKey)
    }

    @Test fun missingSourcesRequireAnExplicitRetryAndPreserveVisibleContent() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository {
            if (++calls == 1) page("first", "retry", true) else HomePage(listOf(video("news")),
                catalog = HomeCatalog.Popular)
        }, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(AppendState.Failed, (vm.uiState.value.content as HomeContent.Feed).append)
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("first", "news"), feed.page.items.map { it.id })
        assertEquals(HomeCatalog.Popular, feed.page.catalog)
        assertEquals(AppendState.Idle, feed.append)
    }

    @Test fun refreshRestoresRecommendationFiltersWhenRecommendationsBecomeAvailable() = runTest {
        var public = true
        val vm = HomeViewModel(repository { if (public) page("public") else HomePage(listOf(video("recommended"))) },
            SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        public = false
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertNull(feed.page.catalog)
        assertEquals(HomeLayout.chips, vm.uiState.value.chips)
        assertEquals(listOf("recommended"), feed.page.items.map { it.id })
    }

    @Test fun chartNavigationKeepsItsPlaylistIdentity() = runTest {
        val vm = HomeViewModel(repository { HomePage(emptyList()) }, SavedStateHandle())
        vm.onAction(HomeAction.Navigate(HomeNavigationRequest.Playlist("chart")))
        assertEquals(HomeNavigationRequest.Playlist("chart"), vm.navigationRequests.first())
    }
    @Test fun duplicateOnlyPagesKeepAdvancingServerCursors() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository { page("same", "next-${++calls}") }, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        repeat(5) {
            vm.onAction(HomeAction.LoadNext)
            advanceUntilIdle()
        }
        assertEquals(6, calls)
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("same"), feed.page.items.map { it.id })
        assertEquals("next-6", feed.page.nextKey)
    }

    @Test fun repeatedContinuationCyclesStopWithoutDuplicatingCards() = runTest {
        val keys = mutableListOf<String?>()
        val vm = HomeViewModel(repository { key ->
            keys += key
            page("same", if (key == "a") "b" else "a")
        }, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        repeat(4) { vm.onAction(HomeAction.LoadNext); advanceUntilIdle() }
        assertEquals(listOf(null, "a", "b"), keys)
        assertNull((vm.uiState.value.content as HomeContent.Feed).page.nextKey)
    }

    @Test fun restoredPublicSelectionKeepsItsFilters() = runTest {
        val state = SavedStateHandle(mapOf("catalog" to true, "chip" to "Music charts"))
        val vm = HomeViewModel(repository { HomePage(emptyList()) }, state)
        assertEquals("Music charts", vm.uiState.value.selectedChip)
        assertEquals(HomeCatalog.filters, vm.uiState.value.chips)
    }

    @Test fun switchingIdentityDiscardsAnUnsupportedPublicFilter() = runTest {
        val filters = mutableListOf<String>()
        val repository = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?): HomePage {
                filters += filter
                return HomePage(listOf(video("signed-in")))
            }
        }
        val vm = HomeViewModel(repository, SavedStateHandle(mapOf("catalog" to true, "chip" to "Music charts")))
        vm.updateSession(false, 1)
        vm.updateSession(true, 2)
        advanceUntilIdle()
        assertEquals(listOf("All"), filters)
        assertEquals(HomeLayout.chips, vm.uiState.value.chips)
        assertEquals("All", vm.uiState.value.selectedChip)
    }

}
