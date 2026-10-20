package com.hhst.youtubelite.search

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
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.home.domain.HomeNavigationRequest
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.data.SearchScenario
import com.hhst.youtubelite.search.domain.SearchDestination
import com.hhst.youtubelite.search.domain.SearchNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchResultsScreenAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var home: ActivityScenario<HomeActivity>? = null
    private var results: ActivityScenario<SearchResultsActivity>? = null

    private fun launchHome(scene: HomeScenario = HomeScenario.Empty) = ActivityScenario.launch<HomeActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_SCENARIO, scene.argument)
    ).also {
        home = it
        awaitNode("home-screen")
    }

    private fun launchResults(query: String = "open movie", scenario: SearchScenario? = null) =
        ActivityScenario.launch<SearchResultsActivity>(
            SearchResultsActivity.intent(ApplicationProvider.getApplicationContext(), query, scenario)
        ).also {
            results = it
            awaitNode("search-results")
        }

    @After fun close() {
        results?.close()
        home?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Host transitions briefly leave the process without a compose hierarchy; treat that as "not yet". */
    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitResultsList() = awaitNode("search-results-list")

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }

    private fun submitSearch(query: String) {
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").performTextInput(query)
        compose.onNodeWithTag("search-input").performImeAction()
        awaitNode("search-results")
    }

    @Test fun submittingFromHomeOpensTheLocalResultsPage() {
        val host = launchHome()
        submitSearch("open movie")
        host.onActivity { assertEquals(HomeNavigationRequest.Search("open movie"), it.lastNavigationRequest) }
        awaitResultsList()
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        // The results page is local: no other page was opened by the request.
        compose.onNodeWithTag("home-empty").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        awaitNode("home-empty")
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").assertTextEquals("open movie")
    }

    @Test fun searchReturnKeepsHomeQueryFilterAndScroll() {
        val host = launchHome(HomeScenario.Feed)
        compose.onNodeWithTag("home-feed").performScrollToIndex(3)
        compose.onNodeWithTag("feed-item-sintel").assertIsDisplayed()
        submitSearch("open movie")
        awaitResultsList()
        compose.onNodeWithContentDescription("Back").performClick()
        awaitNode("home-screen")
        awaitNode("feed-item-sintel")
        compose.onNodeWithTag("feed-item-sintel").assertIsDisplayed()
        compose.onNodeWithText("Music").performClick()
        compose.waitUntil(15_000) { nodeCount("feed-item-mix") > 0 }
        submitSearch("open movie")
        awaitResultsList()
        compose.onNodeWithContentDescription("Back").performClick()
        awaitNode("home-screen")
        awaitNode("feed-item-mix")
        compose.onNodeWithText("Music").assertIsSelected()
        compose.onNodeWithTag("feed-item-mix").assertIsDisplayed()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun deviceScreenshotOfTheResultsPage() {
        launchResults("open movie")
        awaitResultsList()
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        DeviceEvidence.captureScene("search-device-final-390")
    }

    @Test fun resultAndChannelClicksOnlyEmitRequests() {
        val host = launchResults()
        awaitResultsList()
        val first = FixtureSearchRepository.sampleResults().first()
        compose.onNodeWithTag("search-thumb-${first.id}").performClick()
        host.onActivity { assertEquals(SearchNavigationRequest.Watch(first.id), it.lastNavigationRequest) }
        // The host opens the local watch activity for a Watch request; return before the next chrome check.
        awaitNode("watch-screen")
        // GLOBAL_ACTION_BACK == 1; the watch host owns a different back control.
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        awaitResultsList()
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        compose.onNodeWithTag("search-channel-${first.id}").performClick()
        host.onActivity { assertEquals(SearchNavigationRequest.Channel(first.channelId), it.lastNavigationRequest) }
        // The host opens the local channel page for a Channel request; return before the menu check.
        awaitNode("channel-page")
        awaitNode("channel-home-list")
        // GLOBAL_ACTION_BACK == 1; the channel host owns a different back control.
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        awaitNode("search-results-list")
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for ${first.title}").performClick()
        host.onActivity { assertEquals(SearchNavigationRequest.ItemMenu(first.id), it.lastNavigationRequest) }
    }

    @Test fun homeDestinationLeavesTheResultsPage() {
        val host = launchResults()
        awaitResultsList()
        compose.onNodeWithTag("search-nav-Home").performClick()
        host.onActivity {
            assertEquals(SearchNavigationRequest.Destination(SearchDestination.Home), it.lastNavigationRequest)
            assertTrue(it.isFinishing)
        }
    }

    @Test fun debugScenariosRenderTheirOwnState() {
        launchResults("no such video", SearchScenario.Empty)
        awaitNode("search-empty")
        compose.onNodeWithTag("search-empty").assertIsDisplayed()
        compose.onNodeWithTag("search-results-list").assertDoesNotExist()
        compose.onNodeWithText("No results found").assertIsDisplayed()
        results?.close()
        launchResults("open movie", SearchScenario.Loading)
        awaitNode("search-skeleton")
        compose.onNodeWithTag("search-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("skeleton-thumb-0").assertIsDisplayed()
        results?.close()
        launchResults("open movie", SearchScenario.Error)
        awaitNode("search-error")
        compose.onNodeWithTag("search-error").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        // The local fixture still fails, so the retry returns to the same error surface.
        awaitNode("search-error")
        compose.onNodeWithTag("search-error").assertIsDisplayed()
    }

    @Test fun querySurvivesResultsActivityRecreation() {
        launchResults("open movie")
        awaitResultsList()
        results?.recreate()
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        awaitResultsList()
    }


    @Test fun queryCanBeEditedAndSubmittedFromResults() {
        launchResults()
        awaitResultsList()
        compose.onNodeWithTag("search-query").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("meadow")
        compose.onNodeWithTag("search-input").performImeAction()
        awaitResultsList()
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onNodeWithTag("search-query").assertTextEquals("meadow")
        results?.onActivity { assertEquals("meadow", it.searchViewModel.uiState.value.query) }
    }

    @Test fun editingRestoresItsDraftAndBackKeepsResultsAndScroll() {
        launchResults()
        awaitResultsList()
        compose.onNodeWithTag("search-results-list").performScrollToIndex(3)
        compose.onNodeWithTag("search-query").performClick()
        compose.onNodeWithTag("search-input").performTextReplacement("draft")
        results?.recreate()
        awaitNode("search-input")
        compose.onNodeWithTag("search-input").assertTextEquals("draft")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        compose.onNodeWithTag("search-result-workshop").assertIsDisplayed()
        results?.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun resultsPageIsNativeAndStartsNoNetworkOrLegacyRuntime() {
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        launchResults()
        awaitResultsList()
        results?.onActivity {
            assertFalse((it.application as App).legacyRuntimeStarted)
            fun containsWebView(view: View): Boolean =
                view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(it.window.decorView))
        }
        SystemClock.sleep(1200)
        if (sent >= 0) assertEquals("Offline results page sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (received >= 0) assertEquals("Offline results page received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }
}
