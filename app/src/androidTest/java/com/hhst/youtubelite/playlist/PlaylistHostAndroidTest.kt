package com.hhst.youtubelite.playlist

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.channel.ChannelActivity
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.playlist.data.*
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.search.SearchResultsActivity
import com.hhst.youtubelite.search.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityScenario<out Activity>? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val id = FixturePlaylistRepository.MOVIES

    private fun await(tag: String) = compose.waitUntil(15_000) {
        runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }
    private fun back() { instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); compose.waitForIdle() }
    @After fun close() { host?.close(); instrumentation.waitForIdleSync() }

    @Test fun searchFilterOpensPlaylistAndReturnsToItsQueryAndCards() {
        val results = ActivityScenario.launch<SearchResultsActivity>(SearchResultsActivity.intent(ApplicationProvider.getApplicationContext(), "open movie"))
        host = results
        await("search-results-list")
        results.onActivity { it.searchViewModel.onAction(SearchAction.OpenFilters); it.searchViewModel.onAction(SearchAction.SelectResultType(SearchResultType.Playlists)) }
        await("playlist-search-list")
        compose.onNodeWithTag("playlist-title-$id", useUnmergedTree = true).performClick()
        await("playlist-heading")
        back()
        await("playlist-search-list")
        results.onActivity {
            assertEquals("open movie", it.searchViewModel.uiState.value.query)
            assertEquals(SearchResultType.Playlists, it.searchViewModel.uiState.value.filters.type)
            assertTrue(it.searchViewModel.uiState.value.filtersOpen)
        }
        results.recreate()
        await("playlist-search-list")
        compose.onNodeWithTag("search-filter-type").assertTextEquals("Playlists")
    }

    @Test fun channelMenuSharesPlaylistAndKeepsTabOnReturnAndRecreation() {
        val channel = ActivityScenario.launch<ChannelActivity>(ChannelActivity.intent(ApplicationProvider.getApplicationContext(), "blender"))
        host = channel
        await("channel-home-list")
        channel.onActivity { it.shareGateway.launchChooser = false; it.channelViewModel.onAction(ChannelAction.SelectTab(ChannelTab.Playlists)) }
        await("channel-playlists-list")
        compose.onNodeWithTag("channel-playlists-list").performScrollToNode(hasTestTag("channel-playlist-$id"))
        compose.onNodeWithContentDescription("More actions for Official Blender Open Movies").performClick()
        await("playlist-share-menu")
        compose.onNodeWithTag("playlist-menu-share").performClick()
        channel.onActivity { assertEquals("https://www.youtube.com/playlist?list=$id", it.shareGateway.lastShareIntent?.getStringExtra(Intent.EXTRA_TEXT)) }
        compose.onNodeWithTag("playlist-title-$id", useUnmergedTree = true).performClick()
        await("playlist-heading")
        back()
        await("channel-playlists-list")
        channel.recreate()
        await("channel-playlists-list")
        channel.onActivity { assertEquals(ChannelTab.Playlists, it.channelViewModel.uiState.value.selectedTab) }
    }

    @Test fun playlistBackClosesMenuThenSearchAndRestoresDraft() {
        val playlist = ActivityScenario.launch<PlaylistActivity>(PlaylistActivity.intent(ApplicationProvider.getApplicationContext(), id))
        host = playlist
        await("playlist-heading")
        com.hhst.youtubelite.core.DeviceEvidence.captureScene("playlist-host-detail")
        compose.onNodeWithContentDescription("More playlist actions").performClick()
        await("playlist-menu")
        back()
        await("playlist-heading")
        compose.onNodeWithContentDescription("Search").performClick()
        await("search-overlay")
        compose.onNodeWithTag("search-input").performTextInput("open movie")
        playlist.recreate()
        await("search-overlay")
        compose.onNodeWithTag("search-input").assertTextEquals("open movie")
        compose.onNodeWithContentDescription("Back").performClick()
        await("playlist-heading")
        playlist.onActivity {
            assertEquals(PlaylistOverlay.None, it.playlistViewModel.uiState.value.overlay)
            fun hasBrowser(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { index -> hasBrowser(view.getChildAt(index)) }
            assertFalse(hasBrowser(it.window.decorView))
        }
    }

    @Test fun entriesOpenTheExistingPlayerHostAndReturnWithoutLosingPlaylist() {
        val playlist = ActivityScenario.launch<PlaylistActivity>(PlaylistActivity.intent(ApplicationProvider.getApplicationContext(), id))
        host = playlist
        await("playlist-list")
        compose.onNodeWithTag("playlist-list").performScrollToNode(hasTestTag("playlist-entry-cover-dream-1"))
        compose.onNodeWithTag("playlist-entry-cover-dream-1").performClick()
        await("watch-screen")
        compose.onNodeWithTag("watch-metadata").assertIsDisplayed()
        back()
        await("playlist-list")
        playlist.onActivity { assertEquals(PlaylistNavigationRequest.Watch("dream"), it.lastNavigationRequest) }
        compose.onNodeWithTag("playlist-entry-cover-dream-1").assertIsDisplayed()
    }
}
