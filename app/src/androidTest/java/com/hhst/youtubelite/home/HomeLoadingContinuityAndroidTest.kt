package com.hhst.youtubelite.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeLoadingContinuityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingKeepsChromeInteractiveAndMatchesTheFirstCardAtEverySize() {
        var width by mutableIntStateOf(390)
        var fontScale by mutableFloatStateOf(1f)
        var content by mutableStateOf<HomeContent>(HomeContent.Loading)
        val video = FeedItem.Video("first", "First video", "Channel", "1 view", "bunny", "4:00", "channel")
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    HomeScreen(HomeUiState(content = content), { actions += it })
                }
            }
        }

        for ((nextWidth, nextScale) in listOf(390 to 1f, 1280 to 1f, 390 to 2f)) {
            compose.runOnIdle { width = nextWidth; fontScale = nextScale; content = HomeContent.Loading }
            compose.onNodeWithTag("home-topbar").assertIsDisplayed()
            compose.onNodeWithTag("home-chips").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search YouTube").performClick()
            compose.onNodeWithTag("nav-You").performClick()
            val placeholder = compose.onNodeWithTag("skeleton-thumb-0", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val navigation = compose.onNodeWithTag("home-navigation").fetchSemanticsNode().boundsInRoot

            compose.runOnIdle { content = HomeContent.Feed(HomePage(listOf(video))) }
            val thumbnail = compose.onNodeWithContentDescription("${video.title}, ${video.duration}")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(placeholder.left, thumbnail.left, 0.5f)
            assertEquals(placeholder.top, thumbnail.top, 0.5f)
            assertEquals(placeholder.width, thumbnail.width, 0.5f)
            assertEquals(placeholder.height, thumbnail.height, 0.5f)
            assertEquals(navigation, compose.onNodeWithTag("home-navigation").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("home-feed").performTouchInput { swipeUp() }
            compose.onNodeWithTag("home-topbar").assertIsDisplayed()
            compose.onNodeWithTag("home-chips").assertIsDisplayed()
        }
        assertTrue(actions.contains(HomeAction.OpenSearch))
        assertTrue(actions.contains(HomeAction.Navigate(HomeNavigationRequest.Destination(HomeDestination.You))))
    }
}
