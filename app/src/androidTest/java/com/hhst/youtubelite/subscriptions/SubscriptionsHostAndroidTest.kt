package com.hhst.youtubelite.subscriptions

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.account.SignInActivity
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.subscriptions.data.SubscriptionsScenario
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class SubscriptionsHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<SubscriptionsActivity>? = null

    private fun launch(signedIn: Boolean, scene: SubscriptionsScenario = SubscriptionsScenario.Content): ActivityScenario<SubscriptionsActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<SubscriptionsActivity>(SubscriptionsActivity.intent(context, signedIn)
            .putExtra(SubscriptionsActivity.EXTRA_SCENARIO, scene.argument)).also {
            scenario = it
            compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("subscriptions-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        }
    }

    @Before fun enterPhoneWindow() = com.hhst.youtubelite.core.PhoneWindow.enter()

    @After fun exitPhoneWindow() {
        com.hhst.youtubelite.core.PhoneWindow.exit()
    }

    @After fun close() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<SignInActivity>().forEach { it.finish() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun signedOutSubscriptionsShowTheSampledPrompt() {
        val host = launch(signedIn = false)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("subscriptions-signed-out").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sign in").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(SubscriptionsNavigationRequest.SignIn, it.lastNavigationRequest) }
        compose.onNodeWithTag("subscriptions-feed").assertDoesNotExist()
    }

    @Test fun feedOpensVideosAndChannelsStayReachableThroughManage() {
        val host = launch(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("subscriptions-feed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("subs-item-bunny").assertIsDisplayed()
        compose.onNodeWithTag("subs-item-bunny").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(SubscriptionsNavigationRequest.Watch("bunny"), it.lastNavigationRequest) }
        // Watching leaves this host; the channels surface is checked on a fresh launch.
        scenario?.close()
        val second = launch(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("subscriptions-feed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("subscriptions-manage").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("channels-overlay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("channels-row-blender").assertIsDisplayed()
        compose.onNodeWithTag("channels-row-blender").performClick()
        compose.onNodeWithTag("subscriptions-visit-channel").assertIsDisplayed()
        second.onActivity { assertNull(it.lastNavigationRequest); assertEquals("blender", it.subscriptionsViewModel.uiState.value.selectedChannel?.channelId) }
        compose.onNodeWithTag("subscriptions-visit-channel").performClick()
        compose.waitForIdle()
        second.onActivity { assertEquals(SubscriptionsNavigationRequest.Channel("blender"), it.lastNavigationRequest) }
    }

    @Test fun channelStripSelectsItsFeedAndOnlyVisitChannelOpensTheChannel() {
        val host = launch(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("subscriptions-feed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("subscriptions-channels").assertIsDisplayed()
        compose.onNodeWithTag("subs-strip-blender").assertIsDisplayed()
        compose.onNodeWithTag("subs-new-blender", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("subscriptions-chips").assertIsDisplayed()
        compose.onNodeWithTag("subscriptions-chips").performScrollToNode(hasText("Live"))
        compose.onNodeWithText("Live").assertIsDisplayed()
        compose.onNodeWithTag("subs-strip-blender").performClick()
        compose.onNodeWithTag("subs-strip-blender").assertIsSelected()
        compose.onNodeWithTag("subs-new-blender", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("subscriptions-chips").assertDoesNotExist()
        host.onActivity { assertNull(it.lastNavigationRequest) }
        compose.onNodeWithTag("subscriptions-visit-channel").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(SubscriptionsNavigationRequest.Channel("blender"), it.lastNavigationRequest) }
    }

    @Test fun allChannelsKeepsUnreadIndicatorsAndReturnsToTheFeed() {
        launch(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("subs-strip-blender").fetchSemanticsNodes().isNotEmpty() }
        val all = compose.onNodeWithTag("subscriptions-manage").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("subscriptions-channels").performTouchInput { swipeLeft() }
        assertEquals(all, compose.onNodeWithTag("subscriptions-manage").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("subscriptions-manage").performClick()
        compose.onNodeWithText("All subscriptions").assertIsDisplayed()
        compose.onNodeWithTag("channels-new-blender", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("channels-new-openmovies", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("subscriptions-feed").assertIsDisplayed()
        compose.onNodeWithTag("subs-new-blender", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun menuEmitsOnlyTheTypedActionAndStaysOnTheFeed() {
        val host = launch(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("subscriptions-feed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        compose.onNodeWithTag("video-menu").assertIsDisplayed()
        compose.onNodeWithText(FeedMenuAction.NotInterested.label).performClick()
        compose.waitForIdle()
        host.onActivity {
            assertEquals(SubscriptionsNavigationRequest.MenuAction("bunny", FeedMenuAction.NotInterested), it.lastNavigationRequest)
        }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        compose.onNodeWithTag("subscriptions-feed").assertIsDisplayed()
    }
}
