package com.hhst.youtubelite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.comments.presentation.CommentsPanel
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.presentation.SaveSheetLive
import com.hhst.youtubelite.saves.presentation.SaveToPlaylistSheet
import com.hhst.youtubelite.ui.design.AppTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WriteContinuityAndroidTest {
    @get:Rule val compose = createComposeRule()
    private var model: CommentsViewModel? = null
    @After fun cleanup() { compose.runOnIdle { model?.dispose() } }

    @Test fun ratingPreservesCommentViewportAndExpandedRepliesWhileShowingProgressInPlace() {
        val author = CommentAuthor("Reader", "@reader", "")
        val gate = CompletableDeferred<CommentMutation>()
        var reads = 0
        val repository = object : CommentsRepository, CommentActions {
            override suspend fun comments(target: CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
                reads++
                return CommentsPage("30", (1..30).map { n ->
                    CommentThread("c$n", author, "", "Comment $n", "10", 1,
                        replies = listOf(CommentReply("r$n", author, "", "Reply $n", "1")))
                }, null)
            }
            override suspend fun perform(request: CommentNavigationRequest) = gate.await()
        }
        lateinit var vm: CommentsViewModel
        compose.runOnUiThread { vm = CommentsViewModel(repository, SavedStateHandle(), "video"); model = vm }
        lateinit var list: LazyListState
        compose.setContent { AppTheme {
            val state by vm.uiState.collectAsState()
            list = rememberLazyListState()
            CommentsPanel(state, "30", vm::onAction, {}, listState = list)
        } }
        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-c12"))
        compose.onNodeWithTag("comment-c12-reply-toggle").performClick()
        compose.onNodeWithTag("comment-c12-like").performScrollTo()
        val beforeTop = compose.onNodeWithTag("comment-c12").fetchSemanticsNode().boundsInRoot.top
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        compose.onNodeWithTag("comment-c12-like").performClick()
        compose.onNode(hasTestTag("write-progress") and hasAnyAncestor(hasTestTag("comment-c12-like")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(beforeTop, compose.onNodeWithTag("comment-c12").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        compose.onNodeWithTag("comments-loading", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { gate.complete(CommentMutation()) }
        compose.onNodeWithTag("comment-c12-like").assertIsSelected()
        compose.onNodeWithTag("reply-r12").assertExists()
        compose.runOnIdle {
            assertEquals(index, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            assertEquals(setOf("c12"), vm.uiState.value.expanded)
            assertEquals(1, reads)
        }
    }

    @Test fun saveProgressDoesNotMoveRowsOrLeaveTheSheetInvisibleAfterBackDuringAWrite() {
        val pending = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var toggles = 0
        lateinit var back: OnBackPressedDispatcher
        compose.setContent { AppTheme { Box(Modifier.fillMaxSize()) {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            if (visible.value) SaveToPlaylistSheet(emptyList(), { visible.value = false }, {}, {},
                SaveSheetLive(listOf(SaveChoice("a", "Playlist A", false), SaveChoice("b", "Playlist B", true)),
                    false, pending.value, { toggles++ }, {}))
        } } }
        val before = compose.onNodeWithTag("save-menu-playlist-a").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { pending.value = true }
        val during = compose.onNodeWithTag("save-menu-playlist-a").fetchSemanticsNode().boundsInRoot
        assertEquals(before, during)
        compose.onNodeWithTag("save-menu-playlist-a").assertIsNotEnabled()
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithTag("save-to-playlist").assertIsDisplayed()
        compose.runOnIdle { pending.value = false }
        compose.onNodeWithTag("save-menu-playlist-a").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithTag("save-to-playlist").assertIsDisplayed()
        compose.runOnUiThread { back.onBackPressed() }
        compose.onNodeWithTag("save-to-playlist").assertDoesNotExist()
    }
}
