package com.hhst.youtubelite.post

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.post.domain.PostDetailNavigationRequest
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostMenuAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Post detail is local, identity-scoped and exits only through typed requests. */
class PostDetailHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var post: ActivityScenario<PostDetailActivity>? = null

    @After fun close() {
        post?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }

    private fun launch() = ActivityScenario.launch<PostDetailActivity>(
        PostDetailActivity.intent(ApplicationProvider.getApplicationContext(), "studio", "post")
    ).also {
        post = it
        awaitNode("post-detail-page")
        awaitNode("post-detail-body")
    }

    private fun counter(): String =
        compose.onNodeWithTag("post-image-counter").fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString("") { it }

    @Test fun bodyImagesFeedbackAndMenuStayLocalAndCarryPostIdentity() {
        val host = launch()
        compose.onNodeWithTag("post-detail-body").assertIsDisplayed()
        compose.onNodeWithTag("post-detail-author").assertIsDisplayed()
        assertEquals("Image 1 of 3", counter())
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("post-detail-like").performClick()
        host.onActivity {
            assertEquals(PostDetailNavigationRequest.Feedback(PostIdentity("studio", "post"), true), it.lastNavigationRequest)
        }
        compose.onNodeWithText("1.2K").assertIsDisplayed()

        compose.onNodeWithTag("post-detail-comments").performClick()
        host.onActivity {
            assertEquals(PostDetailNavigationRequest.Comments(PostIdentity("studio", "post")), it.lastNavigationRequest)
        }

        compose.onNodeWithTag("post-detail-more").performClick()
        awaitNode("post-detail-menu")
        compose.onNodeWithTag("post-detail-menu-report").performClick()
        host.onActivity {
            assertEquals(PostDetailNavigationRequest.MenuAction(PostIdentity("studio", "post"), PostMenuAction.Report), it.lastNavigationRequest)
        }
        compose.waitUntil(5_000) { nodeCount("post-detail-menu") == 0 }

        compose.onNodeWithTag("post-detail-more").performClick()
        awaitNode("post-detail-menu")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitUntil(5_000) { nodeCount("post-detail-menu") == 0 }
        compose.onNodeWithTag("post-detail-page").assertIsDisplayed()
        host.onActivity { assertFalse(it.isFinishing) }

        // The in-detail pager changes only the local image index, never navigation.
        compose.onNodeWithTag("post-detail-list").performScrollToNode(hasTestTag("post-detail-images"))
        compose.onNodeWithTag("post-images").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val swiped = counter()
        assertTrue("Expected a different local image, got $swiped", swiped == "Image 2 of 3" || swiped == "Image 3 of 3")
        compose.onNodeWithTag("watch-screen").assertDoesNotExist()

        host.recreate()
        awaitNode("post-detail-page")
        awaitNode("post-detail-body")
        assertEquals("The selected image must survive recreation", swiped, counter())
    }
}
