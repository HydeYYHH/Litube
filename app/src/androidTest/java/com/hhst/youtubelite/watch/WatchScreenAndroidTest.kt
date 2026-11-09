package com.hhst.youtubelite.watch

import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
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
import com.hhst.youtubelite.watch.domain.WatchFeedbackKind
import com.hhst.youtubelite.watch.domain.WatchNavigationRequest
import com.hhst.youtubelite.watch.domain.WatchProvisional
import com.hhst.youtubelite.watch.domain.WatchProvisionalStore
import com.hhst.youtubelite.watch.domain.WatchVideoMenuAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.content.Context

/** Host-level watch page checks. The page never navigates on its own; actions only emit typed requests to the host. */
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

    private fun backToLeaveWatch() {
        com.hhst.youtubelite.core.DeviceEvidence.shell("input keyevent KEYCODE_BACK")
        compose.waitForIdle()
    }

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
        awaitNode("watch-player-slot")
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

    @Test fun homeLiveCardOpensTheLiveWatchProfile() {
        val host = launchHome()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasTestTag("feed-item-live"))
        compose.onNodeWithContentDescription("Open movie studio — animation and art, LIVE").performClick()
        awaitNode("watch-screen")
        host.onActivity { assertEquals(HomeNavigationRequest.Watch("live"), it.lastNavigationRequest) }
        awaitReady()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("Open movie studio — animation and art")
        compose.onNodeWithTag("watch-live-badge").assertIsDisplayed()
        compose.onNodeWithText("1.2K watching").assertIsDisplayed()
        // The live profile replaces the age line instead of adding it after the view count.
        compose.onAllNodes(hasText("LIVE") and hasAnyAncestor(hasTestTag("watch-meta")))
            .assertCountEquals(1)
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
        // Share hands off to the system chooser; keep the host in front for the semantic checks.
        watch?.onActivity { it.shareGateway.launchChooser = false }

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
        tag("watch-action-save").performScrollTo().performClick()
        compose.onNodeWithTag("save-to-playlist").assertIsDisplayed()
        compose.onNodeWithText("Watch later").performClick()
        assertWatchRequest(WatchNavigationRequest.SaveToWatchLater("bunny"))
        compose.onNodeWithTag("save-to-playlist").assertDoesNotExist()
        compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        tag("watch-action-report").performScrollTo().performClick()
        compose.onNodeWithTag("feedback-sheet").assertIsDisplayed()
        compose.onNodeWithText("Harmful content").performClick()
        assertWatchRequest(WatchNavigationRequest.Feedback("bunny", WatchFeedbackKind.Report, "Harmful content"))
        compose.onNodeWithTag("feedback-sheet").assertDoesNotExist()
        compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        // Download would open the v3 confirm sheet; keep the host in front and check the payload.
        watch?.onActivity { it.launchDownload = false }
        clickScrolled(tag("watch-action-download"), WatchNavigationRequest.Download("bunny"))
        watch?.onActivity {
            assertEquals("aqz-KE-bpKQ", it.lastDownloadSpec?.videoId)
            assertEquals("Big Buck Bunny | A short film by the Blender Foundation", it.lastDownloadSpec?.title)
            assertEquals("Blender", it.lastDownloadSpec?.author)
        }
        clickScrolled(tag("watch-action-queue"), WatchNavigationRequest.Queue("bunny"))
        watch?.onActivity {
            assertEquals("aqz-KE-bpKQ", it.lastQueuedItem?.videoId)
            assertEquals("Big Buck Bunny | A short film by the Blender Foundation", it.lastQueuedItem?.title)
            assertEquals("Blender", it.lastQueuedItem?.author)
        }
        compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        clickScrolled(tag("watch-action-open-with"), WatchNavigationRequest.OpenWith("bunny"))
        watch?.onActivity {
            assertEquals(Intent.ACTION_VIEW, it.shareGateway.lastOpenWithIntent?.action)
            assertEquals("https://youtu.be/aqz-KE-bpKQ", it.shareGateway.lastOpenWithIntent?.dataString)
        }
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
        compose.onNodeWithContentDescription("More actions for City light trails — a night walk through the old harbour")
            .performScrollTo().performClick()
        compose.onNodeWithTag("watch-video-menu").assertIsDisplayed()
        compose.onNodeWithText("Save to Watch later").performClick()
        assertWatchRequest(WatchNavigationRequest.SaveToWatchLater("harbour"))
        compose.onNodeWithTag("watch-video-menu").assertDoesNotExist()

        compose.onNodeWithTag("watch-topbar").assertDoesNotExist()

        // Recommendations reuse the Watch host, keeping its browse destination
        // and player instead of stacking another Watch as its own backdrop.
        lateinit var originalWatch: WatchActivity
        watch?.onActivity { originalWatch = it }
        compose.onNodeWithTag("watch-thumb-harbour", useUnmergedTree = true)
            .performScrollTo().performClick()
        assertWatchRequest(WatchNavigationRequest.Watch("harbour"))
        compose.onNodeWithTag("watch-screen", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("City light trails — a night walk through the old harbour")
        watch?.onActivity {
            assertSame(originalWatch, it)
            assertEquals("harbour", it.watchViewModel.uiState.value.videoId)
        }
        backToLeaveWatch()
        compose.waitUntil(5_000) { originalWatch.isFinishing || originalWatch.isDestroyed }
    }

    @Test fun saveChooserAndNewPlaylistStayLocalUntilARequest() {
        val host = launchWatch()
        awaitReady()
        fun openMenu() = compose.onNodeWithContentDescription(
            "More actions for City light trails — a night walk through the old harbour",
        ).performScrollTo().performClick()
        openMenu()
        compose.onNodeWithText("Save to playlist").performClick()
        compose.onNodeWithTag("save-to-playlist").assertIsDisplayed()
        host.onActivity { assertNull(it.lastNavigationRequest) }
        compose.onNodeWithTag("save-menu-playlist-LL").performClick()
        assertWatchRequest(WatchNavigationRequest.SaveToPlaylist("harbour", "LL", "Liked videos"))
        compose.onNodeWithTag("save-to-playlist").assertDoesNotExist()

        openMenu()
        compose.onNodeWithText("Save to playlist").performClick()
        compose.onNodeWithTag("save-menu-new-playlist").performClick()
        compose.onNodeWithTag("new-playlist-sheet").assertIsDisplayed()
        compose.onNodeWithTag("new-playlist-title").performTextInput("Night walks")
        compose.onNodeWithTag("new-playlist-create").performClick()
        compose.waitUntil(10_000) {
            var request: WatchNavigationRequest? = null
            host.onActivity { request = it.lastNavigationRequest }
            request == WatchNavigationRequest.CreatePlaylist("harbour", "Night walks", "")
        }
        compose.onNodeWithTag("new-playlist-sheet").assertDoesNotExist()
        compose.onNodeWithTag("watch-screen").assertIsDisplayed()
    }

    @Test fun backClosesRelatedMenuBeforeLeavingAndRestoresItOnRecreation() {
        val host = launchWatch()
        awaitReady()
        compose.onNodeWithContentDescription("More actions for City light trails — a night walk through the old harbour")
            .performScrollTo().performClick()
        compose.onNodeWithTag("watch-video-menu").assertIsDisplayed()
        host.recreate()
        awaitNode("watch-video-menu")
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("watch-video-menu").assertDoesNotExist()
        awaitReady()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun relatedMenuShareReachesTheSystemBoundaryAndLeavesThePageIntact() {
        val host = launchWatch()
        awaitReady()
        host.onActivity { it.shareGateway.launchChooser = false }
        compose.onNodeWithContentDescription("More actions for City light trails — a night walk through the old harbour")
            .performScrollTo().performClick()
        compose.onNodeWithTag("watch-video-menu").assertIsDisplayed()
        compose.onNodeWithText("Share").performClick()
        assertWatchRequest(WatchNavigationRequest.Share("harbour"))
        host.onActivity {
            val intent = it.shareGateway.lastShareIntent
            assertNotNull(intent)
            assertEquals(android.content.Intent.ACTION_SEND, intent!!.action)
            assertEquals("text/plain", intent.type)
            assertEquals("https://youtu.be/TLkA0RELQ1g", intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
        }
        compose.onNodeWithTag("watch-video-menu").assertDoesNotExist()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("Big Buck Bunny | A short film by the Blender Foundation")
        host.onActivity { assertFalse(it.isFinishing) }
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
        watch?.onActivity { it.watchViewModel.onAction(com.hhst.youtubelite.watch.domain.WatchAction.OpenSearch) }
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

    /**
     * performClick re-resolves text in the merged tree and misses per-link virtual
     * nodes; scroll the description into view and tap the link's own bounds.
     */
    private fun clickDescriptionLink(label: String) {
        compose.onNodeWithTag("watch-scroll").performScrollToNode(hasTestTag("watch-description"))
        val page = compose.onNodeWithTag("watch-scroll").fetchSemanticsNode()
        val container = compose.onNodeWithTag("watch-description", useUnmergedTree = true).fetchSemanticsNode()
        val found = findLayoutNode(container)
            ?: throw AssertionError("description text exposes no layout result")
        val (node, layout) = found
        val index = layout.layoutInput.text.indexOf(label)
        check(index >= 0) { "'$label' is not part of the description text" }
        val box = layout.getBoundingBox(index + label.length / 2)
        val x = node.positionInRoot.x + box.left + box.width / 2f - page.positionInRoot.x
        val y = node.positionInRoot.y + box.top + box.height / 2f - page.positionInRoot.y
        compose.onNodeWithTag("watch-scroll").performTouchInput { click(Offset(x, y)) }
    }

    private fun findLayoutNode(node: SemanticsNode): Pair<SemanticsNode, TextLayoutResult>? {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        layouts.firstOrNull()?.let { return node to it }
        node.children.forEach { findLayoutNode(it)?.let { found -> return found } }
        return null
    }

    @Test fun descriptionTimestampSeeksTheCurrentVideoThroughTheHost() {
        launchWatch("harbour", WatchScenario.Description)
        awaitReady()
        compose.onNodeWithContentDescription("Show more").performClick()
        awaitNode("watch-description")
        clickDescriptionLink("0:42")
        watch?.onActivity { assertEquals(WatchNavigationRequest.SeekTo(42), it.lastNavigationRequest) }
    }

    @Test fun descriptionLinkReachesTheRoutingBoundaryWithoutLeavingThePageItself() {
        launchWatch("harbour", WatchScenario.Description)
        awaitReady()
        compose.onNodeWithContentDescription("Show more").performClick()
        awaitNode("watch-description")
        clickDescriptionLink("https://youtu.be/aqz-KE-bpKQ?t=90")
        watch?.onActivity {
            assertEquals(WatchNavigationRequest.OpenLink("https://youtu.be/aqz-KE-bpKQ?t=90"), it.lastNavigationRequest)
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
        // A previous suite class may have started the media runtime in this shared process;
        // the contract: opening this page never starts it, and traffic stays zero when cold.
        val runtimeStartedBefore = (ApplicationProvider.getApplicationContext<Context>() as App).mediaRuntimeStarted
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        launchWatch()
        awaitReady()
        watch?.onActivity {
            assertEquals(runtimeStartedBefore, (it.application as App).mediaRuntimeStarted)
            fun containsWebView(view: View): Boolean =
                view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(it.window.decorView))
        }
        SystemClock.sleep(1200)
        if (!runtimeStartedBefore && sent >= 0) assertEquals("Watch page sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (!runtimeStartedBefore && received >= 0) assertEquals("Watch page received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }

    @Test fun deviceScreenshotOfRelatedCardMenu() {
        launchWatch()
        awaitReady()
        compose.onNodeWithContentDescription("More actions for City light trails — a night walk through the old harbour")
            .performScrollTo().performClick()
        awaitNode("watch-video-menu")
        DeviceEvidence.captureScene("watch-video-menu-device")
    }

    @Test fun deviceScreenshotOfTheWatchPage() {
        launchWatch()
        awaitReady()
        DeviceEvidence.captureScene("watch-device-final")
    }
    @Test fun aCardSummaryPaintsTheLoadingPageUntilRealContentLands() {
        WatchProvisionalStore.put(WatchProvisional(
            videoId = "bunny", title = "Tapped card title", channel = "Known channel",
            channelId = "studio", artwork = "bunny", views = "1.2M views", age = "3 days ago",
            duration = "9:56",
        ))
        launchWatch("bunny", WatchScenario.Loading)
        compose.waitUntil(4_000) { nodeCount("watch-provisional") > 0 || nodeCount("watch-skeleton") > 0 }
        val provisionalCount = nodeCount("watch-provisional")
        if (provisionalCount == 0)
            throw AssertionError("provisional=" + provisionalCount +
                " skeleton=" + nodeCount("watch-skeleton") +
                " metadata=" + nodeCount("watch-metadata") +
                " scroll=" + nodeCount("watch-scroll"))
        compose.onNodeWithTag("watch-provisional").assertIsDisplayed()
        compose.onNodeWithTag("watch-title", useUnmergedTree = true)
            .assertTextEquals("Tapped card title")
        compose.onNodeWithTag("watch-channel-name", useUnmergedTree = true)
            .assertTextEquals("Known channel")
        // The handoff is consumed: relaunching without a card shows the skeleton.
        watch?.close()
        launchWatch("bunny", WatchScenario.Loading)
        awaitNode("watch-skeleton")
        compose.onNodeWithTag("watch-provisional").assertDoesNotExist()
    }
}
