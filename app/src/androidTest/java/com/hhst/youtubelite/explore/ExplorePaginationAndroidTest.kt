package com.hhst.youtubelite.explore

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.explore.data.ExploreScenario
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ExplorePaginationAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<ExploreActivity>? = null

    private fun launch(scene: ExploreScenario): ActivityScenario<ExploreActivity> {
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

    @Test fun pageTailAppendsTheSecondPageOnceAndEnds() {
        launch(ExploreScenario.Paged)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-item-music-bunny").assertIsDisplayed()
        // Reaching the last loaded item puts the tail on screen → asks for page two.
        compose.onNodeWithTag("explore-list").performScrollToNode(hasTestTag("explore-item-music-live"))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("explore-item-music-bunny-p2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-list").performScrollToNode(hasTestTag("explore-item-music-live-p2"))
        compose.onNodeWithTag("explore-item-music-live-p2").assertIsDisplayed()
        compose.waitForIdle()
        // Canned page carried no cursor: no spinner, no retry, no third page.
        compose.onAllNodesWithTag("explore-tail-loading").assertCountEquals(0)
        compose.onAllNodesWithTag("explore-tail-retry").assertCountEquals(0)
        compose.onAllNodesWithTag("explore-item-music-bunny-p3").assertCountEquals(0)
    }

    @Test fun shelfTailAppendsIntoItsOwnCarouselOnly() {
        launch(ExploreScenario.Sections)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-sections").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-section-0-title").assertIsDisplayed()
        // Shelf tail fires once visible: on wide windows it is already on screen and
        // the append may finish before a scroll finds the tile, so scroll is best-effort.
        runCatching { compose.onNodeWithTag("explore-carousel-0").performScrollToNode(hasTestTag("explore-shelf-0-tail")) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("explore-item-music-bunny-s0-1").fetchSemanticsNodes().isNotEmpty() }
        listOf("music-bunny-s0-1", "music-dream-s0-1").forEach { id ->
            compose.onNodeWithTag("explore-carousel-0").performScrollToNode(hasTestTag("explore-item-$id"))
            compose.onNodeWithTag("explore-item-$id").assertIsDisplayed()
        }
        compose.onAllNodesWithTag("explore-item-music-s1-bunny").assertCountEquals(1)
        compose.onAllNodesWithTag("explore-item-music-bunny-s0-1").assertCountEquals(1)
    }

    @Test fun sectionedPageTailAppendsANewShelfBelow() {
        launch(ExploreScenario.Sections)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-sections").fetchSemanticsNodes().isNotEmpty() }
        // Page tail sits below both shelves and asks for the next page once visible.
        compose.onNodeWithTag("explore-sections").performScrollToIndex(4)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("explore-section-2-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explore-section-2-title").assertIsDisplayed()
        compose.onNodeWithTag("explore-carousel-2").performScrollToNode(hasTestTag("explore-item-music-bunny-p2"))
        compose.onNodeWithTag("explore-item-music-bunny-p2").assertIsDisplayed()
    }
}
