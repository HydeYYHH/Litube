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
        compose.onNodeWithTag("channel-title").assertIsDisplayed()
        compose.onNodeWithTag("channel-home-list").assertIsDisplayed()
        channel?.onActivity { channelHost ->
            assertFalse((channelHost.application as App).mediaRuntimeStarted)
            fun containsWebView(view: View): Boolean =
                view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(channelHost.window.decorView))
        }
        SystemClock.sleep(1200)
        if (sent >= 0) assertEquals("Offline channel page sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (received >= 0) assertEquals("Offline channel page received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }

    @Test fun switchingTabsDoesNotCollapseAnExpandedHeader() {
        launchChannel()
        awaitNode("channel-header")
        val top = compose.onNodeWithTag("channel-header").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("channel-tab-Videos").performClick()
        awaitNode("channel-videos-list")
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithTag("channel-header").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("channel-title").assertIsDisplayed()
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

    @Test fun unimplementedHeaderSurfacesStayTypedRequests() {
        val host = launchChannel()
        awaitNode("channel-header")
        compose.onNodeWithTag("channel-tab-Shorts").performClick()
        awaitNode("channel-shorts-list")
        compose.onNodeWithTag("channel-shorts-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search within this channel").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.ChannelSearch, it.lastNavigationRequest) }
        awaitNode("channel-search-list")
        compose.onNodeWithTag("channel-tab-Home").performScrollTo().performClick()
        awaitNode("channel-home-list")
        compose.onNodeWithContentDescription("More channel options").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.SectionMenu, it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-subscribe").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.Subscribe("blender"), it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-description").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.FullDescription, it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-about-panel").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close channel details").performClick()
        compose.onNodeWithTag("channel-home-list").performScrollToNode(
            hasContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation")
        )
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation")
            .performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.ItemMenu("bunny"), it.lastNavigationRequest) }
        compose.onNodeWithTag("channel-home-list").performScrollToIndex(0)
        compose.onNodeWithTag("channel-subscribe").assertIsDisplayed()
    }

    @Test fun videoClickOpensTheExistingWatchHostAndBackReturnsToTheChannel() {
        val host = launchChannel()
        awaitNode("channel-video-bunny")
        compose.onNodeWithTag("channel-thumb-bunny").performClick()
        host.onActivity { assertEquals(ChannelNavigationRequest.Watch("bunny"), it.lastNavigationRequest) }
        awaitNode("watch-screen")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
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
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitNode("search-results-list")
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        results.onActivity { assertFalse(it.isFinishing) }
        results.close()
        search = null
    }

    @Test fun channelSearchRestoresDraftAndSubmittedQueryWithItsIdentity() {
        val host = ActivityScenario.launch<ChannelActivity>(ChannelActivity.intent(ApplicationProvider.getApplicationContext(), "studio"))
        channel = host
        awaitNode("channel-header")
        compose.onNodeWithContentDescription("Search within this channel").performClick()
        awaitNode("channel-search-input")
        compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-form"))
        compose.onNodeWithTag("channel-search-input").performTextInput("bunny")
        compose.onNodeWithContentDescription("Submit channel search").performClick()
        compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-video-bunny"))
        compose.onNodeWithTag("channel-search-video-dream").assertDoesNotExist()
        host.onActivity {
            assertEquals("studio", it.channelViewModel.uiState.value.channelId)
            it.channelViewModel.onAction(com.hhst.youtubelite.channel.domain.ChannelAction.QueryChanged("draft"))
            it.channelViewModel.onAction(com.hhst.youtubelite.channel.domain.ChannelAction.OpenFullDescription)
        }
        awaitNode("channel-about-panel")
        host.recreate()
        awaitNode("channel-about-panel")
        host.onActivity {
            assertEquals("studio", it.channelViewModel.uiState.value.channelId)
            assertEquals("draft", it.channelViewModel.uiState.value.searchQuery)
            assertEquals("bunny", it.channelViewModel.uiState.value.submittedQuery)
        }
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitNode("channel-search-list")
        compose.onNodeWithTag("channel-about-panel").assertDoesNotExist()
        compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-thumb-bunny"))
        compose.onNodeWithTag("channel-search-thumb-bunny").performClick()
        awaitNode("watch-screen")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitNode("channel-search-list")
        host.onActivity { assertEquals("bunny", it.channelViewModel.uiState.value.submittedQuery) }
        DeviceEvidence.captureScene("discovery-channel-search-host")
    }

    @Test fun channelResultNavigatesWithTheSelectedIdentityAndRestoresItsFilter() {
        val host = ActivityScenario.launch<SearchResultsActivity>(SearchResultsActivity.intent(ApplicationProvider.getApplicationContext(), "blender"))
        search = host
        awaitNode("search-results-list")
        host.onActivity { it.searchViewModel.onAction(com.hhst.youtubelite.search.domain.SearchAction.SelectResultType(com.hhst.youtubelite.search.domain.SearchResultType.Channels)) }
        awaitNode("search-channels-list")
        compose.onNodeWithTag("search-channel-name-studio", true).performClick()
        awaitNode("channel-header")
        compose.onNodeWithTag("channel-title", true).assertTextEquals("Blender Studio")
        compose.onNodeWithTag("channel-description").performClick()
        awaitNode("channel-about-panel")
        DeviceEvidence.captureScene("discovery-about-host")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitNode("channel-header")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitNode("search-channels-list")
        host.recreate()
        awaitNode("search-channels-list")
        host.onActivity {
            assertEquals("blender", it.searchViewModel.uiState.value.query)
            assertEquals(com.hhst.youtubelite.search.domain.SearchResultType.Channels, it.searchViewModel.uiState.value.filters.type)
        }
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
        compose.onNodeWithTag("channel-skeleton", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("skeleton-thumb-0", useUnmergedTree = true).assertIsDisplayed()
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
