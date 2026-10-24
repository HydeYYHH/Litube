package com.hhst.youtubelite.subscriptions

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.library.domain.SignInPrompt
import com.hhst.youtubelite.subscriptions.data.*
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsViewModel
import com.hhst.youtubelite.ui.components.MainTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (SubscriptionsFilter, String?) -> SubscriptionsPage) = object : SubscriptionsRepository {
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) = block(filter, continuation)
    }

    @Test fun signedOutFeedShowsThePromptWithoutLoading() = runTest {
        val vm = SubscriptionsViewModel(repository { _, _ -> error("unused") }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = false)
        advanceUntilIdle()
        val content = vm.uiState.value.content
        assertTrue(content is SubscriptionsContent.SignedOut)
        assertEquals(SignedOutPrompts.forContent("subscriptions"), (content as SubscriptionsContent.SignedOut).prompt)
    }

    @Test fun chipSelectionReloadsWithTheMatchingFilter() = runTest {
        val filters = mutableListOf<SubscriptionsFilter>()
        val vm = SubscriptionsViewModel(repository { filter, _ ->
            filters += filter
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos().filter { it.live || filter != SubscriptionsFilter.Live }, FixtureSubscriptionsRepository.channels())
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertEquals(listOf(SubscriptionsFilter.All), filters)
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        advanceUntilIdle()
        assertEquals(listOf(SubscriptionsFilter.All, SubscriptionsFilter.Live), filters)
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertTrue(feed.page.items.all { it.live })
        assertEquals(SubscriptionsChip.Live, vm.uiState.value.selectedChip)
    }

    @Test fun postsChipKeepsTheEmptyStateInsteadOfInventingPosts() = runTest {
        val vm = SubscriptionsViewModel(repository { filter, _ ->
            SubscriptionsPage(if (filter == SubscriptionsFilter.Posts) emptyList() else FixtureSubscriptionsRepository.sampleVideos(),
                FixtureSubscriptionsRepository.channels())
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Posts))
        advanceUntilIdle()
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertTrue(feed.page.items.isEmpty())
    }

    @Test fun paginationAppendsAndFailureKeepsTheLoadedFeed() = runTest {
        var failAppend = false
        val vm = SubscriptionsViewModel(repository { _, continuation ->
            if (continuation != null && failAppend) error("offline")
            val base = FixtureSubscriptionsRepository.sampleVideos()
            SubscriptionsPage(if (continuation == null) base else base.map { it.copy(id = it.id + "-2") },
                FixtureSubscriptionsRepository.channels(), nextKey = if (continuation == null) "page-2" else "page-3")
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val initialSize = (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.size
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        var feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(initialSize * 2, feed.page.items.size)
        assertEquals(AppendState.Idle, feed.append)
        failAppend = true
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(initialSize * 2, feed.page.items.size)
        assertEquals(AppendState.Failed, feed.append)
    }

    @Test fun menuMirrorsTheHomeVideoActionsAndEmitsIdentity() = runTest {
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val requests = mutableListOf<SubscriptionsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(SubscriptionsAction.OpenMenu("bunny"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(listOf(SubscriptionsNavigationRequest.MenuAction("bunny", FeedMenuAction.WatchLater)), requests)
        assertEquals(SubscriptionsOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun selectingTheSubscriptionsTabItselfDoesNotNavigate() = runTest {
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val requests = mutableListOf<SubscriptionsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(SubscriptionsAction.Destination(MainTab.Subscriptions))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun scenarioInitialContentCoversLoadingErrorAndAppendStates() {
        val prompt = SignInPrompt("Don't miss new videos", "Sign in to see updates from your favorite YouTube channels", "Sign in")
        assertEquals(SubscriptionsContent.Loading, SubscriptionsScenario.Loading.initialContent(prompt))
        assertEquals(SubscriptionsContent.Failed, SubscriptionsScenario.Error.initialContent(prompt))
        assertTrue(SubscriptionsScenario.PaginationLoading.initialContent(prompt) is SubscriptionsContent.Feed)
        val append = (SubscriptionsScenario.PaginationError.initialContent(prompt) as SubscriptionsContent.Feed).append
        assertEquals(AppendState.Failed, append)
    }

    @Test fun staleFilterCompletionCannotOverwriteTheNewFilter() = runTest {
        val vm = SubscriptionsViewModel(repository { filter, _ ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(if (filter == SubscriptionsFilter.All) 200 else 10)
            }
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos().take(if (filter == SubscriptionsFilter.All) 2 else 1), emptyList())
        }, SavedStateHandle(), SubscriptionsScenario.Content, true)
        runCurrent()
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.size)
        assertEquals(SubscriptionsChip.Live, vm.uiState.value.selectedChip)
    }

    @Test fun failedPaginationCanRetryAndDuplicateIdsAreMerged() = runTest {
        var attempts = 0
        val page = FixtureSubscriptionsRepository.sampleVideos()
        val vm = SubscriptionsViewModel(repository { _, cursor ->
            if (cursor != null && attempts++ == 0) error("offline")
            SubscriptionsPage(page, emptyList(), if (cursor == null) "next" else null)
        }, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        assertEquals(AppendState.Failed, (vm.uiState.value.content as SubscriptionsContent.Feed).append)
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(page.size, feed.page.items.size)
        assertEquals(AppendState.Idle, feed.append)
        assertNull(feed.page.nextKey)
    }

    @Test fun filterSearchAndOverlayRestoreTogether() = runTest {
        val handle = SavedStateHandle()
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), handle, SubscriptionsScenario.Content, true)
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        vm.onAction(SubscriptionsAction.QueryChanged("films"))
        vm.onAction(SubscriptionsAction.OpenSearch)
        val restored = SubscriptionsViewModel(FixtureSubscriptionsRepository(), handle, SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertEquals(SubscriptionsChip.Live, restored.uiState.value.selectedChip)
        assertEquals("films", restored.uiState.value.query)
        assertEquals(SubscriptionsOverlay.Search, restored.uiState.value.overlay)
    }

    private class FakeSaveRepository : com.hhst.youtubelite.saves.domain.SaveRepository {
        var watchLaterCalls = 0
        override suspend fun choices(videoId: String) =
            listOf(com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun saveWatchLater(videoId: String) { watchLaterCalls++ }
        override suspend fun create(title: String, description: String, videoId: String) =
            com.hhst.youtubelite.saves.domain.SaveChoice("PLnew", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }

    @Test fun liveMenuWritesThroughTheSaveSheetAndDropsFeedbackRows() = runTest {
        val saves = FakeSaveRepository()
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), SavedStateHandle(), SubscriptionsScenario.Content, true,
            saveRepository = saves)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.live)
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(1, saves.watchLaterCalls)
        assertEquals(SubscriptionsOverlay.None, vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        assertEquals("harbour", vm.saveSheet?.state?.value?.videoId)
        vm.saveSheet?.close()
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.NotInterested))
        advanceUntilIdle()
        assertNull(vm.saveSheet?.state?.value)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

}
