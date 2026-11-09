package com.hhst.youtubelite.comments

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.comments.data.CommentsScenario
import com.hhst.youtubelite.comments.domain.CommentNavigationRequest
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.watch.WatchActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class WatchCommentsHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var watch: ActivityScenario<WatchActivity>? = null

    private fun launch(
        videoId: String = "bunny",
        comments: CommentsScenario? = null,
        open: Boolean = false,
    ) = ActivityScenario.launch<WatchActivity>(
        if (comments == null) WatchActivity.intent(ApplicationProvider.getApplicationContext(), videoId)
        else WatchActivity.commentsIntent(ApplicationProvider.getApplicationContext(), videoId, comments, open),
    ).also {
        watch = it
        awaitNode(if (comments != null && open) "comments-panel" else "watch-screen")
    }

    @Before fun enterPhoneWindow() = com.hhst.youtubelite.core.PhoneWindow.enter()

    @After fun exitPhoneWindow() {
        com.hhst.youtubelite.core.PhoneWindow.exit()
    }

    @After fun close() {
        watch?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }
    private fun awaitGone(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) == 0 }

    private fun openPanel() {
        compose.onNodeWithTag("watch-comments-card").performClick()
        awaitNode("comments-panel")
        awaitNode("comments-list")
    }

    private fun systemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun surfaces(): List<View> {
        var found = emptyList<View>()
        watch?.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val result = mutableListOf<View>()
            fun walk(view: View) {
                if (view is android.view.SurfaceView) result += view
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(root)
            found = result
        }
        return found
    }

    @Test fun entryCardOpensThePanelAboveTheUnchangedPlayer() {
        val host = launch()
        awaitNode("watch-metadata")
        val playerBefore = surfaces()
        assertEquals("The real player surface must be present", 1, playerBefore.size)
        val requestsBefore = nodeCount("comments-panel")
        assertEquals(0, requestsBefore)

        compose.onNodeWithTag("watch-comments-card").performClick()
        awaitNode("comments-panel")
        awaitNode("comments-list")

        assertEquals(playerBefore, surfaces())
        assertTrue(surfaces().all { it.isAttachedToWindow })
        compose.onNodeWithTag("watch-comments-card").assertDoesNotExist()
        compose.onNodeWithTag("comments-close").assertExists()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun backClosesThePanelBeforeThePage() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()
        compose.runOnUiThread { host.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        awaitGone("comments-panel")
        compose.onNodeWithTag("watch-screen").assertExists()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun scrimAndCloseBothDismissThePanel() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()
        compose.onNodeWithTag("comments-close").performClick()
        awaitGone("comments-panel")

        openPanel()
        val scrim = compose.onNodeWithTag("comments-scrim").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("comments-scrim").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width / 2f, 40f))
        }
        awaitGone("comments-panel")
        assertTrue(scrim.height > 0f)
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun commentAndReplyMenusCrossTheHostBoundaryWithTheirIdentities() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()

        compose.onNodeWithTag("comment-c1-more").performScrollTo().performClick()
        awaitNode("comments-menu")
        compose.onAllNodesWithTag("comment-menu-edit").assertCountEquals(0)
        compose.onAllNodesWithTag("comment-menu-delete").assertCountEquals(0)
        compose.onNodeWithTag("comment-menu-reply").performClick()
        awaitNode("comment-composer")
        compose.onNodeWithTag("comment-composer-input").performTextInput("Nice detail")
        compose.onNodeWithTag("comment-composer-submit").performClick()
        compose.waitUntil(10_000) {
            var request: CommentNavigationRequest? = null
            host.onActivity { request = it.lastCommentRequest }
            request == CommentNavigationRequest.SubmitReply("bunny", "c1", "c1", "Nice detail")
        }
        awaitGone("comment-composer")
        compose.onNodeWithTag("comments-panel").assertExists()

        compose.onNodeWithTag("comment-c1-reply-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reply-c1r1-more").performScrollTo().performClick()
        awaitNode("comments-menu")
        compose.onNodeWithTag("comment-menu-report").performClick()
        compose.waitUntil(10_000) {
            var request: CommentNavigationRequest? = null
            host.onActivity { request = it.lastCommentRequest }
            request == CommentNavigationRequest.ReportComment("bunny", "c1", "c1r1")
        }
        awaitGone("comments-menu")
        compose.onNodeWithTag("comment-c1-replies", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("comments-panel").assertExists()
    }

    @Test fun backClosesTheCommentMenuBeforeThePanel() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()
        compose.onNodeWithTag("comment-c1-more").performScrollTo().performClick()
        awaitNode("comments-menu")

        compose.runOnUiThread { host.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        awaitGone("comments-menu")
        compose.waitForIdle()
        compose.onNodeWithTag("comments-panel").assertExists()
        host.onActivity { assertFalse(it.isFinishing) }

        compose.runOnUiThread { host.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        awaitGone("comments-panel")
        compose.onNodeWithTag("watch-screen").assertExists()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun openCommentMenuSurvivesActivityRecreationAndKeepsThePanel() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()
        compose.onNodeWithTag("comment-c1-more").performScrollTo().performClick()
        awaitNode("comments-menu")

        host.recreate()
        awaitNode("comments-panel")
        awaitNode("comments-menu")
        compose.onNodeWithTag("comment-menu-reply").assertIsDisplayed()
        compose.onNodeWithTag("comment-menu-report").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close menu").performClick()
        awaitGone("comments-menu")
        compose.onNodeWithTag("comments-panel").assertExists()
    }

    @Test fun sortAndExpandedRepliesSurviveRecreation() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()

        compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-c1-reply-toggle"))
        compose.onNodeWithTag("comment-c1-reply-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("comment-c1-replies", useUnmergedTree = true).assertExists()

        host.recreate()
        awaitNode("comments-panel")
        awaitNode("comments-list")
        compose.onNodeWithTag("comment-c1-replies", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("comments-sort").assertTextEquals("Top")

        compose.onNodeWithTag("comments-sort").performClick()
        compose.onNodeWithTag("comments-sort-newest").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("comments-sort").assertTextEquals("Newest")

        host.recreate()
        awaitNode("comments-panel")
        awaitNode("comments-list")
        compose.onNodeWithTag("comments-sort").assertTextEquals("Newest")
        compose.onNodeWithTag("comments-close").assertExists()
    }

    @Test fun authorHandOffReturnsToTheOpenPanel() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()
        compose.onNodeWithTag("comment-c1-reply-toggle").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("comment-c1-open-author").performClick()
        compose.waitUntil(10_000) {
            var request: CommentNavigationRequest? = null
            host.onActivity { request = it.lastCommentRequest }
            request is CommentNavigationRequest.OpenAuthor
        }
        host.onActivity {
            assertEquals(CommentNavigationRequest.OpenAuthor("blender"), it.lastCommentRequest)
        }
        compose.waitUntil(15_000) {
            var resumed = false
            host.onActivity { resumed = it.hasWindowFocus() }
            !resumed
        }

        systemBack()
        awaitNode("comments-panel")
        compose.onNodeWithTag("comment-c1-replies", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("comments-close").assertExists()
    }

    @Test fun pinnedScenariosRenderTheirPanelState() {
        for ((scenario, tag) in listOf(
            CommentsScenario.Empty to "comments-message",
            CommentsScenario.Disabled to "comments-message",
            CommentsScenario.Error to "comments-error",
        )) {
            val host = launch(comments = scenario, open = true)
            awaitNode("comments-panel")
            compose.waitUntil(15_000) { nodeCount(tag) > 0 }
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
            if (scenario == CommentsScenario.Disabled) {
                assertTrue(compose.onAllNodesWithTag("comments-input").fetchSemanticsNodes().isEmpty())
            } else {
                compose.onNodeWithTag("comments-input").assertExists()
            }
            host.close()
            watch = null
        }
    }

    @Test fun openingThePanelKeepsThePlayerIdentityAndState() {
        val host = launch()
        awaitNode("watch-metadata")
        val player = org.koin.core.context.GlobalContext.get().get<com.hhst.youtubelite.player.PlayerViewModel>()
        val before = player.uiState.value
        val surfaceBefore = surfaces()
        openPanel()
        assertEquals(before, player.uiState.value)
        assertEquals(surfaceBefore, surfaces())
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun loadedPagesAndScrollSurviveClosingAndRecreatingThePanel() {
        val host = launch()
        awaitNode("watch-metadata")
        openPanel()
        repeat(2) { step ->
            host.onActivity { it.commentsViewModel.onAction(com.hhst.youtubelite.comments.domain.CommentAction.LoadMore) }
            compose.waitUntil(5000) {
                var count = 0
                host.onActivity { count = (it.commentsViewModel.uiState.value.content as? com.hhst.youtubelite.comments.domain.CommentsContent.Ready)?.threads?.size ?: 0 }
                count >= if (step == 0) 8 else 9
            }
        }
        compose.onNodeWithTag("comments-list").performScrollToIndex(8)
        fun offset(): Float {
            compose.onNodeWithTag("comment-c8").assertIsDisplayed()
            return compose.onNodeWithTag("comment-c8").fetchSemanticsNode().positionInRoot.y -
                compose.onNodeWithTag("comments-list").fetchSemanticsNode().positionInRoot.y
        }
        val before = offset()
        compose.onNodeWithTag("comments-close").performClick()
        awaitGone("comments-panel")
        openPanel()
        assertEquals(before, offset(), 1f)
        host.recreate()
        awaitNode("comments-list")
        assertEquals(before, offset(), 1f)
    }

    @Test fun deviceScreenshotOfCommentMenu() {
        launch()
        awaitNode("watch-metadata")
        openPanel()
        compose.onNodeWithTag("comment-c1-more").performScrollTo().performClick()
        awaitNode("comments-menu")
        DeviceEvidence.captureScene("comments-menu-device")
    }

    @Test fun closingADebugOpenedPanelStaysClosedAfterRecreation() {
        val host = launch(comments = CommentsScenario.Content, open = true)
        awaitNode("comments-list")
        compose.onNodeWithTag("comments-close").performClick()
        host.recreate()
        awaitNode("watch-metadata")
        assertEquals(0, nodeCount("comments-panel"))
    }

    @Test fun editorTracksTheKeyboardWithoutMovingThePanel() {
        val host = launch()
        openPanel()
        val panelTop = compose.onNodeWithTag("comments-panel").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("comments-input").performClick()
        awaitNode("comment-composer")
        var keyboardTop = 0f
        var density = 1f
        compose.waitUntil(10_000) {
            var visible = false
            host.onActivity { activity ->
                val decor = activity.window.decorView
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                visible = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                keyboardTop = decor.height - (insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0).toFloat()
                density = activity.resources.displayMetrics.density
            }
            visible
        }
        compose.waitForIdle()
        val editorBottom = compose.onNodeWithTag("comment-composer").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(8f, (keyboardTop - editorBottom) / density, 2f)
        assertEquals(panelTop, compose.onNodeWithTag("comments-panel").fetchSemanticsNode().boundsInRoot.top, 2f)
    }
}
