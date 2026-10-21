package com.hhst.youtubelite.post

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.post.data.FixturePostRepository
import com.hhst.youtubelite.post.data.PostScenario
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.post.presentation.PostDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val identity = PostIdentity("studio", "post")

    private fun viewModel(
        scenario: PostScenario = PostScenario.Content,
        saved: SavedStateHandle = SavedStateHandle(),
    ) = PostDetailViewModel(FixturePostRepository(scenario), saved, identity, scenario)

    @Test fun loadsTheRequestedPostIdentity() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(identity, state.identity)
        assertTrue(state.content is PostDetailContent.Ready)
        assertEquals(identity, (state.content as PostDetailContent.Ready).post.identity)
        assertEquals(3, state.post?.images?.size)
    }

    @Test fun bottomNavigationEmitsTheRequestedDestination() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAction(PostDetailAction.SelectDestination(PostDestination.You))
        assertEquals(PostDetailNavigationRequest.Destination(PostDestination.You), vm.navigationRequests.first())
    }

    @Test fun localImageSelectionIsSavedWithThePost() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        advanceUntilIdle()
        vm.onAction(PostDetailAction.SelectImage(2))
        assertEquals(2, vm.uiState.value.selectedImage)
        assertEquals(2, saved.get<Int>("selectedImage"))
        val restored = viewModel(saved = saved)
        assertEquals(2, restored.uiState.value.selectedImage)
    }

    @Test fun feedbackCommentsAndMenuActionsCarryThePostIdentity() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAction(PostDetailAction.Like)
        assertEquals(PostDetailNavigationRequest.Feedback(identity, true), vm.navigationRequests.first())
        vm.onAction(PostDetailAction.Comments)
        assertEquals(PostDetailNavigationRequest.Comments(identity), vm.navigationRequests.first())
        vm.onAction(PostDetailAction.OpenMenu)
        assertEquals(PostDetailOverlay.Menu, vm.uiState.value.overlay)
        vm.onAction(PostDetailAction.MenuSelected(PostMenuAction.Report))
        assertEquals(
            PostDetailNavigationRequest.MenuAction(identity, PostMenuAction.Report),
            vm.navigationRequests.first(),
        )
        assertEquals(PostDetailOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun failedPostRetriesWithoutChangingIdentity() = runTest {
        val vm = viewModel(PostScenario.Error)
        advanceUntilIdle()
        assertEquals(PostDetailContent.Failed, vm.uiState.value.content)
        vm.onAction(PostDetailAction.Retry)
        assertEquals(PostDetailContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(PostDetailContent.Failed, vm.uiState.value.content)
        assertEquals(identity, vm.uiState.value.identity)
    }

    @Test fun missingPostShowsFailureAndKeepsIdentity() = runTest {
        val missing = PostIdentity("studio", "missing")
        val vm = PostDetailViewModel(FixturePostRepository(), SavedStateHandle(), missing, PostScenario.Content)
        advanceUntilIdle()
        assertEquals(missing, vm.uiState.value.identity)
        assertEquals(PostDetailContent.Failed, vm.uiState.value.content)
    }
}
