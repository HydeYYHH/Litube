package com.hhst.youtubelite.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.data.HomeScenario
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Run on API 35 at >=1600x1200, density 160. Explicit density makes goldens reproducible. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class HomeScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstVisitMatchesLayoutReference() {
        // Match the captured mobile viewport, including its 24 CSS px bottom safe area.
        val dpr = 3f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(dpr, 1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp).background(Color.White).testTag("reference-viewport")) {
                    HomeScreen(HomeUiState(), {}, Modifier.padding(bottom = 24.dp))
                }
            }
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        compose.onNodeWithTag("reference-viewport").captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "first-visit-390-final.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val edgeLevels = buildSet {
                for (y in 36 until 108) for (x in 1062 until 1134) {
                    val gray = android.graphics.Color.red(bitmap.getPixel(x, y))
                    if (gray in 16..254) add(gray)
                }
            }
            assertTrue("Icon edges must be anti-aliased", edgeLevels.size > 32)
        }
        val bounds = compose.onNodeWithTag("empty-message").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, bounds.left / dpr, 1f)
        assertEquals(267f, bounds.top / dpr, 1f)
        assertEquals(358f, bounds.width / dpr, 1f)
        assertEquals(120f, bounds.height / dpr, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Try searching to get started").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val title = layouts.single()
        assertEquals(24.sp, title.layoutInput.style.fontSize)
        assertEquals(32.sp, title.layoutInput.style.lineHeight)
        assertEquals(FontWeight.Black, title.layoutInput.style.fontWeight)
        assertEquals(298.583f, (title.getLineRight(0) - title.getLineLeft(0)) / dpr, 1.5f)
    }

    @Test fun viewportAndStateMatrixMatchesGoldens() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var fontScale by mutableFloatStateOf(1f)
        var state by mutableStateOf(HomeUiState())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { HomeScreen(state, {}) }
            }
        }
        val sizes = listOf(320 to 844, 390 to 844, 411 to 844, 600 to 960, 840 to 1000, 1280 to 800, 1600 to 900)
        val record = InstrumentationRegistry.getArguments().getString("recordHomeGoldens") == "true"
        val goldens = setOf("empty-390", "feed-390", "loading-390", "error-390", "search-390", "menu-390", "empty-1280", "feed-1280")
        val output = File(compose.activity.getExternalFilesDir(null), "home-acceptance").apply { mkdirs() }
        for ((w, h) in sizes) {
            for (scene in HomeScenario.entries) {
                compose.runOnIdle {
                    width = w; height = h; fontScale = 1f
                    state = HomeUiState(content = scene.initialContent(), signedInPreview = scene != HomeScenario.Empty)
                }
                capture("${scene.argument}-$w", output, record, goldens)
                if (scene == HomeScenario.Empty && w == 390) {
                    val bounds = compose.onNodeWithTag("empty-message").fetchSemanticsNode().boundsInRoot
                    assertEquals(16f, bounds.left, 1f)
                    assertEquals(267f, bounds.top, 1f)
                    assertEquals(358f, bounds.width, 1f)
                    assertEquals(120f, bounds.height, 1f)
                }
            }
        }
        compose.runOnIdle { width = 390; height = 844; state = HomeUiState(query = "nature", overlay = HomeOverlay.Search) }
        compose.onNodeWithTag("search-input").performImeAction()
        capture("search-390", output, record, goldens)
        compose.runOnIdle { state = HomeUiState(content = HomeScenario.Feed.initialContent(), signedInPreview = true, overlay = HomeOverlay.Menu("bunny")) }
        capture("menu-390", output, record, goldens)
        compose.runOnIdle { width = 320; state = HomeUiState(); fontScale = 2f }
        capture("empty-320-font2", output, record, emptySet())
        val message = compose.onNodeWithTag("empty-message").fetchSemanticsNode().boundsInRoot
        assertTrue(message.width <= 320f)
        compose.onNodeWithText("Try searching to get started").assertIsDisplayed()
    }

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("home-screen").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("home/goldens/$name.png").use(BitmapFactory::decodeStream)
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
