package com.hhst.youtubelite.comments

import com.hhst.youtubelite.watch.domain.VideoRating
import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsOverlay
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
import kotlinx.coroutines.launch
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
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsViewModelTest {
    @Test fun authorizationRefreshPublishesLoadedPagesTogetherAndKeepsRepliesAndDraft() = runTest {
        val author = com.hhst.youtubelite.comments.domain.CommentAuthor("Reader", "", "")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var refreshing = false
        val repository = object : CommentsRepository {
            override suspend fun comments(target: com.hhst.youtubelite.comments.domain.CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
                if (refreshing) gate.await()
                val n = if (cursor == null) 1 else 2
                return CommentsPage("2", listOf(com.hhst.youtubelite.comments.domain.CommentThread("c$n", author, "", "Comment $n", "1", 1,
                    replies = if (refreshing) emptyList() else listOf(com.hhst.youtubelite.comments.domain.CommentReply("r$n", author, "", "Reply", "1")))),
                    if (n == 1) "second" else null)
            }
        }
        val target = com.hhst.youtubelite.comments.domain.CommentTarget.Post(com.hhst.youtubelite.post.domain.PostIdentity("studio", "post"))
        val vm = CommentsViewModel(repository, SavedStateHandle(), target)
        advanceUntilIdle()
        vm.onAction(CommentAction.LoadMore)
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c2"))
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("Keep draft"))
        val before = vm.uiState.value.content
        refreshing = true
        vm.refreshAuthorization()
        runCurrent()
        assertEquals(before, vm.uiState.value.content)
        assertTrue(vm.uiState.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(listOf("c1", "c2"), ready.threads.map { it.id })
        assertEquals("r2", ready.threads[1].replies.single().id)
        assertEquals(setOf("c2"), vm.uiState.value.expanded)
        assertEquals("Keep draft", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.overlay is CommentsOverlay.Composer)
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test fun submittingAfterPostAuthorizationWaitsForFreshActionTokens() = runTest {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        var refresh = false
        var writes = 0
        val repository = object : CommentsRepository, com.hhst.youtubelite.comments.domain.CommentActions {
            override suspend fun comments(target: com.hhst.youtubelite.comments.domain.CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
                if (refresh) ready.await()
                return CommentsPage("", emptyList(), null)
            }
            override suspend fun perform(request: CommentNavigationRequest): com.hhst.youtubelite.comments.domain.CommentMutation {
                writes++
                return com.hhst.youtubelite.comments.domain.CommentMutation()
            }
        }
        val target = com.hhst.youtubelite.comments.domain.CommentTarget.Post(com.hhst.youtubelite.post.domain.PostIdentity("studio", "post"))
        val vm = CommentsViewModel(repository, SavedStateHandle(), target)
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("Ready after authorization"))
        refresh = true
        vm.refreshAuthorization()
        vm.onAction(CommentAction.SubmitDraft)
        runCurrent()
        assertEquals(0, writes)
        assertTrue(vm.uiState.value.actionPending)
        ready.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test fun postAuthorizationKeepsTheComposerDraftAndRefreshDoesNotSubmitIt() = runTest {
        var writes = 0
        val repository = object : CommentsRepository by com.hhst.youtubelite.post.data.FixturePostCommentsRepository(),
            com.hhst.youtubelite.comments.domain.CommentActions {
            override suspend fun perform(request: CommentNavigationRequest): com.hhst.youtubelite.comments.domain.CommentMutation {
                writes++
                throw com.hhst.youtubelite.post.domain.PostAuthorizationRequiredException()
            }
        }
        val target = com.hhst.youtubelite.comments.domain.CommentTarget.Post(com.hhst.youtubelite.post.domain.PostIdentity("studio", "post"))
        val vm = CommentsViewModel(repository, SavedStateHandle(), target)
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("Keep this comment"))
        vm.onAction(CommentAction.SubmitDraft)
        advanceUntilIdle()
        assertEquals(Unit, vm.authorizationRequests.first())
        assertEquals("Keep this comment", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.overlay is CommentsOverlay.Composer)
        vm.refreshAuthorization()
        advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals("Keep this comment", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.overlay is CommentsOverlay.Composer)
    }

    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun startupDefersCommentReadsUntilReleasedAndUsesTheCurrentVideo() = runTest {
        val repository = GatedActions()
        val vm = CommentsViewModel(repository, SavedStateHandle(), "first", loadOnStart = false)
        vm.selectVideo("second")
        vm.refreshSession()
        advanceUntilIdle()
        assertEquals(0, repository.loadCalls)
        vm.startLoading()
        vm.startLoading()
        advanceUntilIdle()
        assertEquals(1, repository.loadCalls)
        assertEquals("second", vm.uiState.value.videoId)
    }

    @Test fun explicitCommentInteractionDoesNotWaitForPlayback() = runTest {
        val repository = GatedActions()
        val vm = CommentsViewModel(repository, SavedStateHandle(), "bunny", loadOnStart = false)
        vm.onAction(CommentAction.Retry)
        advanceUntilIdle()
        assertEquals(1, repository.loadCalls)
        assertTrue(vm.uiState.value.content is CommentsContent.Ready)
    }

    private class GatedActions : com.hhst.youtubelite.comments.domain.CommentActions,
        CommentsRepository by FixtureCommentsRepository() {
        val requests = mutableListOf<CommentNavigationRequest>()
        private val gates = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Unit>>()
        var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        fun nextGate() = kotlinx.coroutines.CompletableDeferred<Unit>().also { gates.add(it) }
        override suspend fun perform(request: CommentNavigationRequest): com.hhst.youtubelite.comments.domain.CommentMutation {
            requests += request
            gates.removeFirst().await()
            return com.hhst.youtubelite.comments.domain.CommentMutation()
        }
        var loadCalls = 0
        override suspend fun comments(target: com.hhst.youtubelite.comments.domain.CommentTarget,
            sort: CommentSort, cursor: String?): CommentsPage {
            loadCalls++
            loadGate?.let { it.await() }
            return FixtureCommentsRepository().comments("bunny", sort, cursor)
        }
    }

    private fun repository(block: suspend (String?, CommentSort) -> CommentsPage) = object : CommentsRepository {
        override suspend fun comments(target: com.hhst.youtubelite.comments.domain.CommentTarget, sort: CommentSort, cursor: String?) = block(cursor, sort)
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
            CommentAction.LikeThread("c1") to CommentNavigationRequest.LikeComment("bunny", "c1", VideoRating.Like, VideoRating.None),
            CommentAction.DislikeThread("c1") to CommentNavigationRequest.DislikeComment("bunny", "c1", VideoRating.Dislike, VideoRating.None),
            CommentAction.ReportThread("c1") to CommentNavigationRequest.ReportComment("bunny", "c1", "c1"),
            CommentAction.OpenThreadMenu("c1") to CommentNavigationRequest.OpenCommentMenu("bunny", "c1"),
            CommentAction.OpenAuthor("blender") to CommentNavigationRequest.OpenAuthor("blender"),
            CommentAction.LikeReply("c1", "c1r1") to CommentNavigationRequest.LikeComment("bunny", "c1r1", VideoRating.Like, VideoRating.None),
            CommentAction.DislikeReply("c1", "c1r1") to CommentNavigationRequest.DislikeComment("bunny", "c1r1", VideoRating.Dislike, VideoRating.None),
            CommentAction.ReportReply("c1", "c1r1") to CommentNavigationRequest.ReportComment("bunny", "c1", "c1r1"),
            CommentAction.OpenReplyMenu("c1", "c1r1") to CommentNavigationRequest.OpenReplyMenu("bunny", "c1", "c1r1"),
        )
        for ((action, expected) in expectations) {
            vm.onAction(action)
            assertEquals(expected, vm.navigationRequests.first())
            assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        }
        assertTrue(vm.uiState.value.content is CommentsContent.Ready)
        assertTrue(vm.uiState.value.expanded.isEmpty())
    }

    @Test fun composerSubmitsTypedTextWithoutFakingAPostedComment() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        assertEquals(CommentsOverlay.Composer(), vm.uiState.value.overlay)
        vm.onAction(CommentAction.DraftChanged("  A new thought  "))
        vm.onAction(CommentAction.SubmitDraft)
        assertEquals(CommentNavigationRequest.SubmitComment("bunny", "A new thought"), vm.navigationRequests.first())
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(4, (vm.uiState.value.content as CommentsContent.Ready).threads.size)

        vm.onAction(CommentAction.Reply("c1"))
        assertEquals(CommentsOverlay.Composer("c1", null, editing = false), vm.uiState.value.overlay)
        vm.onAction(CommentAction.DraftChanged("Reply body"))
        vm.onAction(CommentAction.SubmitDraft)
        assertEquals(CommentNavigationRequest.SubmitReply("bunny", "c1", "c1", "Reply body"), vm.navigationRequests.first())

        vm.onAction(CommentAction.Reply("c1", "c1r1"))
        vm.onAction(CommentAction.DraftChanged("Reply to reply"))
        vm.onAction(CommentAction.SubmitDraft)
        assertEquals(
            CommentNavigationRequest.SubmitReply("bunny", "c1", "c1r1", "Reply to reply"),
            vm.navigationRequests.first(),
        )
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("   "))
        vm.onAction(CommentAction.SubmitDraft)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun ownerOnlyRowsOpenEditAndDeleteWithoutChangingTheFixture() = runTest {
        val repo = repository { cursor, sort ->
            val page = FixtureCommentsRepository().comments("bunny", sort, cursor)
            page.copy(threads = page.threads.mapIndexed { index, thread ->
                if (index != 0) thread else thread.copy(
                    ownedByViewer = true,
                    replies = thread.replies.mapIndexed { replyIndex, reply ->
                        if (replyIndex == 0) reply.copy(ownedByViewer = true) else reply
                    },
                )
            })
        }
        val vm = CommentsViewModel(repo, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.OpenThreadMenu("c1"))
        assertEquals(CommentNavigationRequest.OpenCommentMenu("bunny", "c1"), vm.navigationRequests.first())
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Edit))
        assertEquals(CommentsOverlay.Composer("c1", null, editing = true), vm.uiState.value.overlay)
        assertEquals("The lighting still holds up. Every rewatch turns up a detail I missed the first time.", vm.uiState.value.draft)
        vm.onAction(CommentAction.DraftChanged("edited body"))
        vm.onAction(CommentAction.SubmitDraft)
        assertEquals(
            CommentNavigationRequest.EditComment("bunny", "c1", "c1", "edited body"),
            vm.navigationRequests.first(),
        )
        vm.onAction(CommentAction.RequestDelete("c1"))
        assertEquals(CommentsOverlay.DeleteConfirm("c1", null), vm.uiState.value.overlay)
        vm.onAction(CommentAction.ConfirmDelete)
        assertEquals(CommentNavigationRequest.DeleteComment("bunny", "c1", "c1"), vm.navigationRequests.first())
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)

        vm.onAction(CommentAction.OpenReplyMenu("c1", "c1r1"))
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Delete))
        assertEquals(CommentsOverlay.DeleteConfirm("c1", "c1r1"), vm.uiState.value.overlay)
        vm.onAction(CommentAction.CancelDelete)
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun publicCommentsNeverExposeOwnerOnlyMenuRows() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertEquals(
            listOf(CommentMenuAction.Reply, CommentMenuAction.Report),
            CommentMenuAction.forComment(ownedByViewer = false),
        )
        assertEquals(
            listOf(CommentMenuAction.Reply, CommentMenuAction.Report, CommentMenuAction.Edit, CommentMenuAction.Delete),
            CommentMenuAction.forComment(ownedByViewer = true),
        )
        vm.onAction(CommentAction.OpenThreadMenu("c1"))
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Edit))
        assertEquals(CommentsOverlay.ThreadMenu("c1"), vm.uiState.value.overlay)
        vm.onAction(CommentAction.RequestDelete("c1"))
        assertEquals(CommentsOverlay.ThreadMenu("c1"), vm.uiState.value.overlay)
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

    @Test fun threadAndReplyMenusKeepTheThreadAndCommentIdentitiesApart() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.OpenThreadMenu("c1"))
        assertEquals(CommentsOverlay.ThreadMenu("c1"), vm.uiState.value.overlay)
        assertEquals(CommentNavigationRequest.OpenCommentMenu("bunny", "c1"), vm.navigationRequests.first())
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Reply))
        assertEquals(CommentsOverlay.Composer("c1", null, editing = false), vm.uiState.value.overlay)
        vm.onAction(CommentAction.DraftChanged("A reply"))
        vm.onAction(CommentAction.SubmitDraft)
        assertEquals(CommentNavigationRequest.SubmitReply("bunny", "c1", "c1", "A reply"), vm.navigationRequests.first())
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)
        vm.onAction(CommentAction.OpenThreadMenu("c1"))
        assertEquals(CommentNavigationRequest.OpenCommentMenu("bunny", "c1"), vm.navigationRequests.first())
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Report))
        assertEquals(CommentNavigationRequest.ReportComment("bunny", "c1", "c1"), vm.navigationRequests.first())
        vm.onAction(CommentAction.OpenReplyMenu("c1", "c1r1"))
        assertEquals(CommentsOverlay.ReplyMenu("c1", "c1r1"), vm.uiState.value.overlay)
        assertEquals(CommentNavigationRequest.OpenReplyMenu("bunny", "c1", "c1r1"), vm.navigationRequests.first())
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Reply))
        assertEquals(CommentsOverlay.Composer("c1", "c1r1", editing = false), vm.uiState.value.overlay)
        vm.onAction(CommentAction.DraftChanged("A reply to a reply"))
        vm.onAction(CommentAction.SubmitDraft)
        assertEquals(
            CommentNavigationRequest.SubmitReply("bunny", "c1", "c1r1", "A reply to a reply"),
            vm.navigationRequests.first(),
        )
        vm.onAction(CommentAction.OpenReplyMenu("c1", "c1r1"))
        assertEquals(CommentNavigationRequest.OpenReplyMenu("bunny", "c1", "c1r1"), vm.navigationRequests.first())
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Report))
        assertEquals(CommentNavigationRequest.ReportComment("bunny", "c1", "c1r1"), vm.navigationRequests.first())
    }

    @Test fun closingTheMenuKeepsThePanelExpansionAndLoadedPages() = runTest {
        val vm = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.ToggleReplies("c1"))
        vm.onAction(CommentAction.OpenThreadMenu("c1"))
        vm.onAction(CommentAction.CloseMenu)
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)
        assertTrue("c1" in vm.uiState.value.expanded)
        val ready = vm.uiState.value.content as CommentsContent.Ready
        assertEquals(4, ready.threads.size)
    }

    @Test fun openMenuSurvivesSavedStateReconstruction() = runTest {
        val handle = SavedStateHandle()
        val vm = CommentsViewModel(FixtureCommentsRepository(), handle, "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.OpenReplyMenu("c2", "c2r1"))
        var restored = CommentsViewModel(
            FixtureCommentsRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(CommentsOverlay.ReplyMenu("c2", "c2r1"), restored.uiState.value.overlay)
        vm.onAction(CommentAction.CloseMenu)
        restored = CommentsViewModel(
            FixtureCommentsRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(CommentsOverlay.None, restored.uiState.value.overlay)
    }

    @Test fun postSubmissionsRetainBothChannelAndPostIdentityAcrossRecreation() = runTest {
        val target = com.hhst.youtubelite.comments.domain.CommentTarget.Post(com.hhst.youtubelite.post.domain.PostIdentity("studio", "post-2"))
        val handle = SavedStateHandle()
        val vm = CommentsViewModel(com.hhst.youtubelite.post.data.FixturePostCommentsRepository(), handle, target)
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("A post reply"))
        val restored = CommentsViewModel(com.hhst.youtubelite.post.data.FixturePostCommentsRepository(), handle, "wrong-video")
        advanceUntilIdle()
        restored.onAction(CommentAction.SubmitDraft)
        val request = restored.navigationRequests.first()
        assertEquals(CommentNavigationRequest.SubmitComment(target, "A post reply"), request)
        assertEquals("", request.videoId)
        assertEquals(target, restored.uiState.value.target)
    }

    private fun actionsRepository(block: suspend (CommentNavigationRequest) -> Unit) = object :
        CommentsRepository by FixtureCommentsRepository(), com.hhst.youtubelite.comments.domain.CommentActions {
        override suspend fun perform(request: CommentNavigationRequest): com.hhst.youtubelite.comments.domain.CommentMutation {
            block(request)
            return com.hhst.youtubelite.comments.domain.CommentMutation()
        }
    }

    @Test fun postingKeepsDraftUntilConfirmedAndRejectsDuplicateSubmission() = runTest {
        val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val vm = CommentsViewModel(actionsRepository { calls++; reply.await() }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("Keep this draft"))
        vm.onAction(CommentAction.SubmitDraft)
        vm.onAction(CommentAction.SubmitDraft)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.actionPending)
        assertEquals("Keep this draft", vm.uiState.value.draft)
        reply.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
        assertEquals("", vm.uiState.value.draft)
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun failedPostingPreservesEditorAndDraftAcrossRecreation() = runTest {
        val saved = SavedStateHandle()
        val repository = actionsRepository { throw IOException("offline") }
        val vm = CommentsViewModel(repository, saved, "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("Do not discard"))
        vm.onAction(CommentAction.SubmitDraft)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
        assertEquals("Do not discard", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.overlay is CommentsOverlay.Composer)
        assertTrue(vm.actionNotices.first() is AppNotice.Failure)
        val restored = CommentsViewModel(repository, saved, "bunny")
        assertEquals("Do not discard", restored.uiState.value.draft)
        assertTrue(restored.uiState.value.overlay is CommentsOverlay.Composer)
    }

    @Test fun changingVideoCancelsPendingCommentWithoutChangingTheNewPanel() = runTest {
        val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = CommentsViewModel(actionsRepository { reply.await() }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.Compose)
        vm.onAction(CommentAction.DraftChanged("Old video"))
        vm.onAction(CommentAction.SubmitDraft)
        runCurrent()
        vm.selectVideo("sintel")
        reply.complete(Unit)
        advanceUntilIdle()
        assertEquals("sintel", vm.uiState.value.videoId)
        assertEquals("", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.actionPending)
    }

    @Test fun newestSortFallsBackToTopWithANoticeWhenTheLaneIsMissing() = runTest {
        val vm = CommentsViewModel(repository { _, sort ->
            val page = FixtureCommentsRepository().comments("bunny", CommentSort.Top, null)
            if (sort == CommentSort.Newest) page.copy(sortFallback = true) else page
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        vm.onAction(CommentAction.SelectSort(CommentSort.Newest))
        advanceUntilIdle()
        assertEquals(CommentSort.Top, vm.uiState.value.sort)
        assertTrue(vm.uiState.value.content is CommentsContent.Ready)
        assertEquals(AppNotice.Message("Newest comments aren't offered here. Showing top comments."),
            vm.actionNotices.first())
    }

    @Test fun changingSessionDropsTheComposerDraftAndOldRestoreDepth() = runTest {
        val state = SavedStateHandle()
        val model = CommentsViewModel(FixtureCommentsRepository(), state, "bunny")
        advanceUntilIdle()
        model.onAction(CommentAction.Compose)
        model.onAction(CommentAction.DraftChanged("Private draft"))
        assertEquals("Private draft", model.uiState.value.draft)
        model.refreshSession()
        assertEquals("", model.uiState.value.draft)
        assertEquals(CommentsOverlay.None, model.uiState.value.overlay)
        assertEquals("", state.get<String>("commentsDraft"))
        assertEquals(0, state.get<Int>("commentsLoadedCount"))
        advanceUntilIdle()
        model.dispose()
    }

    @Test fun aWriteCancelledByASessionSwitchCannotPublishStaleResults() = runTest {
        val actions = GatedActions()
        val notices = mutableListOf<AppNotice>()
        val gate1 = actions.nextGate()
        val vm = CommentsViewModel(actions, SavedStateHandle(), "bunny")
        backgroundScope.launch { vm.actionNotices.collect { notices.add(it) } }
        advanceUntilIdle()
        vm.onAction(CommentAction.OpenThreadMenu("c1"))
        vm.onAction(CommentAction.MenuAction(CommentMenuAction.Report))
        assertTrue(vm.uiState.value.actionPending)
        vm.refreshSession()
        assertFalse(vm.uiState.value.actionPending)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
        gate1.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
        assertEquals(CommentsOverlay.None, vm.uiState.value.overlay)
        assertTrue(notices.none { it == AppNotice.SignIn })
        assertEquals(2, actions.loadCalls)
    }
}
