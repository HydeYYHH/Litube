package com.hhst.youtubelite.comments

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.watch.domain.VideoRating
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentWriteContinuityTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val author = CommentAuthor("Reader", "@reader", "")
    private fun thread(id: String) = CommentThread(id, author, "1 day ago", "Body $id", "10", 1,
        listOf(CommentReply("r$id", author, "", "Reply", "2", ownedByViewer = true)), ownedByViewer = true,
        replyKey = "replies-$id", repliesNextCursor = "reply-next-$id")

    private inner class Repository : CommentsRepository, CommentActions {
        val reads = mutableListOf<String?>()
        var replyReads = 0
        var write: suspend (CommentNavigationRequest) -> CommentMutation = { CommentMutation() }
        override suspend fun comments(target: CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
            reads += cursor
            return CommentsPage("100", listOf(thread(if (cursor == null) "a" else "b")), if (cursor == null) "next" else "third")
        }
        override suspend fun repliesPage(target: CommentTarget, thread: CommentThread, cursor: String?): RepliesPage {
            replyReads++
            return RepliesPage(emptyList(), null)
        }
        override suspend fun perform(request: CommentNavigationRequest) = write(request)
    }

    @Test fun ratingKeepsAllPagesExpandedRepliesAndConcurrentPaginationWithoutAnyLoadingTransition() = runTest {
        val repo = Repository()
        val vm = CommentsViewModel(repo, SavedStateHandle(), "video")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("a"))
        val gate = CompletableDeferred<CommentMutation>()
        repo.write = { gate.await() }
        val states = mutableListOf<CommentsContent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { states += it.content } }
        vm.onAction(CommentAction.LikeThread("a"))
        vm.onAction(CommentAction.LikeThread("a")) // blocked duplicate
        runCurrent()
        assertEquals(CommentNavigationRequest.LikeComment(CommentTarget.Video("video"), "a", VideoRating.Like, VideoRating.None), vm.uiState.value.pendingAction)
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        gate.complete(CommentMutation(thread("a").copy(rating = VideoRating.Like, likes = "11")))
        advanceUntilIdle()
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(listOf("a", "b"), ready.threads.map { it.id })
        assertEquals("third", ready.nextCursor)
        assertEquals("11", ready.threads.first().likes)
        assertEquals("reply-next-a", ready.threads.first().repliesNextCursor)
        assertEquals(setOf("a"), vm.uiState.value.expanded)
        assertEquals(listOf(null, "next"), repo.reads)
        assertEquals(0, repo.replyReads)
        assertTrue(states.all { it is CommentsContent.Ready })
    }

    @Test fun togglingAReplyChangesOnlyThatReplyAndNeverReadsTheThreadAgain() = runTest {
        val repo = Repository()
        val requests = mutableListOf<CommentNavigationRequest>()
        repo.write = { requests += it; CommentMutation() }
        val vm = CommentsViewModel(repo, SavedStateHandle(), "video")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("a"))
        val untouched = (vm.uiState.value.content as CommentsContent.Ready).threads[1]
        for (action in listOf(CommentAction.LikeReply("a", "ra"), CommentAction.DislikeReply("a", "ra"), CommentAction.DislikeReply("a", "ra"))) {
            vm.onAction(action)
            advanceUntilIdle()
        }
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(VideoRating.None, ready.threads.first().replies.single().rating)
        assertEquals("2", ready.threads.first().replies.single().likes)
        assertSame(untouched, ready.threads[1])
        assertEquals(listOf(null, "next"), repo.reads)
        assertEquals(0, repo.replyReads)
        assertEquals(listOf(
            CommentNavigationRequest.LikeComment("video", "ra", VideoRating.Like, VideoRating.None),
            CommentNavigationRequest.DislikeComment("video", "ra", VideoRating.Dislike, VideoRating.Like),
            CommentNavigationRequest.DislikeComment("video", "ra", VideoRating.None, VideoRating.Dislike),
        ), requests)
    }

    @Test fun deletionAndEditingPreserveOtherThreadsAndCursors() = runTest {
        val repo = Repository()
        val vm = CommentsViewModel(repo, SavedStateHandle(), "video")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        vm.onAction(CommentAction.OpenReplyMenu("a", "ra"))
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Edit))
        vm.onAction(CommentAction.DraftChanged("Edited reply"))
        vm.onAction(CommentAction.SubmitDraft)
        advanceUntilIdle()
        assertEquals("Edited reply", (vm.uiState.value.content as CommentsContent.Ready).threads.first().replies.single().body)
        vm.onAction(CommentAction.RequestDelete("a", "ra"))
        vm.onAction(CommentAction.ConfirmDelete)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertTrue(ready.threads.first().replies.isEmpty())
        assertEquals(0, ready.threads.first().replyCount)
        assertEquals("third", ready.nextCursor)
        vm.onAction(CommentAction.RequestDelete("a"))
        vm.onAction(CommentAction.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(listOf("b"), (vm.uiState.value.content as CommentsContent.Ready).threads.map { it.id })
        assertEquals("99", vm.uiState.value.total)
        assertEquals(listOf(null, "next"), repo.reads)
    }

    @Test fun serverCreatedCommentsAndRepliesAreInsertedWithoutDiscardingExistingPages() = runTest {
        val repo = Repository()
        val vm = CommentsViewModel(repo, SavedStateHandle(), "video")
        advanceUntilIdle()
        repo.write = { CommentMutation(thread("new")) }
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("New body"))
        vm.onAction(CommentAction.SubmitDraft)
        advanceUntilIdle()
        assertEquals(listOf("new", "a"), (vm.uiState.value.content as CommentsContent.Ready).threads.map { it.id })
        repo.write = { CommentMutation(thread("new-reply")) }
        vm.onAction(CommentAction.Reply("a"))
        vm.onAction(CommentAction.DraftChanged("Reply body"))
        vm.onAction(CommentAction.SubmitDraft)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(listOf("ra", "new-reply"), ready.threads.last().replies.map { it.id })
        assertEquals("next", ready.nextCursor)
        assertTrue("a" in vm.uiState.value.expanded)
        assertEquals(listOf<String?>(null), repo.reads)
    }

    @Test fun failedRatingLeavesTheExactContentAndExpandedStateInPlace() = runTest {
        val repo = Repository()
        val vm = CommentsViewModel(repo, SavedStateHandle(), "video")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("a"))
        val before = vm.uiState.value
        repo.write = { throw java.io.IOException("offline") }
        vm.onAction(CommentAction.LikeThread("a"))
        advanceUntilIdle()
        assertSame(before.content, vm.uiState.value.content)
        assertEquals(before.expanded, vm.uiState.value.expanded)
        assertFalse(vm.uiState.value.actionPending)
        assertNull(vm.uiState.value.pendingAction)
        assertEquals(listOf<String?>(null), repo.reads)
    }
}
