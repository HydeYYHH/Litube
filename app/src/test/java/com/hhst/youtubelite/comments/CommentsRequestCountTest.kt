package com.hhst.youtubelite.comments

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentAuthor
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.CommentsRepository
import com.hhst.youtubelite.comments.domain.RepliesPage
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsRequestCountTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class CountingRepository(
        private val commentsHandler: suspend (String?, CommentSort) -> CommentsPage,
        private val repliesHandler: suspend (CommentThread, String?) -> RepliesPage = { thread, cursor ->
            RepliesPage(if (cursor == null) thread.replies else emptyList(), null)
        },
    ) : CommentsRepository {
        val commentsCalls = mutableListOf<Pair<String?, CommentSort>>()
        val repliesCalls = mutableListOf<Pair<String, String?>>()
        override suspend fun comments(target: CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
            commentsCalls += cursor to sort
            return commentsHandler(cursor, sort)
        }
        override suspend fun repliesPage(target: CommentTarget, thread: CommentThread, cursor: String?): RepliesPage {
            repliesCalls += thread.id to cursor
            return repliesHandler(thread, cursor)
        }
    }

    private fun thread(id: String, replyKey: String? = null, replies: List<CommentReply> = emptyList(), replyCount: Int = replies.size) =
        CommentThread(id, CommentAuthor("Author $id", "@$id", "art"), "1 day ago", "Body $id", "10", replyCount, replies = replies, replyKey = replyKey)

    private fun reply(id: String) = CommentReply(id, CommentAuthor("Replier $id", "@$id", "art"), "1 hour ago", "Reply $id", "1")

    private fun pagingRepository() = CountingRepository({ cursor, _ ->
        val start = cursor?.toInt() ?: 0
        val ids = (start until minOf(start + 2, 5)).map { "t$it" }
        CommentsPage("5", ids.map { thread(it) }, if (start + 2 < 5) (start + 2).toString() else null)
    })

    private fun restoredHandle(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private fun readyThreads(vm: CommentsViewModel) =
        (vm.uiState.value.content as CommentsContent.Ready).threads

    @Test fun firstOpenMakesExactlyOneRequest() = runTest {
        val repo = CountingRepository({ _, _ -> CommentsPage("1", listOf(thread("c1")), null) })
        CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        assertEquals(listOf(null to CommentSort.Top), repo.commentsCalls)
    }

    @Test fun anEmptyOpeningPageKeepsItsContinuationUntilCommentsArrive() = runTest {
        val repo = CountingRepository({ cursor, _ ->
            if (cursor == null) CommentsPage("12", emptyList(), "next")
            else CommentsPage("12", listOf(thread("c1")), null)
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        val pending = vm.uiState.value.content as CommentsContent.Ready
        assertTrue(pending.threads.isEmpty())
        assertEquals("next", pending.nextCursor)
        assertEquals("12", vm.uiState.value.total)
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("c1"), readyThreads(vm).map { it.id })
        assertEquals(2, repo.commentsCalls.size)
    }

    @Test fun onlyAnExhaustedEmptyContinuationShowsNoComments() = runTest {
        val repo = CountingRepository({ cursor, _ -> CommentsPage("12", emptyList(), if (cursor == null) "next" else null) })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(CommentsContent.Empty, vm.uiState.value.content)
        assertEquals("0", vm.uiState.value.total)
    }

    @Test fun distinctCursorsWithoutNewCommentsPauseAutomaticReadsAndAllowRetry() = runTest {
        var recover = false
        var page = 0
        val repo = CountingRepository({ _, _ ->
            page++
            CommentsPage("12", listOf(thread(if (recover) "recovered" else "c1")), "page-$page")
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        repeat(10) {
            if (!(vm.uiState.value.content as CommentsContent.Ready).loadMoreFailed) vm.onAction(CommentAction.LoadMore)
            advanceUntilIdle()
        }
        assertEquals(4, repo.commentsCalls.size)
        assertTrue((vm.uiState.value.content as CommentsContent.Ready).loadMoreFailed)
        assertEquals(listOf("c1"), readyThreads(vm).map { it.id })
        recover = true
        vm.onAction(CommentAction.Retry)
        advanceUntilIdle()
        assertEquals(5, repo.commentsCalls.size)
        assertFalse((vm.uiState.value.content as CommentsContent.Ready).loadMoreFailed)
        assertEquals(listOf("c1", "recovered"), readyThreads(vm).map { it.id })
    }

    @Test fun reopeningAClosedPanelRestartsOnlyACancelledFirstRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = CountingRepository({ _, _ -> gate.await(); CommentsPage("1", listOf(thread("c1")), null) })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        runCurrent()
        assertEquals(1, repo.commentsCalls.size)
        vm.cancelTransient()
        vm.ensureLoaded()
        runCurrent()
        assertEquals(2, repo.commentsCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repo.commentsCalls.size)
        assertEquals(1, readyThreads(vm).size)
        vm.cancelTransient()
        vm.ensureLoaded()
        advanceUntilIdle()
        assertEquals(2, repo.commentsCalls.size)
    }

    @Test fun switchingSortIssuesExactlyOneFreshFirstPage() = runTest {
        val repo = CountingRepository({ cursor, sort ->
            CommentsPage("9", listOf(thread("c-$sort-${cursor ?: "first"}")), if (cursor == null) "next" else null)
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.SelectSort(CommentSort.Newest))
        advanceUntilIdle()
        assertEquals(listOf(null to CommentSort.Top, null to CommentSort.Newest), repo.commentsCalls)
        assertEquals(listOf("c-Newest-first"), readyThreads(vm).map { it.id })
    }

    @Test fun selectingAnotherVideoIssuesOneRequestAndForgetsLoadedDepth() = runTest {
        val repo = pagingRepository()
        val handle = SavedStateHandle()
        val vm = CommentsViewModel(repo, handle, "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(2, repo.commentsCalls.size)
        assertEquals(4, readyThreads(vm).size)
        vm.selectVideo("w")
        advanceUntilIdle()
        assertEquals(3, repo.commentsCalls.size)
        assertNull(repo.commentsCalls.last().first)
        assertEquals(listOf("t0", "t1"), readyThreads(vm).map { it.id })
        val restored = CommentsViewModel(repo, restoredHandle(handle), "ignored")
        advanceUntilIdle()
        assertEquals(4, repo.commentsCalls.size)
        assertEquals(listOf("t0", "t1"), readyThreads(restored).map { it.id })
    }

    @Test fun recreationRefetchesOnlyTheLoadedDepth() = runTest {
        val repo = pagingRepository()
        val handle = SavedStateHandle()
        val vm = CommentsViewModel(repo, handle, "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(2, repo.commentsCalls.size)
        val restored = CommentsViewModel(repo, restoredHandle(handle), "ignored")
        advanceUntilIdle()
        assertEquals(4, repo.commentsCalls.size)
        assertEquals(listOf("t0", "t1", "t2", "t3"), readyThreads(restored).map { it.id })
    }

    @Test fun emptyRepliesFetchOnceAndNeverAgain() = runTest {
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 3)), null) },
            repliesHandler = { _, cursor ->
                if (cursor == null) RepliesPage(emptyList(), null) else error("no further pages")
            },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null), repo.repliesCalls)
        val loaded = readyThreads(vm).first()
        assertTrue(loaded.replies.isEmpty())
        assertNull(loaded.repliesNextCursor)
        vm.onAction(CommentAction.ToggleReplies("c1"))
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(1, repo.repliesCalls.size)
    }

    @Test fun repliesPaginateOnePagePerActionAndSurviveCollapse() = runTest {
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 3)), null) },
            repliesHandler = { _, cursor ->
                when (cursor) {
                    null -> RepliesPage(listOf(reply("r1")), "k2")
                    "k2" -> RepliesPage(listOf(reply("r2")), null)
                    else -> error("never")
                }
            },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null), repo.repliesCalls)
        assertEquals(listOf("r1"), readyThreads(vm).first().replies.map { it.id })
        vm.onAction(CommentAction.LoadMoreReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null, "c1" to "k2"), repo.repliesCalls)
        assertEquals(listOf("r1", "r2"), readyThreads(vm).first().replies.map { it.id })
        vm.onAction(CommentAction.ToggleReplies("c1"))
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(2, repo.repliesCalls.size)
        assertEquals(listOf("r1", "r2"), readyThreads(vm).first().replies.map { it.id })
    }

    @Test fun aRepliesCursorLoopEndsTheThread() = runTest {
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 9)), null) },
            repliesHandler = { _, cursor ->
                when (cursor) {
                    null -> RepliesPage(listOf(reply("r1")), "k2")
                    "k2" -> RepliesPage(listOf(reply("r2")), "k2")
                    else -> error("never")
                }
            },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMoreReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null, "c1" to "k2"), repo.repliesCalls)
        val thread = readyThreads(vm).first()
        assertEquals(listOf("r1", "r2"), thread.replies.map { it.id })
        assertNull(thread.repliesNextCursor)
        vm.onAction(CommentAction.LoadMoreReplies("c1"))
        advanceUntilIdle()
        assertEquals(2, repo.repliesCalls.size)
    }

    @Test fun aReplyContinuationPointingToItsOpeningKeyIsNeverFetchedTwice() = runTest {
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 9)), null) },
            repliesHandler = { _, _ -> RepliesPage(listOf(reply("r1")), "rk") },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertNull(readyThreads(vm).first().repliesNextCursor)
        repeat(3) { vm.onAction(CommentAction.LoadMoreReplies("c1")) }
        vm.onAction(CommentAction.ToggleReplies("c1"))
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null), repo.repliesCalls)
    }

    @Test fun aReplyCycleReturningToItsOpeningKeyStopsBeforeRefetchingIt() = runTest {
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 9)), null) },
            repliesHandler = { _, cursor ->
                if (cursor == null) RepliesPage(listOf(reply("r1")), "k2")
                else RepliesPage(listOf(reply("r2")), "rk")
            },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMoreReplies("c1"))
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMoreReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null, "c1" to "k2"), repo.repliesCalls)
        assertEquals(listOf("r1", "r2"), readyThreads(vm).first().replies.map { it.id })
        assertNull(readyThreads(vm).first().repliesNextCursor)
    }

    @Test fun reopeningResumesOnlyTheCancelledExpandedFirstReplyRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("2", listOf(
                thread("open", replyKey = "rk", replyCount = 2),
                thread("closed", replyKey = "other", replyCount = 2)), null) },
            repliesHandler = { _, _ -> gate.await(); RepliesPage(listOf(reply("r1")), null) },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("open"))
        runCurrent()
        vm.cancelTransient()
        vm.ensureLoaded()
        runCurrent()
        assertEquals(listOf("open" to null, "open" to null), repo.repliesCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("r1"), readyThreads(vm).first().replies.map { it.id })
        vm.cancelTransient()
        vm.ensureLoaded()
        advanceUntilIdle()
        assertEquals(2, repo.repliesCalls.size)
        assertEquals(1, repo.commentsCalls.size)
    }

    @Test fun aCommentsCursorLoopClosesTheList() = runTest {
        val repo = CountingRepository({ cursor, _ ->
            when (cursor) {
                null -> CommentsPage("9", listOf(thread("a1")), "b")
                "b" -> CommentsPage("9", listOf(thread("b1")), "a")
                "a" -> CommentsPage("9", listOf(thread("a2")), "b")
                else -> error("never")
            }
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf<Pair<String?, CommentSort>>(null to CommentSort.Top, "b" to CommentSort.Top, "a" to CommentSort.Top), repo.commentsCalls)
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(listOf("a1", "b1", "a2"), ready.threads.map { it.id })
        assertNull(ready.nextCursor)
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(3, repo.commentsCalls.size)
    }

    @Test fun aFailedRepliesPageRetriesExactlyThatPage() = runTest {
        var failK2 = true
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 3)), null) },
            repliesHandler = { _, cursor ->
                when (cursor) {
                    null -> RepliesPage(listOf(reply("r1")), "k2")
                    "k2" -> if (failK2) throw IOException("boom") else RepliesPage(listOf(reply("r2")), null)
                    else -> error("never")
                }
            },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMoreReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null, "c1" to "k2"), repo.repliesCalls)
        assertTrue(readyThreads(vm).first().repliesFailed)
        failK2 = false
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(listOf("c1" to null, "c1" to "k2", "c1" to "k2"), repo.repliesCalls)
        val thread = readyThreads(vm).first()
        assertFalse(thread.repliesFailed)
        assertEquals(listOf("r1", "r2"), thread.replies.map { it.id })
    }

    @Test fun switchingVideosCancelsInFlightReadsAndNeverPublishesStalePages() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = CountingRepository({ cursor, _ ->
            gate.await()
            CommentsPage("1", listOf(thread("t-${cursor ?: "first"}")), null)
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        runCurrent()
        assertEquals(1, repo.commentsCalls.size)
        // A fast switch (the Shorts swipe case) cancels the read before it lands.
        vm.selectVideo("w")
        runCurrent()
        assertEquals(2, repo.commentsCalls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repo.commentsCalls.size)
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(listOf("t-first"), ready.threads.map { it.id })
        assertEquals("w", vm.uiState.value.videoId)
    }

    @Test fun aFailedPageIsRetriedAsExactlyThatPage() = runTest {
        var failB = true
        val repo = CountingRepository({ cursor, _ ->
            when (cursor) {
                null -> CommentsPage("9", listOf(thread("a1")), "b")
                "b" -> if (failB) throw IOException("boom") else CommentsPage("9", listOf(thread("b1")), null)
                else -> error("never")
            }
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf<Pair<String?, CommentSort>>(null to CommentSort.Top, "b" to CommentSort.Top), repo.commentsCalls)
        assertTrue((vm.uiState.value.content as CommentsContent.Ready).loadMoreFailed)
        failB = false
        vm.onAction(CommentAction.Retry)
        advanceUntilIdle()
        assertEquals(listOf(null, "b", "b"), repo.commentsCalls.map { it.first })
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(listOf("a1", "b1"), ready.threads.map { it.id })
        assertFalse(ready.loadMoreFailed)
    }
    @Test fun closingPanelDoesNotRefetchAnsweredEmptyReplies() = runTest {
        val repo = CountingRepository(
            commentsHandler = { _, _ -> CommentsPage("1", listOf(thread("c1", replyKey = "rk", replyCount = 3)), null) },
            repliesHandler = { _, _ -> RepliesPage(emptyList(), null) },
        )
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        vm.cancelTransient()
        vm.ensureLoaded()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        vm.onAction(CommentAction.ToggleReplies("c1"))
        advanceUntilIdle()
        assertEquals(1, repo.repliesCalls.size)
    }

    @Test fun cancelledAppendCanBeRequestedAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = CountingRepository({ cursor, _ ->
            if (cursor != null) gate.await()
            CommentsPage("2", listOf(thread(if (cursor == null) "a" else "b")), if (cursor == null) "next" else null)
        })
        val vm = CommentsViewModel(repo, SavedStateHandle(), "v")
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        runCurrent()
        vm.cancelTransient()
        gate.complete(Unit)
        runCurrent()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), readyThreads(vm).map { it.id })
        assertEquals(listOf(null, "next", "next"), repo.commentsCalls.map { it.first })
    }

}
