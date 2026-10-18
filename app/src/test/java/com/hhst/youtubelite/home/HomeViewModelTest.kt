package com.hhst.youtubelite.home

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.data.*
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String, String?) -> HomePage) = object : HomeRepository {
        override suspend fun loadHome(filter: String, continuation: String?) = block(filter, continuation)
    }

    @Test fun firstVisitDoesNotLoadRecommendations() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository { _, _ -> calls++; FixtureHomeRepository.samplePage() }, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(0, calls)
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
        assertFalse(vm.uiState.value.signedInPreview)
    }

    @Test fun successfulEmptyReplyPreservesServerMessageWithoutFallback() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val vm = HomeViewModel(repository { filter, cursor -> requests += filter to cursor; HomePage(emptyList(), listOf("Search for something you love")) }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(listOf("All" to null), requests)
        assertEquals("Search for something you love", (vm.uiState.value.content as HomeContent.Empty).title)
    }

    @Test fun requestFailureIsNotAnEmptyFeed() = runTest {
        val vm = HomeViewModel(repository { _, _ -> error("offline") }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(HomeContent.Failed, vm.uiState.value.content)
    }

    @Test fun failedAppendKeepsVisibleItemsAndCanRetrySameCursor() = runTest {
        var fail = true
        val cursors = mutableListOf<String?>()
        val original = FixtureHomeRepository.samplePage()
        val vm = HomeViewModel(repository { _, cursor ->
            cursors += cursor
            if (fail) error("offline")
            original.copy(nextKey = null)
        }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        assertEquals(original.items, (vm.uiState.value.content as HomeContent.Feed).page.items)
        assertEquals(AppendState.Failed, (vm.uiState.value.content as HomeContent.Feed).append)
        fail = false
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("fixture-page-2", "fixture-page-2"), cursors)
        assertEquals(original.items, feed.page.items)
        assertNull(feed.page.nextKey)
    }

    @Test fun refreshFailureIsNotPresentedAsPaginationFailure() = runTest {
        val vm = HomeViewModel(repository { _, _ -> error("offline") }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertTrue(feed.refreshFailed)
        assertFalse(feed.refreshing)
        assertEquals(AppendState.Idle, feed.append)
    }

    @Test fun lateFilterReplyCannotReplaceNewerSelection() = runTest {
        val vm = HomeViewModel(repository { filter, _ ->
            withContext(NonCancellable) { delay(if (filter == "Music") 100 else 10) }
            HomePage(listOf(FeedItem.Mix(filter, filter, "", "bunny")))
        }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.SelectChip("Music"))
        runCurrent()
        vm.onAction(HomeAction.SelectChip("Live"))
        advanceUntilIdle()
        assertEquals("Live", vm.uiState.value.selectedChip)
        assertEquals("Live", (vm.uiState.value.content as HomeContent.Feed).page.items.single().id)
    }

    @Test fun queryAndOpenSearchSurviveSavedStateReconstruction() {
        val handle = SavedStateHandle()
        val vm = HomeViewModel(FixtureHomeRepository(), handle)
        vm.onAction(HomeAction.QueryChanged("nature"))
        vm.onAction(HomeAction.OpenSearch)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = HomeViewModel(FixtureHomeRepository(), restoredHandle)
        assertEquals("nature", restored.uiState.value.query)
        assertEquals(HomeOverlay.Search, restored.uiState.value.overlay)
        restored.onAction(HomeAction.CloseOverlay)
        assertEquals("nature", restored.uiState.value.query)
    }

    @Test fun searchEmitsTypedRequestWithoutInventingResults() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle())
        vm.onAction(HomeAction.SubmitSearch("  nature  "))
        assertEquals(HomeNavigationRequest.Search("nature"), vm.navigationRequests.first())
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
    }

    @Test fun blankSearchDoesNotEmitNavigation() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle())
        vm.onAction(HomeAction.SubmitSearch("   "))
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun menuEmitsExactItemAndActionWithoutWritingAnAccount() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.WatchLater))
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        assertEquals(HomeNavigationRequest.MenuAction("bunny", FeedMenuAction.WatchLater), vm.navigationRequests.first())
        assertEquals(FixtureHomeRepository.samplePage(), (vm.uiState.value.content as HomeContent.Feed).page)
    }

    @Test fun doubleAppendDoesNotStartDuplicateRequests() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository { _, _ -> calls++; delay(100); FixtureHomeRepository.samplePage().copy(nextKey = null) }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.LoadNext)
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun websiteColumnBoundariesDoNotDependOnOrientation() {
        val boundaries = listOf(528f, 994f, 1320f, 1646f, 1972f)
        boundaries.forEachIndexed { index, width ->
            assertEquals(index + 1, HomeLayout.columns(width - 0.5f))
            assertEquals(index + 2, HomeLayout.columns(width))
            assertEquals(index + 2, HomeLayout.columns(width + 0.5f))
        }
        assertEquals(listOf(1, 1, 1, 2, 2, 3, 4), listOf(320f, 390f, 411f, 600f, 840f, 1280f, 1600f).map(HomeLayout::columns))
    }
}
