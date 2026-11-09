package com.hhst.youtubelite.playlist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.PlaylistScreen
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistScreenAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val summary = FixturePlaylistRepository.summaries().first()
    private fun ready() = PlaylistContent.Ready(PlaylistPage(summary, "Watch the open movies made with Blender!", FixturePlaylistRepository.entries()))
    private fun state(content: PlaylistContent = ready(), overlay: PlaylistOverlay = PlaylistOverlay.None) = PlaylistUiState(summary.id, content, overlay = overlay)

    @Test fun phoneAndWideAnchorsMatchTheReference() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var content by mutableStateOf<PlaylistContent>(ready())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { PlaylistScreen(state(content), {}, {}) }
            }
        }
        var cover = compose.onNodeWithTag("playlist-hero-cover").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, cover.left, 1f)
        assertEquals(64f, cover.top, 1f)
        assertEquals(358f, cover.width, 1f)
        assertEquals(201.4375f, cover.height, 1f)
        val heading = compose.onNodeWithTag("playlist-heading", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(281.4375f, heading.top, 1f)
        val menu = compose.onNodeWithTag("playlist-entry-menu-bunny-1").fetchSemanticsNode().boundsInRoot
        assertEquals(344f, menu.left, 1f)
        assertEquals(40f, menu.width, 1f)
        val phoneThumb = compose.onNodeWithTag("playlist-entry-cover-bunny-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(phoneThumb.top - 6f, menu.top, 1f)
        compose.runOnIdle { width = 1280; height = 800 }
        cover = compose.onNodeWithTag("playlist-hero-cover").fetchSemanticsNode().boundsInRoot
        assertEquals(427f, cover.width, 1f)
        assertEquals(240f, cover.height, 1f)
        val first = compose.onNodeWithTag("playlist-entry-cover-bunny-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(16f, first.left, 1f)
        assertEquals(328f, first.top, 2f)
        assertEquals(160f, first.width, 1f)
        assertEquals(90f, first.height, 1f)
        for ((w, h) in listOf(839 to 800, 840 to 800, 1023 to 800, 1024 to 800, 1280 to 1104, 1600 to 800)) {
            compose.runOnIdle { width = w; height = h }
            cover = compose.onNodeWithTag("playlist-hero-cover").fetchSemanticsNode().boundsInRoot
            val expectedWidth = when { w < 840 -> (w - 32).toFloat(); w < 1024 -> 284f; else -> 427f }
            val expectedHeight = when { w < 840 -> expectedWidth * 9f / 16f; w < 1024 -> 160f; else -> 240f }
            assertEquals("cover width at $w", expectedWidth, cover.width, 1f)
            assertEquals("cover height at $w", expectedHeight, cover.height, 1f)
        }
        compose.runOnIdle { width = 1280; content = PlaylistContent.Loading }
        val loadingCover = compose.onNodeWithTag("playlist-loading-cover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(427f, loadingCover.width, 1f)
        assertEquals(240f, loadingCover.height, 1f)
    }

    @Test fun anEmptyPageWithAContinuationDoesNotAnnounceAnEmptyPlaylist() {
        val page = (ready() as PlaylistContent.Ready).page.copy(entries = emptyList(), nextKey = "next")
        var current by mutableStateOf(state(PlaylistContent.Ready(page)))
        val actions = mutableListOf<PlaylistAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { PlaylistScreen(current, actions::add, {}) }
            }
        }
        compose.waitUntil(5_000) { PlaylistAction.LoadNext in actions }
        compose.onNodeWithTag("playlist-empty").assertDoesNotExist()
        compose.runOnIdle { current = state(PlaylistContent.Ready(page.copy(nextKey = null))) }
        compose.onNodeWithTag("playlist-list").performScrollToNode(hasTestTag("playlist-empty"))
        compose.onNodeWithTag("playlist-empty").assertIsDisplayed()
    }

    @Test fun actionsAndMenusKeepTheirIdentity() {
        val actions = mutableListOf<PlaylistAction>()
        var current by mutableStateOf(state())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { PlaylistScreen(current, actions::add, {}) }
            }
        }
        compose.onNodeWithTag("playlist-play-all").performClick()
        assertEquals(PlaylistAction.PlayAll, actions.last())
        compose.onNodeWithTag("playlist-description").performClick()
        assertEquals(PlaylistAction.ToggleDescription, actions.last())
        compose.onNodeWithContentDescription("More playlist actions").performClick()
        assertEquals(PlaylistAction.OpenMenu, actions.last())
        compose.runOnIdle { current = state(overlay = PlaylistOverlay.Menu) }
        compose.onNodeWithTag("playlist-shuffle").assertIsDisplayed().performClick()
        assertEquals(PlaylistAction.Shuffle, actions.last())
        compose.onNodeWithTag("playlist-heading").assertDoesNotExist()
        compose.runOnIdle { current = state(overlay = PlaylistOverlay.VideoMenu("dream-1")) }
        compose.onNodeWithTag("playlist-video-action-WatchLater").performClick()
        assertEquals(PlaylistAction.VideoMenuAction(PlaylistVideoAction.WatchLater), actions.last())
        compose.onNodeWithContentDescription("Close menu").performClick()
        // The sheet plays its exit motion before the overlay callback lands.
        compose.waitUntil(5_000) { actions.lastOrNull() == PlaylistAction.CloseOverlay }
        assertEquals(PlaylistAction.CloseOverlay, actions.last())
    }

    @Test fun playlistSearchCardsMatchTheMeasuredLockupAndStride() {
        var width by mutableIntStateOf(390)
        val playlists = FixturePlaylistRepository.summaries()
        val actions = mutableListOf<SearchAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.dp, 844.dp)) {
                    SearchResultsScreen(SearchUiState("open movie", SearchContent.Results(SearchPage("open movie", emptyList(), playlists)),
                        filters = SearchFilters(type = SearchResultType.Playlists), signedInPreview = false), actions::add, {})
                }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        var title = bounds("playlist-title-${summary.id}")
        assertEquals(64f, title.left, 1f)
        assertEquals(278.375f, title.top, 1f)
        var menu = compose.onNodeWithContentDescription("More actions for ${summary.title}").fetchSemanticsNode().boundsInRoot
        assertEquals(342f, menu.left, 1f)
        assertEquals(270.375f, menu.top, 1f)
        assertEquals(48f, menu.height, 1f)
        compose.onNodeWithContentDescription("More actions for ${summary.title}").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(center.x, 2f))
        }
        assertEquals(SearchAction.OpenPlaylistMenu(summary.id), actions.last())
        val first = bounds("playlist-search-${summary.id}")
        val second = bounds("playlist-search-${playlists[1].id}")
        assertEquals(297.375f, second.top - first.top, 2f)
        compose.runOnIdle { width = 1280 }
        title = bounds("playlist-title-${summary.id}")
        assertEquals(444f, title.left, 1f)
        assertEquals(65f, title.top, 1f)
        menu = compose.onNodeWithContentDescription("More actions for ${summary.title}").fetchSemanticsNode().boundsInRoot
        assertEquals(996f, menu.left, 1f)
        assertEquals(40f, menu.height, 1f)
        compose.onNodeWithContentDescription("More actions for ${summary.title}").performClick()
        assertEquals(SearchAction.OpenPlaylistMenu(summary.id), actions.last())
    }

    @Test fun viewportStatesAndMenusMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var font by mutableFloatStateOf(1f)
        var scene by mutableStateOf("detail")
        var current by mutableStateOf(state())
        val channel = ChannelPage(FixtureChannelRepository.profile(), FixtureChannelRepository.sampleVideos(), FixturePlaylistRepository.summaries())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    when (scene) {
                        "search" -> SearchResultsScreen(SearchUiState("open movie", SearchContent.Results(SearchPage("open movie", emptyList(), FixturePlaylistRepository.summaries())),
                            filters = SearchFilters(type = SearchResultType.Playlists), signedInPreview = false), {}, {})
                        "channel" -> ChannelScreen(ChannelUiState(content = ChannelContent.Ready(channel), selectedTab = ChannelTab.Playlists,
                            playlists = ChannelSection.Ready(FixturePlaylistRepository.summaries())), {}, {})
                        else -> PlaylistScreen(current, {}, {})
                    }
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.filesDir, "playlist-acceptance").apply { mkdirs() }
        val record = InstrumentationRegistry.getArguments().getString("recordPlaylistGoldens") == "true"
        for (w in listOf(320, 390, 411, 599, 600, 839, 840, 1023, 1024, 1280, 1600)) {
            compose.runOnIdle { width = w; height = if (w >= 700) 800 else 844; font = 1f }
            for (name in listOf("detail", "search", "channel")) {
                compose.runOnIdle { scene = name; current = state() }
                val tag = when (name) { "search" -> "search-results"; "channel" -> "channel-page"; else -> "playlist-page" }
                compose.onNodeWithTag(tag).assertIsDisplayed()
                if (name == "channel") compose.onNodeWithTag("channel-playlists-list").performScrollToNode(hasTestTag("channel-playlist-${summary.id}"))
                capture("$name-$w", tag, output, record, w in listOf(390, 1280))
            }
            for ((name, content) in listOf("loading" to PlaylistContent.Loading, "error" to PlaylistContent.Failed,
                "unavailable" to PlaylistContent.Unavailable, "empty" to PlaylistContent.Ready((ready() as PlaylistContent.Ready).page.copy(
                    summary = summary.copy(videoCount = 0), entries = emptyList())))) {
                compose.runOnIdle { scene = "detail"; current = state(content) }
                if (name == "empty") compose.onNodeWithTag("playlist-list")
                    .performScrollToNode(hasTestTag("playlist-empty"))
                // playlist-loading clears its subtree semantics; query the unmerged tree.
                compose.onNodeWithTag("playlist-$name", useUnmergedTree = true).assertIsDisplayed()
                capture("$name-$w", "playlist-page", output, record, w in listOf(390, 1280))
            }
            for ((name, overlay) in listOf("menu" to PlaylistOverlay.Menu, "video-menu" to PlaylistOverlay.VideoMenu("bunny-1"))) {
                compose.runOnIdle { scene = "detail"; current = state(overlay = overlay) }
                compose.onNodeWithTag(if (name == "menu") "playlist-menu" else "playlist-video-menu").assertIsDisplayed()
                capture("$name-$w", "playlist-page", output, record, w in listOf(390, 1280))
            }
        }
        for ((w, h) in listOf(320 to 240, 840 to 390, 240 to 320, 390 to 400)) {
            compose.runOnIdle { width = w; height = h; font = 2f; scene = "detail"; current = state() }
            compose.onNodeWithTag("playlist-list").performScrollToNode(hasTestTag("playlist-actions"))
            compose.onNodeWithTag("playlist-actions").performScrollTo()
            compose.onNodeWithContentDescription("More playlist actions").performScrollTo()
            capture("actions-$w-$h-font2", "playlist-page", output, record, false)
            compose.onNodeWithContentDescription("More playlist actions").assertIsDisplayed()
            compose.onNodeWithTag("playlist-list").performScrollToNode(hasTestTag("playlist-entry-bunny-1"))
            compose.onNodeWithTag("playlist-entry-bunny-1").assertIsDisplayed()
            val title = compose.onNodeWithTag("playlist-entry-title-bunny-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Readable title column at $w", title.width >= 80f)
            capture("detail-$w-$h-font2", "playlist-page", output, record, false)
            compose.runOnIdle { scene = "search" }
            compose.onNodeWithTag("playlist-search-list").assertIsDisplayed()
            capture("search-$w-$h-font2", "search-results", output, record, false)
            compose.runOnIdle { scene = "channel" }
            compose.onNodeWithTag("channel-playlists-list").performScrollToNode(hasTestTag("channel-playlist-${summary.id}"))
            compose.onNodeWithTag("channel-playlist-${summary.id}").assertIsDisplayed()
            capture("channel-$w-$h-font2", "channel-page", output, record, false)
        }
    }

    private fun capture(name: String, tag: String, output: File, record: Boolean, compare: Boolean) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!record && compare) {
            val expected = InstrumentationRegistry.getInstrumentation().context.assets.open("playlist/goldens/$name.png").use(BitmapFactory::decodeStream)
            assertEquals(name, expected.width, actual.width)
            assertEquals(name, expected.height, actual.height)
            val a = IntArray(actual.width * actual.height)
            val b = IntArray(a.size)
            actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
            expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
            val changed = a.indices.count { i -> listOf(0, 8, 16).any { s -> abs((a[i] shr s and 255) - (b[i] shr s and 255)) > 2 } }
            assertTrue("$name: $changed changed pixels", changed.toFloat() / a.size <= 0.001f)
            expected.recycle()
        }
        actual.recycle()
    }
}
