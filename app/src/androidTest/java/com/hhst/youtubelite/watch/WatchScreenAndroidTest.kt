package com.hhst.youtubelite.watch

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
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.HomeNavigationRequest
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.search.domain.SearchNavigationRequest
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.WatchNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Host-level checks for the watch page: home/search hand-off, typed-only actions,
 * state scenarios, and recreation. The page never navigates on its own.
 */
class WatchScreenAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var home: ActivityScenario<HomeActivity>? = null
    private var results: ActivityScenario<SearchResultsActivity>? = null
    private var watch: ActivityScenario<WatchActivity>? = null

    private fun launchHome(scene: HomeScenario = HomeScenario.Feed) = ActivityScenario.launch<HomeActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_SCENARIO, scene.argument)
    ).also {
        home = it
        awaitNode("home-screen")
    }

    private fun launchResults(query: String = "open movie") =
        ActivityScenario.launch<SearchResultsActivity>(
            SearchResultsActivity.intent(ApplicationProvider.getApplicationContext(), query)
        ).also {
            results = it
            awaitNode("search-results")
        }

    private fun launchWatch(videoId: String = "bunny", scenario: WatchScenario? = null) =
        ActivityScenario.launch<WatchActivity>(
            WatchActivity.intent(ApplicationProvider.getApplicationContext(), videoId, scenario)
        ).also {
            watch = it
            awaitNode("watch-screen")
        }

    @After fun close() {
        watch?.close()
        results?.close()
        home?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Host transitions briefly leave the process without a compose hierarchy; treat that as "not yet". */
    private fun nodeCount(tag: String) = runCatching {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    }.getOrDefault(0)

    private fun awaitNode(tag: String) = compose.waitUntil(15_000) { nodeCount(tag) > 0 }

    /** Content arrives after the fixture's 140ms delay. */
    private fun awaitReady() = awaitNode("watch-metadata")

    /** The v3 player draws its own Back control; scope to the page's fixed top bar. */
    private fun backToLeaveWatch() = compose
        .onNode(hasContentDescription("Back") and hasAnyAncestor(hasTestTag("watch-topbar")))
        .performClick()

    private fun assertWatchRequest(expected: WatchNavigationRequest) {
        compose.waitUntil(5_000) {
            var actual: WatchNavigationRequest? = null
            watch?.onActivity { actual = it.lastNavigationRequest }
            actual == expected
        }
        watch?.onActivity { assertEquals(expected, it.lastNavigationRequest) }
    }

    @Test fun playerBackUnlocksBeforeLeavingFullscreen() {
        val host = launchWatch()
        awaitReady()
        val player = org.koin.core.context.GlobalContext.get().get<com.hhst.youtubelite.player.PlayerViewModel>()
        host.onActivity {
            player.setFullscreen(true)
            player.setLocked(true)
        }
        compose.waitUntil(5000) { player.uiState.value.locked && player.uiState.value.fullscreen }
        compose.waitForIdle()
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { !player.uiState.value.locked }
        assertTrue(player.uiState.value.fullscreen)
        compose.waitForIdle()
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { !player.uiState.value.fullscreen }
        host.onActivity { assertFalse(it.isFinishing) }
        awaitNode("watch-topbar")
    }

    @Test fun homeFeedThumbnailOpensWatchAndBackKeepsHome() {
        val host = launchHome()
        compose.onNodeWithContentDescription("Big Buck Bunny | A short film by the Blender Foundation, 9:56")
            .performClick()
        awaitNode("watch-screen")
        host.onActivity { assertEquals(HomeNavigationRequest.Watch("bunny"), it.lastNavigationRequest) }
        awaitReady()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("Big Buck Bunny | A short film by the Blender Foundation")
        backToLeaveWatch()
        awaitNode("home-screen")
        awaitNode("feed-item-bunny")
        compose.onNodeWithTag("feed-item-bunny").assertIsDisplayed()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun searchResultOpensWatchAndBackKeepsResults() {
        val host = launchResults()
        compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).performClick()
        awaitNode("watch-screen")
        host.onActivity { assertEquals(SearchNavigationRequest.Watch("harbour"), it.lastNavigationRequest) }
        awaitReady()
        backToLeaveWatch()
        awaitNode("search-results")
        awaitNode("search-thumb-harbour")
        compose.onNodeWithTag("search-thumb-harbour", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("search-query").assertTextEquals("open movie")
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun watchChromeActionsOnlyEmitTypedRequests() {
        launchWatch()
        awaitReady()

        fun clickScrolled(node: SemanticsNodeInteraction, expected: WatchNavigationRequest) {
            node.performScrollTo().performClick()
            assertWatchRequest(expected)
            if (expected is WatchNavigationRequest.Comments) {
                compose.onNodeWithTag("comments-panel").assertIsDisplayed()
            } else compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        }

        fun clickFixed(node: SemanticsNodeInteraction, expected: WatchNavigationRequest) {
            node.performClick()
            assertWatchRequest(expected)
            compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        }

        fun tag(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

        clickScrolled(tag("watch-action-like"), WatchNavigationRequest.Like("bunny"))
        clickScrolled(tag("watch-action-dislike"), WatchNavigationRequest.Dislike("bunny"))
        clickScrolled(tag("watch-action-subscribe"), WatchNavigationRequest.Subscribe("blender"))
        clickScrolled(tag("watch-action-share"), WatchNavigationRequest.Share("bunny"))
        clickScrolled(tag("watch-action-save"), WatchNavigationRequest.Save("bunny"))
        clickScrolled(tag("watch-action-report"), WatchNavigationRequest.Report("bunny"))
        clickScrolled(
            compose.onNodeWithText("Comments"),
            WatchNavigationRequest.Comments("bunny"),
        )
        // Opening the panel is a host effect; dismiss it before touching the page again.
        compose.onNodeWithTag("comments-close").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("comments-panel").fetchSemanticsNodes().isEmpty()
        }
        // The page itself has no kebab; item menus come from the related cards.
        clickScrolled(
            compose.onNodeWithContentDescription("More actions for City light trails — a night walk through the old harbour"),
            WatchNavigationRequest.ItemMenu("harbour"),
        )
        // The fixed top bar sits outside the scroll container.
        clickFixed(compose.onNodeWithContentDescription("More options"), WatchNavigationRequest.PageMenu)

        // Related videos open a new history entry.
        compose.onNodeWithTag("watch-thumb-harbour", useUnmergedTree = true)
            .performScrollTo().performClick()
        assertWatchRequest(WatchNavigationRequest.Watch("harbour"))
        compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("City light trails — a night walk through the old harbour")
        backToLeaveWatch()
        awaitReady()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("Big Buck Bunny | A short film by the Blender Foundation")
    }

    @Test fun channelAndSearchRoundTripsKeepTheWatchState() {
        launchWatch()
        awaitReady()
        compose.onNodeWithContentDescription("Show more").performClick()
        compose.onNodeWithTag("watch-action-avatar", useUnmergedTree = true).performScrollTo().performClick()
        awaitNode("channel-page")
        compose.onNodeWithContentDescription("Close the channel page").performClick()
        awaitNode("watch-screen")
        compose.onNodeWithTag("watch-description", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithTag("search-input").performTextInput("meadow")
        watch?.recreate()
        awaitNode("search-input")
        compose.onNodeWithTag("search-input").assertTextEquals("meadow")
        compose.onNodeWithTag("search-input").performImeAction()
        awaitNode("search-results")
        compose.onNodeWithTag("search-query").assertTextEquals("meadow")
        compose.onNodeWithContentDescription("Back").performClick()
        awaitNode("watch-screen")
        compose.onNodeWithTag("watch-description", useUnmergedTree = true).assertExists()
    }

    @Test fun moreTogglesTheDescriptionWithoutRequestingNavigation() {
        launchWatch()
        awaitReady()
        compose.onNodeWithTag("watch-description", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show more").performClick()
        compose.onNodeWithTag("watch-description", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Show less").assertExists()
        watch?.onActivity {
            assertEquals("bunny", it.watchViewModel.uiState.value.videoId)
            assertNull(it.lastNavigationRequest)
        }
    }

    @Test fun debugScenariosRenderTheirOwnState() {
        launchWatch("bunny", WatchScenario.Loading)
        awaitNode("watch-skeleton")
        compose.onNodeWithTag("watch-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("watch-metadata", useUnmergedTree = true).assertDoesNotExist()
        watch?.close()
        launchWatch("bunny", WatchScenario.Error)
        awaitNode("watch-error")
        compose.onNodeWithText("Something went wrong").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        // The local fixture still fails, so the retry returns to the same error surface.
        awaitNode("watch-error")
        compose.onNodeWithTag("watch-error").assertIsDisplayed()
    }

    @Test fun videoIdAndDescriptionSurviveActivityRecreation() {
        launchWatch()
        awaitReady()
        compose.onNodeWithContentDescription("Show more").performClick()
        awaitNode("watch-description")
        watch?.recreate()
        awaitNode("watch-screen")
        awaitReady()
        watch?.onActivity { assertEquals("bunny", it.watchViewModel.uiState.value.videoId) }
        compose.onNodeWithTag("watch-description", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Show less").assertExists()
    }

    @Test fun watchPageIsNativeAndStartsNoNetworkOrLegacyRuntime() {
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        launchWatch()
        awaitReady()
        watch?.onActivity {
            assertFalse((it.application as App).legacyRuntimeStarted)
            fun containsWebView(view: View): Boolean =
                view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(it.window.decorView))
        }
        SystemClock.sleep(1200)
        if (sent >= 0) assertEquals("Watch page sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (received >= 0) assertEquals("Watch page received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }

    @Test fun deviceScreenshotOfTheWatchPage() {
        launchWatch()
        awaitReady()
        DeviceEvidence.captureScene("watch-device-final")
    }
}
