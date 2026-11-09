package com.hhst.youtubelite.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.HomeAction
import com.hhst.youtubelite.home.domain.HomeContent
import com.hhst.youtubelite.home.domain.HomeUiState
import com.hhst.youtubelite.home.presentation.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class HomeRefreshAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun feed(refreshing: Boolean = false, userRefresh: Boolean = false) =
        (HomeScenario.Feed.initialContent() as HomeContent.Feed)
            .copy(refreshing = refreshing, userRefresh = userRefresh)

    /** Mirrors the ViewModel contract: one load slot; repeats while busy are dropped. */
    private fun hostState(refreshing: Boolean, userRefresh: Boolean) = HomeUiState(
        content = feed(refreshing = refreshing, userRefresh = userRefresh), signedInPreview = true)

    @Test fun pullPastThresholdRefreshesExactlyOnceAndAnchorsBelowTheChips() {
        var state by mutableStateOf(hostState(refreshing = false, userRefresh = false))
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(state, { action ->
                        if (action == HomeAction.Refresh && !(state.content as HomeContent.Feed).refreshing) {
                            actions += action
                            state = state.copy(content = (state.content as HomeContent.Feed)
                                .copy(refreshing = true, userRefresh = true))
                        }
                    })
                }
            }
        }
        compose.onNodeWithTag("home-feed").performTouchInput {
            swipe(Offset(center.x, 100f), Offset(center.x, 700f))
        }
        compose.waitForIdle()
        assertEquals(1, actions.count { it == HomeAction.Refresh })
        val chips = compose.onNodeWithTag("home-chips").fetchSemanticsNode().boundsInRoot
        val topBar = compose.onNodeWithTag("home-topbar").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("home-refresh-indicator").fetchSemanticsNode().boundsInRoot
        assertTrue("indicator must start below the chip strip", indicator.top >= chips.bottom)
        assertTrue("indicator must start below the top bar", indicator.top >= topBar.bottom)
    }

    @Test fun shortPullSpringsBackWithoutRefreshing() {
        var state by mutableStateOf(hostState(refreshing = false, userRefresh = false))
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(state, { action -> if (action == HomeAction.Refresh) actions += action })
                }
            }
        }
        compose.onNodeWithTag("home-feed").performTouchInput {
            swipe(Offset(center.x, 300f), Offset(center.x, 340f))
        }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("home-refresh-indicator").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, actions.size)
        compose.onNodeWithTag("home-refresh-indicator").assertDoesNotExist()
    }

    @Test fun repeatedPullsDuringOneRefreshStayOneRequest() {
        var state by mutableStateOf(hostState(refreshing = false, userRefresh = false))
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(state, { action ->
                        if (action == HomeAction.Refresh && !(state.content as HomeContent.Feed).refreshing) {
                            actions += action
                            state = state.copy(content = (state.content as HomeContent.Feed)
                                .copy(refreshing = true, userRefresh = true))
                        }
                    })
                }
            }
        }
        repeat(2) {
            compose.onNodeWithTag("home-feed").performTouchInput {
                swipe(Offset(center.x, 150f), Offset(center.x, 700f))
            }
        }
        assertEquals(1, actions.count { it == HomeAction.Refresh })
        compose.onNodeWithTag("home-refresh-indicator").assertIsDisplayed()
    }

    @Test fun quietRefreshPaintsNoIndicator() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(hostState(refreshing = true, userRefresh = false), {})
                }
            }
        }
        compose.onNodeWithTag("home-refresh-indicator").assertDoesNotExist()
    }

    @Test fun holdingPastTheThresholdWaitsForRelease() {
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(hostState(refreshing = false, userRefresh = false), actions::add)
                }
            }
        }
        val feed = compose.onNodeWithTag("home-feed")
        feed.performTouchInput {
            down(Offset(center.x, 150f))
            moveBy(Offset(0f, 300f))
        }
        compose.waitForIdle()
        val pulled = compose.onNodeWithTag("home-feed").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(800)
        Thread.sleep(400)
        compose.waitForIdle()
        assertEquals(0, actions.count { it == HomeAction.Refresh })
        assertEquals(pulled, feed.fetchSemanticsNode().boundsInRoot.top, 0.5f)
        feed.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, actions.count { it == HomeAction.Refresh })
        compose.onNodeWithTag("home-refresh-indicator").assertDoesNotExist()
    }

    @Test fun cancelledPullSettlesWithoutRefreshing() {
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    HomeScreen(hostState(refreshing = false, userRefresh = false), actions::add)
                }
            }
        }
        compose.onNodeWithTag("home-feed").performTouchInput {
            down(Offset(center.x, 150f))
            moveBy(Offset(0f, 300f))
            cancel()
        }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("home-refresh-indicator").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, actions.count { it == HomeAction.Refresh })
    }

    @Test fun emptyHomeKeepsItsCardAndOnlyUserRefreshShowsTheIndicator() {
        var state by mutableStateOf(HomeUiState(content = HomeContent.Empty(refreshing = true, userRefresh = true)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { HomeScreen(state, {}) }
            }
        }
        compose.onNodeWithTag("empty-message").assertIsDisplayed()
        val topBar = compose.onNodeWithTag("home-topbar").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("home-refresh-indicator").fetchSemanticsNode().boundsInRoot
        assertTrue("empty-home indicator must start below the top bar", indicator.top >= topBar.bottom)
        compose.runOnIdle { state = HomeUiState(content = HomeContent.Empty(refreshing = true, userRefresh = false)) }
        compose.onNodeWithTag("empty-message").assertIsDisplayed()
        compose.onNodeWithTag("home-refresh-indicator").assertDoesNotExist()
    }

    @Test fun chromeHoldsItsPlaceWhileContentIsPulled() {
        var state by mutableStateOf(hostState(refreshing = false, userRefresh = false))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { HomeScreen(state, {}) }
            }
        }
        val topBarBefore = compose.onNodeWithTag("home-topbar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("home-feed").performTouchInput {
            down(Offset(center.x, 200f))
            moveBy(Offset(0f, 300f))
        }
        val topBarDuring = compose.onNodeWithTag("home-topbar").fetchSemanticsNode().boundsInRoot
        assertEquals("top bar must not move with the pull", topBarBefore.top, topBarDuring.top, 0.5f)
        val indicator = compose.onNodeWithTag("home-refresh-indicator").fetchSemanticsNode().boundsInRoot
        val chips = compose.onNodeWithTag("home-chips").fetchSemanticsNode().boundsInRoot
        assertTrue(indicator.top >= chips.bottom)
        compose.onNodeWithTag("home-feed").performTouchInput { up() }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithTag("home-refresh-indicator").fetchSemanticsNodes().isEmpty()
        }
    }
}
