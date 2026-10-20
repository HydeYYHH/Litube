package com.hhst.youtubelite.channel

import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.App
import com.hhst.youtubelite.channel.data.ChannelScenario
import com.hhst.youtubelite.channel.domain.ChannelNavigationRequest
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.domain.SearchNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import com.hhst.youtubelite.core.DeviceEvidence

/** Host-level behavior of the local channel page: typed requests, tabs, returns and scenarios. */
class ChannelScreenAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var channel: ActivityScenario<ChannelActivity>? = null
    private var search: ActivityScenario<SearchResultsActivity>? = null

    @After fun close() {
        channel?.close()
        search?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }

    private fun launchChannel(scenario: ChannelScenario? = null) =
        ActivityScenario.launch<ChannelActivity>(
            ChannelActivity.intent(ApplicationProvider.getApplicationContext(), "blender", scenario)
        ).also {
            channel = it
            awaitNode("channel-page")
        }

    @Test fun normalLaunchShowsTheLocalFixtureWithoutNetworkOrLegacyRuntime() {
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        launchChannel()
        awaitNode("channel-header")
        compose.onNodeWithTag("channel-name").assertIsDisplayed()
        compose.onNodeWithTag("channel-home-list").assertIsDisplayed()
        channel?.onActivity { channelHost ->
            assertFalse((channelHost.application as App).legacyRuntimeStarted)
            fun containsWebView(view: View): Boolean =
                view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(channelHost.window.decorView))
        }
        SystemClock.sleep(1200)
        if (sent >= 0) assertEquals("Offline channel page sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (received >= 0) assertEquals("Offline channel page received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }

    @Test fun tabsSwitchLocallyAndKeepTheirScrollsAcrossSwitchesAndRecreation() {
        launchChannel()
        awaitNode("channel-home-list")
        compose.onNodeWithTag("channel-tab-Videos").performClick()
        awaitNode("channel-videos-list")
        compose.onNodeWithTag("channel-videos-list").performScrollToIndex(4)
        compose.onNodeWithTag("channel-video-behind").assertIsDisplayed()
        // The pinned tab bar stays reachable from deep in the list.
        compose.onNodeWithTag("channel-tab-Home").assertIsDisplayed()
        compose.onNodeWithTag("channel-tab-Home").performClick()
        awaitNode("channel-home-list")
        compose.onNodeWithTag("channel-videos-list").assertDoesNotExist()
        compose.onNodeWithTag("channel-tab-Videos").performClick()
        awaitNode("channel-videos-list")
        compose.onNodeWithTag("channel-video-behind").assertIsDisplayed()
        channel?.recreate()
        awaitNode("channel-videos-list")
        compose.onNodeWithTag("channel-video-behind").assertIsDisplayed()
    }

    @Test fun everyNonLocalSurfaceStaysATypedRequest() {
        val host = launchChannel()
        awaitNode("channel-header")
        compose.onNodeWithTag("channel-tab-Shorts").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.OtherTab(com.hhst.youtubelite.channel.domain.ChannelTab.Shorts), it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-home-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search within this channel").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.ChannelSearch, it.lastNavigationRequest) }
        compose.onNodeWithContentDescription("More channel options").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.SectionMenu, it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-subscribe").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.Subscribe("blender"), it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-description").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.FullDescription, it.lastNavigationRequest) }
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation")
            .performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.ItemMenu("bunny"), it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-subscribe").assertIsDisplayed()
    }

    @Test fun videoClickOpensTheExistingWatchHostAndBackReturnsToTheChannel() {
        val host = launchChannel()
        awaitNode("channel-video-bunny")
        compose.onNodeWithTag("channel-thumb-bunny").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.Watch("bunny"), it.lastNavigationRequest) }
        awaitNode("watch-screen")
        // GLOBAL_ACTION_BACK == 1; the watch host owns a different back control.
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        awaitNode("channel-home-list")
    }

    @Test fun enteringFromSearchKeepsTheResultsPageOnReturn() {
        val results = ActivityScenario.launch<SearchResultsActivity>(
            SearchResultsActivity.intent(ApplicationProvider.getApplicationContext(), "open movie")
        )
        search = results
        awaitNode("search-results-list")
        val first = FixtureSearchRepository.sampleResults().first()
        compose.onNodeWithTag("search-channel-${first.id}").performClick()
        results.onActivity { assertEquals(SearchNavigationRequest.Channel(first.channelId), it.lastNavigationRequest) }
        awaitNode("channel-page")
        awaitNode("channel-home-list")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        awaitNode("search-results-list")
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        results.onActivity { assertFalse(it.isFinishing) }
        results.close()
        search = null
    }

    @Test fun debugScenariosRenderTheirOwnState() {
        launchChannel(ChannelScenario.Empty)
        awaitNode("channel-empty")
        compose.onNodeWithTag("channel-empty").assertIsDisplayed()
        compose.onNodeWithText("This channel has no videos.").assertIsDisplayed()
        compose.onNodeWithTag("channel-videos-list", useUnmergedTree = false).assertDoesNotExist()
        channel?.close()
        launchChannel(ChannelScenario.Loading)
        awaitNode("channel-skeleton")
        compose.onNodeWithTag("channel-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("skeleton-thumb-0").assertIsDisplayed()
        channel?.close()
        launchChannel(ChannelScenario.Error)
        awaitNode("channel-error")
        compose.onNodeWithText("Retry").performClick()
        // The local fixture still fails after retry, so the same error surface stays.
        awaitNode("channel-error")
        compose.onNodeWithTag("channel-error").assertIsDisplayed()
    }

    @Test fun deviceScreenshotOfTheChannelPage() {
        launchChannel()
        awaitNode("channel-header")
        DeviceEvidence.captureScene("channel-device-final-390")
    }
}
