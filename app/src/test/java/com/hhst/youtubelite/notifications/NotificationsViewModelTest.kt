package com.hhst.youtubelite.notifications

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.notifications.data.*
import com.hhst.youtubelite.notifications.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend () -> NotificationsPage) = object : NotificationsRepository {
        override suspend fun notifications() = block()
    }

    @Test fun signedOutShowsThePromptWithoutLoading() = runTest {
        val vm = NotificationsViewModel(repository { error("unused") }, NotificationsScenario.Content, signedIn = false)
        advanceUntilIdle()
        val content = vm.uiState.value.content
        assertTrue(content is NotificationsContent.SignedOut)
        assertEquals(SignedOutPrompts.forContent("subscriptions"), (content as NotificationsContent.SignedOut).prompt)
    }

    @Test fun readyEmptyAndErrorStatesStayTyped() = runTest {
        val vm = NotificationsViewModel(FixtureNotificationsRepository(NotificationsScenario.Content), NotificationsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as NotificationsContent.Ready
        assertTrue(ready.page.items.first().unread)
        assertEquals("bunny", ready.page.items.first().videoId)
        val empty = NotificationsViewModel(FixtureNotificationsRepository(NotificationsScenario.Empty), NotificationsScenario.Empty, signedIn = true)
        advanceUntilIdle()
        assertEquals(NotificationsContent.Empty, empty.uiState.value.content)
        val failed = NotificationsViewModel(repository { error("offline") }, NotificationsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertEquals(NotificationsContent.Failed, failed.uiState.value.content)
    }

    @Test fun tapsCarryTheStableContentIdentity() = runTest {
        val vm = NotificationsViewModel(FixtureNotificationsRepository(), NotificationsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val requests = mutableListOf<NotificationsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        val item = (vm.uiState.value.content as NotificationsContent.Ready).page.items[2]
        vm.onAction(NotificationsAction.OpenVideo(item.videoId!!))
        vm.onAction(NotificationsAction.OpenChannel(item.channelId))
        advanceUntilIdle()
        assertEquals(listOf(
            NotificationsNavigationRequest.Watch("dream"),
            NotificationsNavigationRequest.Channel("studio"),
        ), requests)
    }
}
