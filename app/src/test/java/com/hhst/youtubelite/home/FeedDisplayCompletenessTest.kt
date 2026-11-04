package com.hhst.youtubelite.home

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeViewModel
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.data.SubscriptionsRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedDisplayCompletenessTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val initial = FeedItem.Video("video", "Title", "Author", "", "cover", "1:23", "UCauthor", avatar = "avatar")
    private val continuation = initial.copy(metadata = "2K views · Today", duration = "", avatar = "", age = "Today")

    @Test fun homeContinuationRepairsPartialDuplicateCardsWithoutLosingTheirDurationAndAvatar() = runTest {
        val repository = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) =
                if (continuation == null) HomePage(listOf(initial), nextKey = "next")
                else HomePage(listOf(this@FeedDisplayCompletenessTest.continuation))
        }
        val vm = HomeViewModel(repository, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val row = (vm.uiState.value.content as HomeContent.Feed).page.items.single() as FeedItem.Video
        assertEquals("2K views · Today", row.metadata)
        assertEquals("Today", row.age)
        assertEquals("1:23", row.duration)
        assertEquals("avatar", row.avatar)
    }

    @Test fun subscriptionsContinuationRepairsPartialDuplicateCardsWithoutLosingTheirDurationAndAvatar() = runTest {
        val repository = object : SubscriptionsRepository {
            override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) =
                if (continuation == null) SubscriptionsPage(listOf(initial), emptyList(), nextKey = "next")
                else SubscriptionsPage(listOf(this@FeedDisplayCompletenessTest.continuation), emptyList())
        }
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        val row = (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.single()
        assertEquals("2K views · Today", row.metadata)
        assertEquals("Today", row.age)
        assertEquals("1:23", row.duration)
        assertEquals("avatar", row.avatar)
    }

    @Test fun anEmptyHomePageKeepsItsCursorAndTheFollowingPageCanRecover() = runTest {
        val repository = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) =
                if (continuation == null) HomePage(emptyList(), nextKey = "next") else HomePage(listOf(initial))
        }
        val vm = HomeViewModel(repository, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        val first = vm.uiState.value.content as HomeContent.Feed
        assertEquals("next", first.page.nextKey)
        assertTrue(first.page.items.isEmpty())
        vm.onAction(HomeAction.AutomaticLoadNext)
        advanceUntilIdle()
        val recovered = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf(initial), recovered.page.items)
        assertNull(recovered.page.nextKey)
    }

    @Test fun endlessEmptyPagesPauseAutomaticRequestsAndOneManualReadCanRecover() = runTest {
        var calls = 0
        val repository = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?): HomePage {
                calls++
                return if (calls == 5) HomePage(listOf(initial)) else HomePage(emptyList(), nextKey = "cursor$calls")
            }
        }
        val vm = HomeViewModel(repository, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        repeat(10) { vm.onAction(HomeAction.AutomaticLoadNext); advanceUntilIdle() }
        assertEquals(4, calls)
        val paused = vm.uiState.value.content as HomeContent.Feed
        assertEquals("cursor4", paused.page.nextKey)
        assertTrue(paused.automaticPagingPaused)
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val recovered = vm.uiState.value.content as HomeContent.Feed
        assertEquals(5, calls)
        assertEquals(listOf(initial), recovered.page.items)
        assertFalse(recovered.automaticPagingPaused)
        assertNull(recovered.page.nextKey)
    }

    @Test fun anEmptyContinuationWithoutAnotherCursorBecomesTheHonestEmptyState() = runTest {
        val repository = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) =
                HomePage(emptyList(), nextKey = if (continuation == null) "next" else null)
        }
        val vm = HomeViewModel(repository, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        vm.onAction(HomeAction.AutomaticLoadNext)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
    }

    @Test fun duplicateOnlyPagesAlsoPauseAutomaticReadsWhilePreservingRowsAndCursor() = runTest {
        var calls = 0
        val repository = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?): HomePage {
                calls++
                return HomePage(listOf(initial), nextKey = "cursor$calls")
            }
        }
        val vm = HomeViewModel(repository, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        repeat(10) { vm.onAction(HomeAction.AutomaticLoadNext); advanceUntilIdle() }
        val paused = vm.uiState.value.content as HomeContent.Feed
        assertEquals(4, calls)
        assertEquals(listOf(initial), paused.page.items)
        assertEquals("cursor4", paused.page.nextKey)
        assertTrue(paused.automaticPagingPaused)
    }
}
