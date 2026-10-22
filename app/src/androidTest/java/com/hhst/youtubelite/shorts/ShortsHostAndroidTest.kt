package com.hhst.youtubelite.shorts

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.HomeNavigationRequest
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.domain.ShortsNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShortsHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null

    private fun launch(scene: ShortsScenario = ShortsScenario.Content, startItem: String? = null): ActivityScenario<ShortsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<ShortsActivity>(ShortsActivity.intent(context, startItem, scene)).also {
            scenario = it
            compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("shorts-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        }
    }

    @After fun close() {
        scenario?.close()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun pagerRendersTheMeasuredRailAndTypedRailActions() {
        val host = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shorts-pager").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("short-page-short-1").assertIsDisplayed()
        compose.onNodeWithTag("short-title-short-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Like A whole world in a single frame").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(ShortsNavigationRequest.Rate("short-1", true), it.lastNavigationRequest) }
        compose.onNodeWithContentDescription("Comments for A whole world in a single frame").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("comments-panel-host").fetchSemanticsNodes().isNotEmpty() }
        // Closing the comments overlay keeps the pager underneath alive.
        compose.onAllNodesWithContentDescription("Close comments").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shorts-pager").assertIsDisplayed()
    }

    @Test fun menuStaysOnThePagerAndEmitsOneTypedAction() {
        val host = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shorts-pager").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More actions for A whole world in a single frame").performClick()
        compose.onNodeWithTag("video-menu").assertIsDisplayed()
        compose.onNodeWithText("Not interested").performClick()
        compose.waitForIdle()
        host.onActivity {
            assertEquals(ShortsNavigationRequest.MenuAction("short-1", com.hhst.youtubelite.home.domain.FeedMenuAction.NotInterested), it.lastNavigationRequest)
        }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        compose.onNodeWithTag("shorts-pager").assertIsDisplayed()
    }

    @Test fun playerViewModelIdentitySurvivesOverlays() {
        val host = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shorts-pager").fetchSemanticsNodes().isNotEmpty() }
        lateinit var before: PlayerViewModel
        host.onActivity { before = it.suspendingPlayerViewModel() }
        compose.onNodeWithContentDescription("Comments for A whole world in a single frame").performClick()
        compose.waitForIdle()
        host.onActivity { assertSame(before, it.suspendingPlayerViewModel()) }
        compose.onAllNodesWithContentDescription("Close comments").onFirst().performClick()
        compose.waitForIdle()
        host.onActivity { assertSame(before, it.suspendingPlayerViewModel()) }
    }

    @Test fun commentsFollowTheSettledPageAndNestedBackKeepsThePanel() {
        val host = launch(startItem = "short-2")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shorts-pager").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shorts-pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        var selected = ""
        host.onActivity { selected = it.shortsViewModel.uiState.value.currentItemId.orEmpty() }
        assertNotEquals("short-2", selected)
        host.onActivity { it.shortsViewModel.onAction(ShortsAction.OpenComments(selected)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("comments-list").fetchSemanticsNodes().isNotEmpty() }
        host.onActivity {
            assertEquals(selected, it.commentsViewModel.uiState.value.videoId)
            it.commentsViewModel.onAction(com.hhst.youtubelite.comments.domain.CommentAction.Compose)
        }
        compose.onNodeWithTag("comment-composer").assertExists()
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("comment-composer").assertDoesNotExist()
        compose.onNodeWithTag("comments-panel-host").assertIsDisplayed()
        host.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("comments-list").fetchSemanticsNodes().isNotEmpty() }
        host.onActivity {
            assertEquals(selected, it.commentsViewModel.uiState.value.videoId)
            assertEquals(selected, it.shortsViewModel.uiState.value.currentItemId)
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("comments-panel-host").assertDoesNotExist()
        compose.onNodeWithTag("short-page-$selected").assertIsDisplayed()
    }

    @Test fun homeShelfRoutesShortsIntoTheShortsHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val home = ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_SCENARIO, HomeScenario.Feed.argument)).also { scenario = it }
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasTestTag("shorts-shelf"))
        compose.onNodeWithTag("short-short-1").performClick()
        compose.waitForIdle()
        home.onActivity { assertEquals(HomeNavigationRequest.Shorts("short-1"), it.lastNavigationRequest) }
        // The typed request starts the shorts host directly on that item.
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodesWithTag("short-page-short-1").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty()
        }
        compose.onNodeWithTag("short-page-short-1").assertIsDisplayed()
        finishSpawnedShorts()
    }
}

private fun finishSpawnedShorts() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<ShortsActivity>()
            .forEach { it.finish() }
    }
    instrumentation.waitForIdleSync()
}

private fun ShortsActivity.suspendingPlayerViewModel(): PlayerViewModel =
    org.koin.java.KoinJavaComponent.getKoin().get<PlayerViewModel>()
