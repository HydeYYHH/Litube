package com.hhst.youtubelite.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; density is pinned to 1 so captures are stable CSS-px sized. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ChannelScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun ready() = ChannelContent.Ready(FixtureChannelRepository.samplePage())

    private fun contentOf(scene: String) = when (scene) {
        "profile", "videos" -> ready()
        "empty" -> ChannelContent.Ready(ChannelPage(FixtureChannelRepository.profile().copy(videoCount = "0 videos"), emptyList()))
        "loading" -> ChannelContent.Loading
        else -> ChannelContent.Failed
    }

    private fun stateOf(scene: String, tab: ChannelTab = ChannelTab.Home) = ChannelUiState(
        channelId = "blender", content = contentOf(scene), selectedTab = tab,
    )

    private fun setContent(width: androidx.compose.runtime.MutableIntState, height: androidx.compose.runtime.MutableIntState, state: androidx.compose.runtime.MutableState<ChannelUiState>) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(width.intValue.dp, height.intValue.dp)) {
                    ChannelScreen(state.value, {}, {})
                }
            }
        }
    }

    @Test fun verifiedStatesMatchGoldens() {
        var width = mutableIntStateOf(390)
        var height = mutableIntStateOf(844)
        var state = mutableStateOf(stateOf("profile"))
        setContent(width, height, state)
        val record = InstrumentationRegistry.getArguments().getString("recordChannelGoldens") == "true"
        val scenes = listOf("profile", "videos", "empty", "loading", "error")
        val names = listOf(390, 1280).flatMap { w -> scenes.map { "$it-$w" } }.toSet()
        val output = File(compose.activity.getExternalFilesDir(null), "channel-acceptance").apply { mkdirs() }
        for ((w, h) in listOf(390 to 844, 1280 to 800)) {
            for (scene in scenes) {
                compose.runOnIdle { width.intValue = w; height.intValue = h; state.value = stateOf(scene, tab = if (scene == "videos") ChannelTab.Videos else ChannelTab.Home) }
                capture("$scene-$w", output, record, names)
            }
        }
    }

    @Test fun everyViewportKeepsTheHeaderTabsAndContentAnchored() {
        var width = mutableIntStateOf(390)
        var height = mutableIntStateOf(844)
        var state = mutableStateOf(stateOf("profile"))
        setContent(width, height, state)
        val output = File(compose.activity.getExternalFilesDir(null), "channel-acceptance").apply { mkdirs() }
        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 1280 to 800, 1600 to 900)) {
            for (scene in listOf("profile", "videos", "empty", "loading", "error")) {
                compose.runOnIdle { width.intValue = w; height.intValue = h; state.value = stateOf(scene, tab = if (scene == "videos") ChannelTab.Videos else ChannelTab.Home) }
                capture("$scene-$w", output, true, emptySet())
            }
        }
        compose.runOnIdle { width.intValue = 840; height.intValue = 390; state.value = stateOf("videos", tab = ChannelTab.Videos) }
        capture("videos-840-landscape", output, true, emptySet())
        compose.runOnIdle { width.intValue = 390; height.intValue = 400; state.value = stateOf("profile") }
        compose.onNodeWithTag("channel-page").assertIsDisplayed()
        capture("profile-390-split", output, true, emptySet())
    }

    @Test fun breakpointEdgesFlipAvatarSubscribeAndGridColumns() {
        var width = mutableIntStateOf(390)
        var height = mutableIntStateOf(844)
        var state = mutableStateOf(stateOf("videos", tab = ChannelTab.Videos))
        setContent(width, height, state)
        val output = File(compose.activity.getExternalFilesDir(null), "channel-acceptance").apply { mkdirs() }
        for ((below, edge) in listOf(527 to 528, 599 to 600, 699 to 700, 1147 to 1148)) {
            for (w in listOf(below, edge)) {
                compose.runOnIdle { width.intValue = w; height.intValue = 900 }
                compose.waitForIdle()
                capture("videos-$w", output, true, emptySet())
                // Probe widths above the window size center the composition in the root,
                // so every check stays relative to the page origin.
                val pageLeft = boundsOf("channel-page").left
                val avatar = boundsOf("channel-avatar")
                assertEquals("avatar size at $w", if (w < 600) 72f else if (w < 700) 120f else 160f, avatar.width, 1f)
                val subscribe = boundsOf("channel-subscribe")
                if (w < 700) assertEquals("subscribe width at $w", w - 32f, subscribe.width, 1f)
                else assertTrue("compact subscribe at $w: ${subscribe.width}", subscribe.width < 200f)
            }
        }
        // The second video's thumb sits at the next cell's start, so its left edge
        // moves exactly with the flipped column count.
        for (w in listOf(1147, 1148)) {
            compose.runOnIdle { width.intValue = w }
            compose.waitForIdle()
            capture("videos-$w", output, true, emptySet())
            val pageLeft = boundsOf("channel-page").left
            val dream = boundsOf("channel-thumb-dream")
            assertEquals("grid column position at $w",
                ChannelLayout.PageInset + ChannelLayout.cardWidth(w.toFloat()) + ChannelLayout.PageInset,
                dream.left - pageLeft, 1f)
        }
    }

    @Test fun largeFontScaleKeepsHeaderAndFirstRowVisible() {
        // No density override: the device font scaling reaches the text directly.
        var state = mutableStateOf(stateOf("videos", tab = ChannelTab.Videos))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) { ChannelScreen(state.value, {}, {}) }
            }
        }
        awaitKeyAnchors()
        compose.onNodeWithTag("channel-tab-Videos").performClick() // already selected; underline stays
        awaitKeyAnchors()
    }

    private fun awaitKeyAnchors() {
        compose.waitForIdle()
        compose.onNodeWithTag("channel-name").assertIsDisplayed()
        compose.onNodeWithTag("channel-tab-Videos").assertIsDisplayed()
        compose.onNodeWithTag("channel-thumb-bunny").assertIsDisplayed()
    }


    @Test fun wideDetailsStayBesideTheAvatar() {
        val width = mutableIntStateOf(1280)
        val height = mutableIntStateOf(800)
        val state = mutableStateOf(stateOf("profile"))
        setContent(width, height, state)
        val avatar = boundsOf("channel-avatar")
        val description = boundsOf("channel-description")
        val subscribe = boundsOf("channel-subscribe")
        assertEquals(192f, description.left, 0.5f)
        assertTrue("Description must stay beside the avatar", description.top < avatar.bottom)
        assertEquals(429.17f, subscribe.top, 1.5f)
        val footer = boundsOf("channel-navigation")
        assertEquals(800f, footer.bottom, 0.5f)
        assertEquals(57f, footer.height, 0.5f)
    }

    @Test fun shortErrorWindowKeepsRetryReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(320.dp, 240.dp)) {
                    ChannelScreen(ChannelUiState(content = ChannelContent.Failed), {}, {})
                }
            }
        }
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
        val retry = compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot
        assertTrue(retry.top >= 48f)
        assertTrue(retry.bottom <= boundsOf("channel-navigation").top)
    }

    @Test fun longDescriptionsKeepTheirMoreAffordanceVisible() {
        val profile = FixtureChannelRepository.profile().copy(description = "A very long channel description. ".repeat(30), banner = null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(320.dp, 844.dp)) {
                    ChannelScreen(ChannelUiState(content = ChannelContent.Ready(ChannelPage(profile, emptyList()))), {}, {})
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasAnyAncestor(hasTestTag("channel-description")) and hasText("...more", substring = true), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.layoutInput.text.text.endsWith("...more"))
        assertFalse("More must not be ellipsized off-screen", layout.hasVisualOverflow)
        assertTrue(layout.lineCount <= 2)
    }

    private fun boundsOf(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val root = boundsOf("channel-page")
        assertEquals("Capture must start at the window origin", 0f, root.left, 0.5f)
        val actual = compose.onNodeWithTag("channel-page").captureToImage().asAndroidBitmap()
        assertEquals(root.width.toInt(), actual.width)
        assertEquals(root.height.toInt(), actual.height)
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("channel/goldens/$name.png").use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.count { i -> listOf(0, 8, 16).any { shift ->
            abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2
        } }
        assertTrue("$name changed $changed pixels; inspect the saved screenshot", changed.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
