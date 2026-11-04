package com.hhst.youtubelite.library

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.account.SignInActivity
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.library.data.LibraryScenario
import com.hhst.youtubelite.library.domain.HistoryMenuAction
import com.hhst.youtubelite.library.domain.LibraryNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null

    private fun launchYou(signedIn: Boolean, scene: LibraryScenario = LibraryScenario.Content): ActivityScenario<YouActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<YouActivity>(YouActivity.intent(context, signedIn)
            .putExtra(YouActivity.EXTRA_SCENARIO, scene.argument)).also {
            scenario = it
            compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("you-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        }
    }

    @After fun close() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<SignInActivity>().forEach { it.finish() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun youSectionsRenderAndRowsEmitPersonalIdentities() {
        val host = launchYou(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("you-account").assertIsDisplayed()
        compose.onNodeWithTag("library-playlists-shelf").assertExists()
        compose.onNodeWithTag("library-history-shelf").assertExists()
        listOf("History", "Playlists", "WatchLater", "LikedVideos", "Queue", "Downloads").forEach { row ->
            compose.onNodeWithTag("library-row-$row").assertIsDisplayed()
        }
        compose.onNodeWithTag("you-screen").assertIsDisplayed()
        compose.onNodeWithTag("library-row-WatchLater").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(LibraryNavigationRequest.Playlist("WL"), it.lastNavigationRequest) }
        // Navigation leaves this host, so the second identity is asserted on a fresh launch.
        scenario?.close()
        val second = launchYou(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-row-LikedVideos").performClick()
        compose.waitForIdle()
        second.onActivity { assertEquals(LibraryNavigationRequest.Playlist("LL"), it.lastNavigationRequest) }
    }

    @Test fun signedOutYouPageShowsTheSampledPromptAndSignInStaysTyped() {
        val host = launchYou(signedIn = false)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-signed-out").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Don't miss new videos").assertIsDisplayed()
        // No fake session exists before the typed sign-in request.
        compose.onNodeWithTag("you-library").assertDoesNotExist()
        compose.onNodeWithTag("you-signed-out").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(LibraryNavigationRequest.SignIn, it.lastNavigationRequest) }
    }

    @Test fun downloadsRowEmitsOneTypedRequestWithoutOpeningTheManager() {
        val host = launchYou(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        host.onActivity { it.launchDownloads = false }
        compose.onNodeWithTag("library-row-Downloads").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(LibraryNavigationRequest.OpenDownloads, it.lastNavigationRequest) }
        compose.onNodeWithTag("you-library").assertIsDisplayed()
    }

    @Test fun mainTabsReuseExistingDestinationsInsteadOfStacking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_SCENARIO, HomeScenario.Feed.argument)).also { scenario = it }
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        compose.onNodeWithTag("nav-You").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("you-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        val you = scenario as ActivityScenario<*>
        you.onActivity { assertFalse(it.isFinishing) }
        compose.onNodeWithTag("main-nav-Home").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test fun searchOverlayEmitsTheQueryAndKeepsThePageUnderneath() {
        val host = launchYou(signedIn = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("you-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("search-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-input").performTextInput("open movies")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("you-library").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Search YouTube").onFirst().performClick()
        compose.onNodeWithTag("search-input").assertTextEquals("open movies")
        compose.onNodeWithTag("search-input").performImeAction()
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("search-results").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        host.onActivity { assertEquals(LibraryNavigationRequest.Search("open movies"), it.lastNavigationRequest) }
    }
}

class HistoryHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<HistoryActivity>? = null

    private fun launch(scene: LibraryScenario = LibraryScenario.Content): ActivityScenario<HistoryActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<HistoryActivity>(HistoryActivity.intent(context, scene)).also {
            scenario = it
            compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("history-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        }
    }

    @After fun close() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<SignInActivity>().forEach { it.finish() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun historySectionsRenderAndTheMenuEmitsOneRequestWithoutMutatingRows() {
        val host = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for Big Buck Bunny | A short film by the Blender Foundation")
            .performClick()
        compose.onNodeWithTag("video-menu").assertIsDisplayed()
        compose.onNodeWithText(HistoryMenuAction.RemoveFromHistory.label).performClick()
        compose.waitForIdle()
        host.onActivity {
            assertEquals(LibraryNavigationRequest.MenuAction("bunny", HistoryMenuAction.RemoveFromHistory), it.lastNavigationRequest)
        }
        compose.onNodeWithTag("video-menu").assertDoesNotExist()
        // The typed request never mutates the local history rows.
        compose.onNodeWithTag("history-item-history-bunny").assertIsDisplayed()
    }

    @Test fun emptyHistoryShowsItsOwnStateNotThePrompt() {
        launch(LibraryScenario.HistoryEmpty)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-signed-out").assertDoesNotExist()
    }

    @Test fun failedHistoryRecoversThroughRetry() {
        val host = launch(LibraryScenario.Error)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-error").fetchSemanticsNodes().isNotEmpty() }
        host.onActivity {
            it.historyViewModel.onAction(com.hhst.youtubelite.library.domain.LibraryAction.Retry)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-item-history-bunny").assertIsDisplayed()
    }
}
