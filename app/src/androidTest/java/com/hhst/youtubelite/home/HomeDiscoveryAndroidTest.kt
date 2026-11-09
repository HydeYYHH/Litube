package com.hhst.youtubelite.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeScreen
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeDiscoveryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chart = FeedItem.Playlist(PlaylistSummary("chart", "Top songs", "Music Charts", "", 100, "bunny", "", null))
    private val video = FeedItem.Video("game", "Game highlights", "Player", "1M views", "bunny", "4:00", "channel")
    private val page = HomePage(listOf(video, video.copy(id = "second"), video.copy(id = "third")), catalog = HomeCatalog.Popular)

    @Test fun topFiltersSwitchBetweenVideosAndTypedChartDestinations() {
        val actions = mutableListOf<HomeAction>()
        var selected by mutableStateOf("All")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    val current = if (selected == "Music charts") HomePage(listOf(chart), catalog = HomeCatalog.MusicCharts) else page
                    HomeScreen(HomeUiState(content = HomeContent.Feed(current), selectedChip = selected, chips = HomeCatalog.filters), {
                        actions += it
                        if (it is HomeAction.SelectChip) selected = it.chip
                    })
                }
            }
        }
        compose.onNodeWithTag("feed-item-game").assertIsDisplayed()
        compose.onNodeWithText("Music charts").performClick()
        compose.onNodeWithText("Top songs").performClick()
        assertEquals(HomeAction.Navigate(HomeNavigationRequest.Playlist("chart")), actions.last())
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithTag("feed-item-game").assertIsDisplayed()
        compose.onAllNodesWithTag("feed-item-game").assertCountEquals(1)
    }

    @Test fun partialSourceRetryAndResponsiveFrames() {
        var width by mutableIntStateOf(390)
        var scale by mutableFloatStateOf(1f)
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    HomeScreen(HomeUiState(chips = HomeCatalog.filters,
                        content = HomeContent.Feed(page.copy(nextKey = "retry", partialFailure = true),
                        append = AppendState.Failed)), { actions += it })
                }
            }
        }
        val output = File(compose.activity.getExternalFilesDir(null), "home-discovery").apply { mkdirs() }
        for ((w, font) in listOf(320 to 2f, 390 to 1f, 1280 to 1f)) {
            compose.runOnIdle { width = w; scale = font }
            compose.onNodeWithTag("home-feed").performScrollToIndex(0)
            compose.onNodeWithText("All").assertIsDisplayed()
            val bounds = compose.onNodeWithTag("feed-item-game").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width > 0 && bounds.width <= w)
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(output, "public-$w-font$font.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Something went wrong. Tap to retry."))
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(HomeAction.LoadNext, actions.last()) }
    }
    @Test fun anEmptyCategoryKeepsFiltersAvailable() {
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            HomeScreen(HomeUiState(chips = HomeCatalog.filters, selectedChip = "Music charts",
                content = HomeContent.Empty()), { actions += it })
        }
        compose.onNodeWithText("All").assertIsDisplayed().performClick()
        assertEquals(HomeAction.SelectChip("All"), actions.last())
    }

}
