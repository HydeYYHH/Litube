package com.hhst.youtubelite.comments

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.CommentsRepository
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String?, CommentSort) -> CommentsPage) = object : CommentsRepository {
        override suspend fun comments(videoId: String, sort: CommentSort, cursor: String?) = block(cursor, sort)
    }

    @Test fun firstPageLoadsTheLocalFixture() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        assertEquals(CommentsContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(4, ready.threads.size)
        assertEquals("4", ready.nextCursor)
        assertEquals(CommentSort.Top, vm.uiState.value.sort)
    }

    @Test fun newestSortRefetchesAndKeepsExpandedThreads() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        val topFirst = (vm.uiState.value.content as CommentsContent.Ready).threads.first().id
        vm.onAction(CommentAction.ToggleReplies("c3"))
        vm.onAction(CommentAction.SelectSort(CommentSort.Newest))
        assertEquals(CommentsContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(CommentSort.Newest, vm.uiState.value.sort)
        assertTrue("c3" in vm.uiState.value.expanded)
        val newestFirst = (vm.uiState.value.content as CommentsContent.Ready).threads.first().id
        assertFalse(topFirst == newestFirst)
    }

    @Test fun selectingTheActiveSortDoesNotRefetch() = runTest {
        var calls = 0
        val vm = CommentsViewModel(repository { _, _ -> calls++; FixtureCommentsRepository().comments("bunny", CommentSort.Top, null) }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.SelectSort(CommentSort.Top))
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun paginationAppendsPagesUntilTheFixtureEnds() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        assertTrue((vm.uiState.value.content as CommentsContent.Ready).loadingMore)
        advanceUntilIdle()
        val second = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(8, second.threads.size)
        assertEquals("8", second.nextCursor)
        assertFalse(second.loadingMore)
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        val third = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(9, third.threads.size)
        assertNull(third.nextCursor)
        // A finished list ignores further pagination requests.
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(9, (vm.uiState.value.content as CommentsContent.Ready).threads.size)
    }

    @Test fun paginationFailureKeepsTheLoadedPageAndRetryRecovers() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(com.hhst.youtubelite.comments.data.CommentsScenario.LoadMoreError), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        val failed = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(4, failed.threads.size)
        assertTrue(failed.loadMoreFailed)
        assertEquals("4", failed.nextCursor)
    }

    @Test fun disabledAndEmptyAndErrorStatesAreDistinct() = runTest {
        for ((scenario, expected) in listOf(
            com.hhst.youtubelite.comments.data.CommentsScenario.Disabled to CommentsContent.Disabled,
            com.hhst.youtubelite.comments.data.CommentsScenario.Empty to CommentsContent.Empty,
            com.hhst.youtubelite.comments.data.CommentsScenario.Error to CommentsContent.Failed,
        )) {
            val vm = CommentsViewModel(FixtureCommentsRepository(scenario), SavedStateHandle(), "bunny")
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.content)
        }
    }

    @Test fun retryReloadsAfterFailure() = runTest {
        var fail = true
        val vm = CommentsViewModel(repository { _, _ ->
            if (fail) throw IOException("offline")
            FixtureCommentsRepository().comments("bunny", CommentSort.Top, null)
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertEquals(CommentsContent.Failed, vm.uiState.value.content)
        fail = false
        vm.onAction(CommentAction.Retry)
        assertEquals(CommentsContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is CommentsContent.Ready)
    }

    @Test fun interactionsEmitTypedRequestsAndNeverChangeContent() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        val expectations = listOf(
            CommentAction.Compose to CommentNavigationRequest.Compose("bunny"),
            CommentAction.LikeThread("c1") to CommentNavigationRequest.LikeComment("bunny", "c1"),
            CommentAction.DislikeThread("c1") to CommentNavigationRequest.DislikeComment("bunny", "c1"),
            CommentAction.ReportThread("c1") to CommentNavigationRequest.ReportComment("bunny", "c1"),
            CommentAction.OpenThreadMenu("c1") to CommentNavigationRequest.OpenCommentMenu("bunny", "c1"),
            CommentAction.Reply("c1") to CommentNavigationRequest.Reply("bunny", "c1", "c1"),
            CommentAction.Reply("c1", "c1r1") to CommentNavigationRequest.Reply("bunny", "c1", "c1r1"),
            CommentAction.OpenAuthor("blender") to CommentNavigationRequest.OpenAuthor("blender"),
            CommentAction.LikeReply("c1", "c1r1") to CommentNavigationRequest.LikeComment("bunny", "c1r1"),
            CommentAction.DislikeReply("c1", "c1r1") to CommentNavigationRequest.DislikeComment("bunny", "c1r1"),
            CommentAction.ReportReply("c1", "c1r1") to CommentNavigationRequest.ReportComment("bunny", "c1r1"),
            CommentAction.OpenReplyMenu("c1", "c1r1") to CommentNavigationRequest.OpenCommentMenu("bunny", "c1r1"),
        )
        for ((action, expected) in expectations) {
            vm.onAction(action)
            assertEquals(expected, vm.navigationRequests.first())
            assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        }
        assertTrue(vm.uiState.value.content is CommentsContent.Ready)
        assertTrue(vm.uiState.value.expanded.isEmpty())
    }

    @Test fun sortAndExpansionSurviveRecreation() = runTest {
        val handle = SavedStateHandle()
        val vm = CommentsViewModel(FixtureCommentsRepository(), handle, "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.SelectSort(CommentSort.Newest))
        vm.onAction(CommentAction.ToggleReplies("c2"))
        vm.onAction(CommentAction.ToggleReplies("c4"))
        vm.onAction(CommentAction.ToggleReplies("c2"))
        advanceUntilIdle()
        val restored = CommentsViewModel(
            FixtureCommentsRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(CommentSort.Newest, restored.uiState.value.sort)
        assertEquals(setOf("c4"), restored.uiState.value.expanded)
        advanceUntilIdle()
        assertEquals("bunny", restored.uiState.value.videoId)
        assertTrue(restored.uiState.value.content is CommentsContent.Ready)
    }

    @Test fun toggleRepliesOnlyExpandsExistingThreads() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c9"))
        assertTrue("c9" in vm.uiState.value.expanded)
        vm.onAction(CommentAction.ToggleReplies("c9"))
        assertFalse("c9" in vm.uiState.value.expanded)
    }

    @Test fun recreatingTheModelRestoresLoadedPagesBeforePublishingTheList() = runTest {
        val handle = SavedStateHandle()
        val vm = CommentsViewModel(FixtureCommentsRepository(), handle, "bunny")
        advanceUntilIdle()
        repeat(2) { vm.onAction(CommentAction.LoadMore); advanceUntilIdle() }
        val expected = vm.uiState.value.content
        val restored = CommentsViewModel(FixtureCommentsRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), "ignored")
        advanceUntilIdle()
        assertEquals(expected, restored.uiState.value.content)
        assertEquals("5.2K", restored.uiState.value.total)
    }

    @Test fun changingSortCancelsPaginationAndStartsWithTheNewFirstPage() = runTest {
        var cancelled = false
        val repo = repository { cursor, sort ->
            if (cursor != null) try { kotlinx.coroutines.awaitCancellation() }
            finally { cancelled = true }
            FixtureCommentsRepository().comments("bunny", sort, cursor)
        }
        val vm = CommentsViewModel(repo, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        runCurrent()
        vm.onAction(CommentAction.SelectSort(CommentSort.Newest))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(CommentSort.Newest, vm.uiState.value.sort)
        assertEquals(4, (vm.uiState.value.content as CommentsContent.Ready).threads.size)
    }
}
