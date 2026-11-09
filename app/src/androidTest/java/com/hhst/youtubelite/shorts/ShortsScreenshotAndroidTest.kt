package com.hhst.youtubelite.shorts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.domain.ShortsPage
import com.hhst.youtubelite.shorts.domain.ShortsUiState
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; golden matrix at density 1, plus landscape and 2x font. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ShortsScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pagerMatchesGoldensAcrossTheMatrix() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var scale by mutableFloatStateOf(1f)
        val page = ShortsPage(FixtureShortsRepository.sample())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing).requiredSize(width.dp, height.dp)) {
                    ShortsScreen(ShortsUiState(content = ShortsContent.Ready(page)), {})
                }
            }
        }
        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 844, 840 to 800, 1280 to 800, 1600 to 800)) {
            compose.runOnIdle { width = w; height = h }
            capture("shorts-$w", output, record, goldens)
        }
        compose.runOnIdle { width = 390; height = 844 }
        fun bounds(description: String) =
            compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        val more = bounds("More actions for A whole world in a single frame")
        assertEquals(342f, more.left, 0.5f)
        assertEquals(8f, more.top, 0.5f)
        assertEquals(40f, more.width, 0.5f)
        assertEquals(40f, more.height, 0.5f)
        val like = bounds("Like A whole world in a single frame")
        val comments = bounds("Comments for A whole world in a single frame")
        val share = bounds("Share A whole world in a single frame")
        for ((b, top) in listOf(like to 576f, comments to 644f, share to 712f)) {
            assertEquals(338f, b.left, 0.5f)
            assertEquals(top, b.top, 0.5f)
            assertEquals(40f, b.height, 0.5f)
        }
        compose.onNodeWithText("Subscribe").assertIsDisplayed()
        val channel = compose.onNodeWithTag("short-channel-short-1").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, channel.left, 0.5f)
        // The shared SubscribeButton's 48dp touch target makes the bottom-anchored
        // channel row 8dp taller than the old 40dp pill row; the title keeps its 808f top.
        assertEquals(752f, channel.top, 0.5f)
        val title = compose.onNodeWithTag("short-title-short-1").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, title.left, 0.5f)
        assertEquals(808f, title.top, 0.5f)
        // The fabricated "author · Original" sound pill is gone; nothing may resurrect it as attribution without a real source entry point.
        compose.onNodeWithTag("short-sound-short-1", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { width = 844; height = 390 }
        capture("shorts-390-landscape", output, record, goldens)
        compose.runOnIdle { width = 390; height = 844; scale = 2f }
        for (label in listOf(
            "rail-label-Like A whole world in a single frame",
            "rail-label-Comments for A whole world in a single frame",
            "rail-label-Share A whole world in a single frame",
        )) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(label, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertFalse("$label ellipsized at 2x", layouts.first().isLineEllipsized(0))
        }
        capture("shorts-390-font2", output, record, goldens)
    }

    @Test fun serverContentKeepsItsTopRightMenu() {
        val actions = mutableListOf<com.hhst.youtubelite.shorts.domain.ShortsAction>()
        val item = FixtureShortsRepository.sample().first()
        compose.setContent {
            ShortsScreen(ShortsUiState(content = ShortsContent.Ready(ShortsPage(listOf(item))), live = true), { actions += it })
        }
        compose.onNodeWithTag("short-more-${item.id}").assertIsDisplayed().performClick()
        assertEquals(com.hhst.youtubelite.shorts.domain.ShortsAction.OpenMenu(item.id), actions.last())
    }

    private val output: File
        get() = File(compose.activity.getExternalFilesDir(null), "shorts-acceptance").apply { mkdirs() }
    private val goldens: Set<String>
        get() = setOf(320, 390, 411, 600, 840, 1280, 1600).map { "shorts-$it" }.toSet() +
            setOf("shorts-390-landscape", "shorts-390-font2")
    private val record: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordShortsGoldens") == "true"

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("shorts-screen").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("shorts/goldens/$name.png").use(BitmapFactory::decodeStream)
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(expectedPixels.size)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.filter { i -> listOf(0, 8, 16).any { shift ->
            abs((expectedPixels[i] shr shift and 255) - (actualPixels[i] shr shift and 255)) > 2
        } }
        // Report the diff region so an intentional geometry change is distinguishable from unrelated drift.
        val changedArea = changed.fold(null as IntArray?) { area, i ->
            val x = i % expected.width; val y = i / expected.width
            if (area == null) intArrayOf(x, y, x, y)
            else intArrayOf(minOf(area[0], x), minOf(area[1], y), maxOf(area[2], x), maxOf(area[3], y))
        }
        assertTrue("$name changed ${changed.size} pixels in ${changedArea?.contentToString()}; inspect the saved screenshot",
            changed.size.toFloat() / expectedPixels.size <= 0.001f)
        expected.recycle()
    }
}
