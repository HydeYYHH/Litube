package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.explore.presentation.ExploreScreen
import com.hhst.youtubelite.library.domain.*
import com.hhst.youtubelite.library.presentation.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.PlaylistScreen
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsScreen
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsScreen
import com.hhst.youtubelite.ui.design.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Every supported route can recover from an error through the same real pull gesture. */
@RunWith(Parameterized::class)
class RefreshCoverageAndroidTest(private val screen: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pullDispatchesThePagesRefreshActionOnce() {
        var refreshes = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                AppTheme {
                    Box(Modifier.requiredSize(390.dp, 844.dp)) {
                        when (screen) {
                            "you-screen" -> YouScreen(LibraryUiState(content = LibraryContent.Failed),
                                { if (it == LibraryAction.Retry) refreshes++ })
                            "history-screen" -> HistoryScreen(HistoryUiState(content = HistoryContent.Failed),
                                { if (it == LibraryAction.Retry) refreshes++ }, {})
                            "playlists-screen" -> CollectionScreen(CollectionUiState(content = CollectionContent.Failed),
                                { if (it == LibraryAction.Retry) refreshes++ }, {})
                            "explore-screen" -> ExploreScreen(ExploreUiState(content = ExploreContent.Failed),
                                { if (it == ExploreAction.Retry) refreshes++ }, {})
                            "channel-page" -> ChannelScreen(ChannelUiState(content = ChannelContent.Failed),
                                { if (it == ChannelAction.Retry) refreshes++ }, {})
                            "playlist-page" -> PlaylistScreen(PlaylistUiState("PLtest", content = PlaylistContent.Failed),
                                { if (it == PlaylistAction.Retry) refreshes++ }, {})
                            "search-results" -> SearchResultsScreen(SearchUiState(query = "test", content = SearchContent.Failed),
                                { if (it == SearchAction.Retry) refreshes++ }, {})
                            "subscriptions-screen" -> SubscriptionsScreen(SubscriptionsUiState(signedIn = true, content = SubscriptionsContent.Failed),
                                { if (it == SubscriptionsAction.Retry) refreshes++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(screen).performTouchInput {
            swipe(Offset(center.x, 350f), Offset(center.x, 720f))
        }
        compose.waitForIdle()
        assertEquals("Refresh must be connected on $screen", 1, refreshes)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun screens() = listOf(
            "you-screen", "history-screen", "playlists-screen", "explore-screen", "channel-page",
            "playlist-page", "search-results", "subscriptions-screen",
        )
    }
}
