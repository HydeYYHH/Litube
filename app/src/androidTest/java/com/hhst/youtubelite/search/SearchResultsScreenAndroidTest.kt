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
import com.hhst.youtubelite.core.NativeChoicePicker
import com.hhst.youtubelite.App
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.home.domain.HomeNavigationRequest
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.data.SearchScenario
import com.hhst.youtubelite.search.domain.SearchDestination
import com.hhst.youtubelite.search.domain.SearchNavigationRequest
import com.hhst.youtubelite.search.domain.SearchResultType
import com.hhst.youtubelite.search.domain.SearchVideoMenuAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.content.Context

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
        awaitNode("watch-screen")
        // System Back key, not scenario.goBack: navigation crosses activity hosts.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitResultsList()
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        compose.onNodeWithTag("search-channel-${first.id}").performClick()
        host.onActivity { assertEquals(SearchNavigationRequest.Channel(first.channelId), it.lastNavigationRequest) }
        awaitNode("channel-page")
        awaitNode("channel-home-list")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitNode("search-results-list")
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for ${first.title}").performClick()
        compose.onNodeWithTag("search-video-menu").assertIsDisplayed()
        // The card menu is page state; only its rows cross the host boundary.
        host.onActivity { assertEquals(SearchNavigationRequest.Channel(first.channelId), it.lastNavigationRequest) }
        compose.onNodeWithText("Save to Watch later").performClick()
        host.onActivity {
            assertEquals(SearchNavigationRequest.MenuAction(first.id, SearchVideoMenuAction.WatchLater), it.lastNavigationRequest)
        }
        compose.onNodeWithTag("search-video-menu").assertDoesNotExist()
    }

    @Test fun filterStripUsesTheSampledGeometryAndAppliesDateImmediately() {
        val host = launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        awaitNode("search-filter-bar")
        host.onActivity { assertEquals(SearchNavigationRequest.Filters, it.lastNavigationRequest) }

        val bar = compose.onNodeWithTag("search-filter-bar").fetchSemanticsNode().boundsInRoot
        val type = compose.onNodeWithTag("search-filter-type").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("search-filter-date").fetchSemanticsNode().boundsInRoot
        // Bounds are pixels on the device; the sampled values are CSS px (dp) at density 1.
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals(75f, bar.height / d, 0.5f)
        assertEquals(50f, type.height / d, 0.5f)
        assertEquals(50f, date.height / d, 0.5f)
        assertEquals(104f, type.width / d, 0.5f)
        assertEquals(117f, date.width / d, 0.5f)
        assertEquals(12f, (type.left - bar.left) / d, 0.5f)
        assertEquals(12f, (type.top - bar.top) / d, 0.5f)
        assertEquals(4f, (date.left - type.right) / d, 0.5f)
        val divider = compose.onNodeWithTag("search-filter-divider").fetchSemanticsNode().boundsInRoot
        assertEquals(1f, divider.height / d, 0.5f)
        assertEquals(bar.left, divider.left, 0.5f)
        assertEquals(bar.right, divider.right, 0.5f)

        compose.onNodeWithTag("search-filter-date").performClick()
        compose.waitForIdle()
        NativeChoicePicker.choose("This year")
        compose.waitUntil(10_000) {
            var applied = false
            host.onActivity {
                val ids = (it.searchViewModel.uiState.value.content as? com.hhst.youtubelite.search.domain.SearchContent.Results)
                    ?.page?.results?.map { result -> result.id }.orEmpty()
                applied = "meadow" in ids && "orbit" !in ids
            }
            applied
        }
        compose.onNodeWithTag("search-results-list").performScrollToNode(hasTestTag("search-result-meadow"))
        compose.onNodeWithTag("search-result-meadow").assertIsDisplayed()
        host.onActivity {
            assertEquals(com.hhst.youtubelite.search.domain.SearchUploadDate.ThisYear,
                it.searchViewModel.uiState.value.filters.uploaded)
        }
        compose.onNodeWithTag("search-filter-date").assertTextEquals("This year")
        compose.onNodeWithTag("search-filter-bar").assertIsDisplayed()

        compose.onNodeWithTag("search-filter-date").performClick()
        compose.waitForIdle()
        NativeChoicePicker.choose("Today")
        awaitNode("search-empty")
        compose.onNodeWithText("No results found").assertIsDisplayed()
    }

    @Test fun channelsAndPlaylistTypesLoadTheirOwnCards() {
        val host = launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        awaitNode("search-filter-bar")
        compose.onNodeWithTag("search-filter-type").performClick()
        compose.waitForIdle()
        NativeChoicePicker.choose("Channels")
        awaitNode("search-channels-list")
        compose.onNodeWithTag("search-filter-type").assertTextEquals("Channels")
        compose.onNodeWithTag("search-channel-result-blender").assertIsDisplayed()

        compose.onNodeWithTag("search-filter-type").performClick()
        compose.waitForIdle()
        NativeChoicePicker.choose("Playlists")
        awaitNode("playlist-search-list")
        compose.onNodeWithTag("search-filter-type").assertTextEquals("Playlists")
        host.onActivity {
            assertEquals(SearchResultType.Playlists, it.searchViewModel.uiState.value.filters.type)
        }
    }

    @Test fun backClosesTheFilterStripAndTheCardMenuBeforeLeaving() {
        val host = launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        awaitNode("search-filter-bar")
        compose.runOnUiThread { host.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        compose.waitUntil(5_000) { nodeCount("search-filter-bar") == 0 }
        compose.onNodeWithTag("search-results-list").assertIsDisplayed()
        host.onActivity { assertFalse(it.isFinishing) }

        val first = FixtureSearchRepository.sampleResults().first()
        compose.onNodeWithContentDescription("More actions for ${first.title}").performClick()
        compose.onNodeWithTag("search-video-menu").assertIsDisplayed()
        compose.runOnUiThread { host.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        compose.onNodeWithTag("search-video-menu").assertDoesNotExist()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun nativePickerBackClosesBeforeTheFilterStrip() {
        val host = launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.onNodeWithTag("search-filter-date").performClick()
        compose.waitForIdle()
        NativeChoicePicker.assertVisible("Today")
        DeviceEvidence.captureScene("host-filter-picker")
        NativeChoicePicker.back()
        compose.onNodeWithTag("search-filter-bar").assertIsDisplayed()
        host.onActivity {
            assertTrue(it.searchViewModel.uiState.value.filtersOpen)
            assertFalse(it.isFinishing)
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("search-filter-bar").assertDoesNotExist()
    }

    @Test fun inlineFiltersAndScrollStayInPlaceAfterMenuAndSearch() {
        val host = launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.onNodeWithTag("search-results-list").performScrollToIndex(3)
        val tag = "search-result-workshop"
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        val title = FixtureSearchRepository.sampleResults()[3].title
        compose.onNodeWithContentDescription("More actions for $title").performClick()
        compose.onNodeWithTag("search-video-menu").assertIsDisplayed()
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("search-filter-bar").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y, 1f)
        compose.onNodeWithTag("search-query").performClick()
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("search-filter-bar").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y, 1f)
        host.recreate()
        awaitResultsList()
        compose.onNodeWithTag("search-filter-bar").assertIsDisplayed()
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun cardMenuShareReachesTheSystemShareBoundary() {
        val host = launchResults()
        awaitResultsList()
        host.onActivity { it.shareGateway.launchChooser = false }
        val first = FixtureSearchRepository.sampleResults().first()
        compose.onNodeWithContentDescription("More actions for ${first.title}").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.waitUntil(5_000) {
            var request: SearchNavigationRequest? = null
            host.onActivity { request = it.lastNavigationRequest }
            request == SearchNavigationRequest.Share(first.id)
        }
        host.onActivity {
            val intent = it.shareGateway.lastShareIntent
            assertNotNull(intent)
            assertEquals(android.content.Intent.ACTION_SEND, intent!!.action)
            assertEquals("text/plain", intent.type)
            assertEquals("https://youtu.be/TLkA0RELQ1g", intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
        }
        compose.onNodeWithTag("search-video-menu").assertDoesNotExist()
    }

    @Test fun filterAndMenuStateSurviveActivityRecreation() {
        launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        awaitNode("search-filter-bar")
        compose.onNodeWithTag("search-filter-date").performClick()
        compose.waitForIdle()
        NativeChoicePicker.choose("This month")
        compose.waitUntil(10_000) {
            var applied = false
            results?.onActivity {
                applied = it.searchViewModel.uiState.value.filters.uploaded ==
                    com.hhst.youtubelite.search.domain.SearchUploadDate.ThisMonth
            }
            applied
        }
        results?.recreate()
        awaitNode("search-filter-bar")
        compose.onNodeWithTag("search-filter-date").assertTextEquals("This month")
        compose.onNodeWithTag("search-filter-type").assertTextEquals("All")
    }

    @Test fun homeDestinationLeavesTheResultsPage() {
        val host = launchResults()
        awaitResultsList()
        lateinit var activity: SearchResultsActivity
        host.onActivity { activity = it }
        compose.onNodeWithTag("search-nav-Home").performClick()
        awaitNode("home-screen")
        // Navigation may finish and destroy the results host before the assertion;
        // read the captured host on main instead of resuming it via ActivityScenario.
        compose.runOnIdle {
            assertEquals(SearchNavigationRequest.Destination(SearchDestination.Home), activity.lastNavigationRequest)
            assertTrue(activity.isFinishing || activity.isDestroyed)
        }
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
        compose.onNodeWithTag("search-results").assertDoesNotExist()
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
        compose.onNodeWithTag("search-skeleton", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("skeleton-thumb-0", useUnmergedTree = true).assertIsDisplayed()
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
        compose.onNodeWithTag("search-panel-back").performClick()
        // The close updates state synchronously, but the overlay leaves the
        // semantics tree one frame; older APIs can serve the assert from the
        // pre-close tree.
        compose.waitUntil(5_000) { nodeCount("search-overlay") == 0 }
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        compose.onNodeWithTag("search-result-workshop").assertIsDisplayed()
        results?.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun deviceScreenshotOfFilterStripAndCardMenu() {
        launchResults()
        awaitResultsList()
        compose.onNodeWithContentDescription("Search filters").performClick()
        awaitNode("search-filter-bar")
        DeviceEvidence.captureScene("search-filters-device")
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.waitUntil(5_000) { nodeCount("search-filter-bar") == 0 }
        val first = FixtureSearchRepository.sampleResults().first()
        compose.onNodeWithContentDescription("More actions for ${first.title}").performClick()
        awaitNode("search-video-menu")
        DeviceEvidence.captureScene("search-video-menu-device")
    }

    @Test fun resultsPageIsNativeAndStartsNoNetworkOrLegacyRuntime() {
        // A previous suite class may have started the media runtime in this shared
        // process; the contract is that opening this page does not start it, and
        // first-visit traffic stays zero when genuinely cold.
        val runtimeStartedBefore = (ApplicationProvider.getApplicationContext<Context>() as App).mediaRuntimeStarted
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        launchResults()
        awaitResultsList()
        results?.onActivity {
            assertEquals(runtimeStartedBefore, (it.application as App).mediaRuntimeStarted)
            fun containsWebView(view: View): Boolean =
                view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(it.window.decorView))
        }
        SystemClock.sleep(1200)
        if (!runtimeStartedBefore && sent >= 0) assertEquals("Offline results page sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (!runtimeStartedBefore && received >= 0) assertEquals("Offline results page received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }
}
