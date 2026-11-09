package com.hhst.youtubelite.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
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
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChannelDiscoveryScreenAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val page = ChannelPage(FixtureChannelRepository.profile(), FixtureChannelRepository.sampleVideos(),
        homeShelves = FixtureChannelRepository.samplePage().homeShelves)
    private fun channel(about: Boolean = false, query: String = "") = ChannelUiState(content = ChannelContent.Ready(page),
        selectedTab = if (about) ChannelTab.Home else ChannelTab.Search, overlay = if (about) ChannelOverlay.About else ChannelOverlay.None,
        searchQuery = query, submittedQuery = query)
    private fun search() = SearchUiState("blender", SearchContent.Results(SearchPage("blender", emptyList(), channels = FixtureSearchRepository.sampleChannels())),
        signedInPreview = false, filters = SearchFilters(type = SearchResultType.Channels))

    @Test fun channelCardsUseTheirOwnMeasuredLockup() {
        var width by mutableIntStateOf(390)
        val actions = mutableListOf<SearchAction>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            Box(Modifier.requiredSize(width.dp, 844.dp)) { SearchResultsScreen(search(), actions::add, {}) }
        } }
        for (w in listOf(390, 1280)) {
            compose.runOnIdle { width = w }
            val inset = if (w == 1280) 220 else 0
            val avatar = compose.onNodeWithTag("search-channel-avatar-blender", true).fetchSemanticsNode().boundsInRoot
            assertEquals(inset + 58.5f, avatar.left, 1f)
            assertEquals(83.5f, avatar.top, 1.5f)
            assertEquals(67f, avatar.width, .1f)
            val title = compose.onNodeWithTag("search-channel-name-blender", true).fetchSemanticsNode().boundsInRoot
            assertEquals(inset + 180f, title.left, .1f)
            assertEquals(72f, title.top, .1f)
            compose.onNodeWithTag("search-channel-subscribe-blender").performClick()
            assertEquals(SearchAction.Subscribe("blender"), actions.last())
        }
    }

    @Test fun aboutPanelFollowsSampledPhoneAndRailAnchors() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            Box(Modifier.requiredSize(width.dp, height.dp)) { ChannelScreen(channel(about = true), {}, {}) }
        } }
        for ((w, h, x, y, panelWidth) in listOf(
            listOf(390f, 844f, 0f, 267.375f, 390f), listOf(600f, 800f, 0f, 385.5f, 600f),
            listOf(840f, 800f, 0f, 424.5f, 840f), listOf(931f, 800f, 675f, 48f, 256f),
            listOf(1280f, 800f, 780f, 48f, 500f))) {
            compose.runOnIdle { width = w.toInt(); height = h.toInt() }
            val panel = compose.onNodeWithTag("channel-about-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(x, panel.left, .6f); assertEquals(y, panel.top, .6f); assertEquals(panelWidth, panel.width, .6f)
            compose.onNodeWithContentDescription("Close channel details").assertIsDisplayed()
        }
        val row = compose.onNodeWithTag("channel-about-detail-0", true).fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("channel-about-detail-icon-0", true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithTag("channel-about-detail-value-0", true).fetchSemanticsNode().boundsInRoot
        assertEquals(row.top, icon.top, .1f)
        assertEquals(row.top + 2f, value.top, .1f)
        assertEquals(24f, icon.width, .1f)
    }

    @Test fun statesAndWindowSizesMatchGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var font by mutableFloatStateOf(1f)
        var scene by mutableStateOf("channels")
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
            key(scene) { Box(Modifier.requiredSize(width.dp, height.dp)) {
                if (scene == "channels") SearchResultsScreen(search(), {}, {})
                else ChannelScreen(channel(about = scene == "about", query = if (scene == "empty") "no-matching-local-title" else ""), {}, {})
            } }
        } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val record = InstrumentationRegistry.getArguments().getString("recordDiscoveryGoldens") == "true"
        val output = File(instrumentation.targetContext.filesDir, "discovery-acceptance").apply { mkdirs() }
        for (w in listOf(320, 390, 411, 600, 840, 930, 931, 1279, 1280, 1600)) {
            compose.runOnIdle { width = w; height = if (w < 600) 844 else 800; font = 1f }
            for (name in listOf("channels", "about", "channel-search", "empty")) {
                compose.runOnIdle { scene = name }
                if (name in listOf("channel-search", "empty")) compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-form"))
                if (name == "empty") compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-empty"))
                capture("$name-$w", if (name == "channels") "search-results" else "channel-page", output, record, w in listOf(390, 1280))
            }
        }
        for ((w, h) in listOf(240 to 320, 320 to 240, 390 to 400, 840 to 390)) {
            compose.runOnIdle { width = w; height = h; font = 2f }
            for (name in listOf("channels", "about", "channel-search", "empty")) {
                compose.runOnIdle { scene = name }
                if (name == "about") compose.onNodeWithContentDescription("Close channel details").assertIsDisplayed()
                if (name in listOf("channel-search", "empty")) {
                    compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-form"))
                    compose.onNodeWithContentDescription("Submit channel search").assertIsDisplayed()
                }
                if (name == "channels") {
                    compose.onNodeWithTag("search-channels-list").performScrollToNode(hasTestTag("search-channel-subscribe-blender"))
                    compose.onNodeWithTag("search-channel-subscribe-blender").assertIsDisplayed()
                }
                if (name == "channel-search") {
                    compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-video-bunny"))
                    compose.onNodeWithTag("channel-search-thumb-bunny").assertIsDisplayed()
                }
                if (name == "empty") {
                    compose.onNodeWithTag("channel-search-list").performScrollToNode(hasTestTag("channel-search-empty"))
                    compose.onNodeWithTag("channel-search-empty").assertIsDisplayed()
                }
                capture("$name-$w-$h-font2", if (name == "channels") "search-results" else "channel-page", output, record, false)
            }
        }
    }

    private fun capture(name: String, tag: String, output: File, record: Boolean, compare: Boolean) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!record && compare) {
            val expected = InstrumentationRegistry.getInstrumentation().context.assets.open("channel/discovery/$name.png").use(BitmapFactory::decodeStream)
            assertEquals(name, expected.width, actual.width); assertEquals(name, expected.height, actual.height)
            val a = IntArray(actual.width * actual.height); val b = IntArray(a.size)
            actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
            expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
            val changed = a.indices.count { i -> listOf(0, 8, 16).any { s -> abs((a[i] shr s and 255) - (b[i] shr s and 255)) > 2 } }
            assertTrue("$name: $changed changed pixels", changed.toFloat() / a.size <= .001f)
            expected.recycle()
        }
        actual.recycle()
    }
}
