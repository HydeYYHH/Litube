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
        return ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_SCENARIO, scene.argument)).also { scenario = it }
    }
    @After fun close() { scenario?.close() }

    @Test fun firstVisitIsNativeAndDoesNotStartNetworkingOrLegacyRuntime() {
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        val host = launch()
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
        compose.onNodeWithText("Try searching to get started").assertIsDisplayed()
        compose.onNodeWithTag("home-feed").assertDoesNotExist()
        compose.onNodeWithTag("nav-Subscriptions").assertDoesNotExist()
        host.onActivity {
            assertFalse((it.application as App).legacyRuntimeStarted)
            fun containsWebView(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> containsWebView(view.getChildAt(index)) }
            assertFalse(containsWebView(it.window.decorView))
        }
        SystemClock.sleep(1200)
        if (sent >= 0) assertEquals("Offline preview sent network data", sent, TrafficStats.getUidTxBytes(Process.myUid()))
        if (received >= 0) assertEquals("Offline preview received network data", received, TrafficStats.getUidRxBytes(Process.myUid()))
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
        compose.onNodeWithTag("home-screen").performTouchInput { click(center.copy(y = height - 20f)) }
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
        open()
        compose.onNodeWithTag("home-screen").performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 10f)) }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        open()
        compose.onNodeWithTag("menu-handle").performTouchInput { swipe(center, center.copy(y = center.y + 20.dp.toPx()), 300) }
        compose.onNodeWithTag("video-menu").assertIsDisplayed()
        compose.onNodeWithTag("menu-handle").performTouchInput { swipe(center, center.copy(y = center.y + 200.dp.toPx()), 300) }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
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
