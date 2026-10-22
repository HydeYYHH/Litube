package com.hhst.youtubelite.explore

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.domain.ExploreNavigationRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExploreHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<ExploreActivity>? = null

    private fun launch(scene: ExploreScenario = ExploreScenario.Content): ActivityScenario<ExploreActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch<ExploreActivity>(ExploreActivity.intent(context, null, scene)).also {
            scenario = it
            compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("explore-screen").fetchSemanticsNodes() }.getOrDefault(emptyList()).isNotEmpty() }
        }
    }

    @After fun close() {
        scenario?.close()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun categoryIntentAndSelectionSurviveRecreation() {
        val host = ActivityScenario.launch<ExploreActivity>(ExploreActivity.intent(
            ApplicationProvider.getApplicationContext(), com.hhst.youtubelite.explore.domain.ExploreCategory.Sports))
        scenario = host
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("explore-item-sports-bunny").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-chip-Gaming").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-item-gaming-bunny").fetchSemanticsNodes().isNotEmpty() }
        host.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("explore-item-gaming-bunny").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun fourCategoryChipsShareOneContainerAndSwapContent() {
        val host = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-item-music-bunny").assertIsDisplayed()
        compose.onNodeWithTag("explore-chip-Gaming").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-item-gaming-bunny").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-chip-News").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-item-news-bunny").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-chip-Sports").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-item-sports-bunny").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-item-music-bunny").assertDoesNotExist()
    }

    @Test fun openingsEmitTypedWatchAndChannelRequests() {
        val host = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-item-music-bunny").performClick()
        compose.waitForIdle()
        host.onActivity { assertEquals(ExploreNavigationRequest.Watch("music-bunny"), it.lastNavigationRequest) }
        // Navigation left the host; relaunch for the channel identity.
        scenario?.close()
        val second = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Go to channel Blender").onFirst().performClick()
        compose.waitForIdle()
        second.onActivity { assertEquals(ExploreNavigationRequest.Channel("blender"), it.lastNavigationRequest) }
    }

    @Test fun liveCategoryEntriesCarryTheLiveBadgeThroughWatch() {
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-chip-News").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-item-news-bunny").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-item-news-bunny").assertIsDisplayed()
    }
}
