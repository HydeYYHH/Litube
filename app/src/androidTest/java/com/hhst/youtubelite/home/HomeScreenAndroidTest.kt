package com.hhst.youtubelite.home

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.App
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeScreenAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<HomeActivity>? = null

    private fun launch(scene: HomeScenario = HomeScenario.Empty): ActivityScenario<HomeActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_SCENARIO, scene.argument)).also {
            scenario = it
            // Host tests hand over between activities; wait for this window before touching it.
            compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        }
    }
    @After fun close() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun firstVisitIsNativeAndDoesNotStartNetworkingOrLegacyRuntime() {
        // A previous suite class may have started the media runtime in this shared process;
        // the contract is that opening this page does not start it, and first-visit traffic
        // stays zero when the runtime is genuinely cold.
        val runtimeStartedBefore = (ApplicationProvider.getApplicationContext<Context>() as App).mediaRuntimeStarted
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        val host = launch()
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
        compose.onNodeWithText("Try searching to get started").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").assertDoesNotExist()
        compose.onNodeWithTag("nav-Subscriptions").assertDoesNotExist()
        host.onActivity {
            assertEquals(runtimeStartedBefore, (it.application as App).mediaRuntimeStarted)
            fun containsWebView(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(it.window.decorView))
        }
        SystemClock.sleep(1200)
        if (!runtimeStartedBefore && sent >= 0) assertEquals("Offline preview sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (!runtimeStartedBefore && received >= 0) assertEquals("Offline preview received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
    }

    @Test fun searchClearSubmitAndBackKeepTheHomeState() {
        val host = launch()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").performTextInput("nature")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("search-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("search-input").performTextInput("nature")
        compose.onNodeWithTag("search-input").performImeAction()
        compose.waitForIdle()
        host.onActivity { assertEquals(HomeNavigationRequest.Search("nature"), it.lastNavigationRequest) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").assertTextEquals("nature")
    }

    @Test fun searchQueryAndOverlaySurviveActivityRecreation() {
        val host = launch()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").performTextInput("open movies")
        host.recreate()
        compose.onNodeWithTag("search-input").assertTextEquals("open movies")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
    }

    @Test fun suggestionsEditOrSubmitWithoutInventingResultsAndScrimCloses() {
        val host = launch()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").performTextInput("nature")
        compose.onNodeWithContentDescription("Use suggestion nature documentary").performClick()
        compose.onNodeWithTag("search-input").assertTextEquals("nature documentary")
        host.onActivity { assertNull(it.lastNavigationRequest) }
        compose.onNodeWithTag("suggestion-0").performClick()
        host.onActivity { assertEquals(HomeNavigationRequest.Search("nature documentary"), it.lastNavigationRequest) }
        // Submitting opens the local results page; it never invents home feed content.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("search-results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-query").assertTextEquals("nature documentary")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").assertTextEquals("nature documentary")
        val home = compose.onNodeWithTag("home-screen").fetchSemanticsNode().boundsInRoot
        val overlay = compose.onNodeWithTag("search-overlay").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("home-screen").performTouchInput {
            click(center.copy(y = overlay.bottom - home.top + 24f))
        }
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").assertTextEquals("nature documentary")
    }

    @Test fun systemBackDismissesKeyboardBeforeSearch() {
        val host = launch()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        fun keyboardVisible(): Boolean {
            var visible = false
            host.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return visible
        }
        compose.waitUntil(5_000) { keyboardVisible() }
        compose.onNodeWithTag("search-input").performTextInput("nature")
        compose.onNodeWithTag("suggestion-3").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { !keyboardVisible() }
        compose.onNodeWithTag("search-overlay").assertIsDisplayed()
        compose.onNodeWithTag("search-input").assertTextEquals("nature")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("search-overlay").assertDoesNotExist()
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
    }

    @Test fun menuScrimAndHandleDismissWithoutSendingAnOperation() {
        val host = launch(HomeScenario.Feed)
        fun open() = compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        // A menu leaving the screen plays its short exit first; wait for the removal.
        fun awaitClosed() = compose.waitUntil(5_000) {
            runCatching { compose.onAllNodesWithTag("video-menu").fetchSemanticsNodes().isEmpty() }.getOrDefault(false)
        }
        open()
        compose.onNodeWithTag("home-screen").performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 10f)) }
        awaitClosed()
        open()
        compose.onNodeWithTag("menu-handle").performTouchInput { swipe(center, center.copy(y = center.y + 20.dp.toPx()), 300) }
        compose.onNodeWithTag("video-menu").assertIsDisplayed()
        compose.onNodeWithTag("menu-handle").performTouchInput { swipe(center, center.copy(y = center.y + 200.dp.toPx()), 300) }
        awaitClosed()
        host.onActivity { assertNull(it.lastNavigationRequest) }
    }

    @Test fun feedMenuClosesBeforeHomeAndEmitsARequestOnly() {
        val host = launch(HomeScenario.Feed)
        compose.onNodeWithTag("nav-Subscriptions").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        compose.onNodeWithTag("video-menu").assertIsDisplayed()
        host.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        compose.onNodeWithText("Save to Watch later").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(HomeNavigationRequest.MenuAction("bunny", FeedMenuAction.WatchLater), it.lastNavigationRequest) }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    @Test fun feedMenuSaveToPlaylistOpensTheChooserAndNewPlaylistForm() {
        val host = launch(HomeScenario.Feed)
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        compose.onNodeWithText("Save to playlist").performClick()
        compose.onNodeWithTag("save-to-playlist").assertIsDisplayed()
        compose.onNodeWithTag("save-menu-playlist-LL").assertIsDisplayed()
        host.onActivity { assertNull(it.lastNavigationRequest) }
        compose.onNodeWithTag("save-menu-new-playlist").performClick()
        compose.onNodeWithTag("new-playlist-sheet").assertIsDisplayed()
        compose.onNodeWithTag("new-playlist-title").performTextInput("Road trip")
        compose.onNodeWithTag("new-playlist-create").performClick()
        compose.waitUntil(10_000) {
            var request: HomeNavigationRequest? = null
            host.onActivity { request = it.lastNavigationRequest }
            request == HomeNavigationRequest.CreatePlaylist("bunny", "Road trip", "")
        }
        compose.onNodeWithTag("new-playlist-sheet").assertDoesNotExist()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test fun feedbackRowsOpenAReasonChooserAndEmitTheTypedReason() {
        val host = launch(HomeScenario.Feed)
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        compose.onNodeWithText("Not interested").performClick()
        compose.onNodeWithTag("feedback-sheet").assertIsDisplayed()
        host.onActivity { assertNull(it.lastNavigationRequest) }
        compose.onNodeWithText("It's repetitive").performClick()
        compose.waitUntil(10_000) {
            var request: HomeNavigationRequest? = null
            host.onActivity { request = it.lastNavigationRequest }
            request == HomeNavigationRequest.Feedback("bunny", HomeFeedbackKind.NotInterested, "It's repetitive")
        }
        compose.onNodeWithTag("feedback-sheet").assertDoesNotExist()
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test fun feedMenuShareReachesTheSystemBoundaryWithoutOpeningAnotherScreen() {
        val host = launch(HomeScenario.Feed)
        host.onActivity { it.shareGateway.launchChooser = false }
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.waitUntil(5_000) {
            var request: HomeNavigationRequest? = null
            host.onActivity { request = it.lastNavigationRequest }
            request == HomeNavigationRequest.MenuAction("bunny", FeedMenuAction.Share)
        }
        host.onActivity {
            val intent = it.shareGateway.lastShareIntent
            assertNotNull(intent)
            assertEquals(Intent.ACTION_SEND, intent!!.action)
            assertEquals("text/plain", intent.type)
            assertEquals("https://youtu.be/aqz-KE-bpKQ", intent.getStringExtra(Intent.EXTRA_TEXT))
        }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
        host.onActivity { assertFalse(it.isFinishing) }
    }

    @Test fun homePostOpensTheSharedPostDetailAndBackKeepsTheFeedPosition() {
        val host = launch(HomeScenario.Feed)
        val feed = compose.onNodeWithTag("home-feed")
        feed.performScrollToNode(hasTestTag("post-body-post"))
        compose.onNodeWithTag("post-body-post").performTouchInput { click() }
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodesWithTag("post-detail-page").fetchSemanticsNodes() }
                .getOrDefault(emptyList()).isNotEmpty()
        }
        host.onActivity {
            assertEquals(HomeNavigationRequest.OpenPost("studio", "post"), it.lastNavigationRequest)
        }
        compose.onNodeWithTag("post-detail-body")
            .assertTextContains("A new story starts with a little curiosity", substring = true)
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodesWithTag("home-feed").fetchSemanticsNodes() }
                .getOrDefault(emptyList()).isNotEmpty()
        }
        compose.onNodeWithTag("post-body-post").assertIsDisplayed()
    }

    @Test fun feedScrollPositionSurvivesSearchAndRecreation() {
        val host = launch(HomeScenario.Feed)
        compose.onNodeWithTag("home-feed").performScrollToIndex(3)
        compose.onNodeWithTag("feed-item-sintel").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("feed-item-sintel").assertIsDisplayed()
        host.recreate()
        compose.onNodeWithTag("feed-item-sintel").assertIsDisplayed()
    }
}
