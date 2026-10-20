package com.hhst.youtubelite.menus

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.comments.data.CommentsScenario
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.watch.WatchActivity
import com.hhst.youtubelite.watch.domain.WatchAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchMenuLayerAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pageMenuCoversDockedCommentsAndBackRestoresThePanelAndSurface() {
        ActivityScenario.launch<WatchActivity>(WatchActivity.commentsIntent(
            ApplicationProvider.getApplicationContext(), "bunny", CommentsScenario.Content,
        )).use { host ->
            host.onActivity { assertTrue("Use a landscape host at least 931dp wide", it.resources.configuration.screenWidthDp >= 931) }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("comments-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("watch-title", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("More actions for City light trails — a night walk through the old harbour").assertDoesNotExist()
            val surfaces = mutableListOf<SurfaceView>()
            host.onActivity { activity ->
                fun visit(view: View) {
                    if (view is SurfaceView) surfaces += view
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(activity.window.decorView)
                assertEquals(1, surfaces.size)
                activity.commentsViewModel.onAction(CommentAction.ToggleReplies("c1"))
                activity.watchViewModel.onAction(WatchAction.OpenItemMenu("harbour"))
            }
            compose.onNodeWithTag("watch-video-menu").assertIsDisplayed()
            compose.onNodeWithTag("comments-list").assertDoesNotExist()
            host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("watch-video-menu").assertDoesNotExist()
            compose.onNodeWithTag("comments-list").assertIsDisplayed()
            compose.onNodeWithTag("comment-c1-replies").assertExists()
            host.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(activity.watchViewModel.uiState.value.commentsOpen)
                assertTrue(surfaces.single().isAttachedToWindow)
            }
        }
    }
}
