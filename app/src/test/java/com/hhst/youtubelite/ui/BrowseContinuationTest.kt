package com.hhst.youtubelite.ui

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.search.domain.SearchAction
import com.hhst.youtubelite.search.domain.SearchContent
import com.hhst.youtubelite.search.domain.SearchFilters
import com.hhst.youtubelite.search.domain.SearchPage
import com.hhst.youtubelite.search.domain.SearchRepository
import com.hhst.youtubelite.search.domain.SearchResult
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.data.SubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.domain.ChannelDirectoryPage
import com.hhst.youtubelite.subscriptions.domain.SubscribedChannel
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsAction
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsChannels
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsContent
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsPage
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseContinuationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class SearchPages(private val selfLoop: Boolean = false) : SearchRepository {
        val reads = mutableListOf<String>()
        var failNext = false
        override suspend fun search(query: String, filters: SearchFilters) =
            SearchPage(query, listOf(row("first")), nextCursor = "a")

        override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage {
            reads += cursor
            if (failNext) {
                failNext = false
                throw IOException("offline")
            }
            return SearchPage(query, listOf(row(cursor)), nextCursor = if (selfLoop || cursor == "b") "a" else "b")
        }

        private fun row(id: String) = SearchResult(id, id, "Channel", "UCchannel", "1K", "Today", "1:00", "thumb")
    }

    @Test fun searchStopsACursorCycleAndKeepsTheLastPagesRows() = runTest {
        val repo = SearchPages()
        val vm = SearchResultsViewModel(repo, SavedStateHandle(), "query")
        advanceUntilIdle()
        repeat(4) {
            vm.onAction(SearchAction.LoadMore)
            advanceUntilIdle()
        }
        val ready = vm.uiState.value.content as SearchContent.Results
        assertEquals(listOf("a", "b"), repo.reads)
        assertEquals(listOf("first", "a", "b"), ready.page.results.map { it.id })
        assertNull(ready.page.nextCursor)
        assertFalse(ready.loadMoreFailed)
    }

    @Test fun searchKeepsRowsFromASelfPointingPageInsteadOfDiscardingThemAsAnError() = runTest {
        val repo = SearchPages(selfLoop = true)
        val vm = SearchResultsViewModel(repo, SavedStateHandle(), "query")
        advanceUntilIdle()
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as SearchContent.Results
        assertEquals(listOf("first", "a"), ready.page.results.map { it.id })
        assertNull(ready.page.nextCursor)
        assertFalse(ready.loadMoreFailed)
    }

    @Test fun aFailedSearchPageCanRetryAndANewQueryCanReuseCursors() = runTest {
        val repo = SearchPages()
        val vm = SearchResultsViewModel(repo, SavedStateHandle(), "old")
        advanceUntilIdle()
        repo.failNext = true
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        assertTrue((vm.uiState.value.content as SearchContent.Results).loadMoreFailed)
        repeat(2) {
            vm.onAction(SearchAction.LoadMore)
            advanceUntilIdle()
        }
        vm.onAction(SearchAction.SubmitSearch("new"))
        advanceUntilIdle()
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals(listOf("a", "a", "b", "a"), repo.reads)
        assertEquals("new", page.query)
        assertEquals("b", page.nextCursor)
    }

    private class SubscriptionPages(private val selfLoop: Boolean = false) : SubscriptionsRepository {
        val feedReads = mutableListOf<String?>()
        val directoryReads = mutableListOf<String?>()
        var failDirectory = false
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?): SubscriptionsPage {
            feedReads += continuation
            val id = continuation ?: "first"
            return SubscriptionsPage(listOf(FeedItem.Video(id, id, "Channel", "", "", "", "UCchannel")),
                emptyList(), next(continuation))
        }

        override suspend fun subscribedChannelsPage(cursor: String?): ChannelDirectoryPage {
            directoryReads += cursor
            if (failDirectory) {
                failDirectory = false
                throw IOException("offline")
            }
            val id = cursor ?: "first"
            return ChannelDirectoryPage(listOf(SubscribedChannel(id, id, "", "")), next(cursor))
        }

        private fun next(cursor: String?): String = if (selfLoop || cursor == null || cursor == "b") "a" else "b"
    }

    @Test fun subscriptionFeedAndDirectoryStopCyclesIndependentlyAndKeepTheirRows() = runTest {
        val repo = SubscriptionPages()
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        repeat(4) {
            vm.onAction(SubscriptionsAction.LoadNext)
            vm.onAction(SubscriptionsAction.LoadMoreChannels)
            advanceUntilIdle()
        }
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        val directory = vm.uiState.value.channels as SubscriptionsChannels.Ready
        assertEquals(listOf(null, "a", "b"), repo.feedReads)
        assertEquals(listOf(null, "a", "b"), repo.directoryReads)
        assertEquals(listOf("first", "a", "b"), feed.page.items.map { it.id })
        assertEquals(listOf("first", "a", "b"), directory.channels.map { it.channelId })
        assertNull(feed.page.nextKey)
        assertNull(directory.nextKey)
    }

    @Test fun aSelfPointingDirectoryPageEndsWithoutDroppingNewChannels() = runTest {
        val repo = SubscriptionPages(selfLoop = true)
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        repeat(3) {
            vm.onAction(SubscriptionsAction.LoadMoreChannels)
            advanceUntilIdle()
        }
        val ready = vm.uiState.value.channels as SubscriptionsChannels.Ready
        assertEquals(listOf(null, "a"), repo.directoryReads)
        assertEquals(listOf("first", "a"), ready.channels.map { it.channelId })
        assertNull(ready.nextKey)
    }

    @Test fun failedDirectoryPagesCanRetryAndAnAccountSwitchStartsANewWalk() = runTest {
        val repo = SubscriptionPages()
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.updateSession(true, 1L)
        repo.failDirectory = true
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        assertEquals(AppendState.Failed, (vm.uiState.value.channels as SubscriptionsChannels.Ready).append)
        repeat(2) {
            vm.onAction(SubscriptionsAction.LoadMoreChannels)
            vm.onAction(SubscriptionsAction.LoadNext)
            advanceUntilIdle()
        }
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        assertEquals(listOf(null, "a", "a", "b", null, "a"), repo.directoryReads)
        assertEquals(listOf(null, "a", "b", null, "a"), repo.feedReads)
        assertEquals("b", (vm.uiState.value.channels as SubscriptionsChannels.Ready).nextKey)
        assertEquals("b", (vm.uiState.value.content as SubscriptionsContent.Feed).page.nextKey)
    }

    @Test fun quietDirectoryRefreshCannotAppendThePreviousSnapshotsCursor() = runTest {
        val refresh = CompletableDeferred<ChannelDirectoryPage>()
        val reads = mutableListOf<String?>()
        var now = 0L
        val repo = object : SubscriptionsRepository {
            override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) =
                SubscriptionsPage(emptyList(), emptyList())

            override suspend fun subscribedChannelsPage(cursor: String?): ChannelDirectoryPage {
                reads += cursor
                if (cursor == null && reads.size > 1) return refresh.await()
                return ChannelDirectoryPage(listOf(SubscribedChannel("channel", "Channel", "", "")),
                    if (cursor == null) "old" else null)
            }
        }
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true, clock = { now })
        advanceUntilIdle()
        now = 60_000L
        vm.onForeground()
        runCurrent()
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        runCurrent()
        assertEquals(listOf(null, null), reads)
        refresh.complete(ChannelDirectoryPage(listOf(SubscribedChannel("new", "New", "", "")), "new-cursor"))
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        assertEquals(listOf(null, null, "new-cursor"), reads)
        assertEquals(listOf("new", "channel"),
            (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels.map { it.channelId })
    }
}
