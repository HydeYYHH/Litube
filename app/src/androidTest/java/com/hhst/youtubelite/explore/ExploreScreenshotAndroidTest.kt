package com.hhst.youtubelite.explore

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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.explore.data.FixtureExploreRepository
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExploreContent
import com.hhst.youtubelite.explore.domain.ExplorePage
import com.hhst.youtubelite.explore.domain.ExploreUiState
import com.hhst.youtubelite.explore.presentation.ExploreScreen
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** API 35/SwiftShader; golden matrix at density 1, plus landscape and 2x font. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ExploreScreenshotAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun categoriesMatchGoldensAcrossTheMatrix() {
        var width by mutableIntStateOf(390)
        var height by mutableIntStateOf(844)
        var scale by mutableFloatStateOf(1f)
        var category by mutableStateOf(ExploreCategory.Music)
        fun state(c: ExploreCategory) = ExploreUiState(category = c,
            content = ExploreContent.Ready(ExplorePage(c, FixtureExploreRepository.sample(c))))
        var ui by mutableStateOf(state(category))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { ExploreScreen(ui, {}, {}) }
            }
        }
        for ((w, h) in listOf(320 to 844, 390 to 844, 411 to 844, 600 to 844, 840 to 800, 1280 to 800, 1600 to 800)) {
            compose.runOnIdle { width = w; height = h; ui = state(ExploreCategory.Music) }
            capture("explore-music-$w", output, record, goldens)
        }
        compose.runOnIdle { width = 1280; height = 800; ui = state(ExploreCategory.Music) }
        val first = compose.onNodeWithTag("explore-item-music-bunny").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("explore-item-music-dream").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("explore-item-music-sintel").fetchSemanticsNode().boundsInRoot
        val cell = (1280f - 32f - 32f) / 3f
        assertEquals(16f, first.left, 0.5f)
        assertEquals(cell, first.width, 1f)
        assertEquals(first.left + first.width + 16f, second.left, 1f)
        assertEquals(second.left + second.width + 16f, third.left, 1f)
        assertEquals(first.top, second.top, 0.5f)
        compose.runOnIdle { width = 390; height = 844; ui = state(ExploreCategory.Music) }
        val phone = compose.onNodeWithTag("explore-item-music-bunny").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, phone.left, 0.5f)
        assertEquals(390f, phone.width, 0.5f)
        for (c in listOf(ExploreCategory.Gaming, ExploreCategory.News, ExploreCategory.Sports)) {
            compose.runOnIdle { width = 390; height = 844; ui = state(c) }
            capture("explore-${c.name.lowercase()}-390", output, record, goldens)
        }
        compose.runOnIdle { width = 844; height = 390; ui = state(ExploreCategory.Music) }
        capture("explore-music-390-landscape", output, record, goldens)
        compose.runOnIdle { width = 390; height = 844; scale = 2f; ui = state(ExploreCategory.Music) }
        capture("explore-music-390-font2", output, record, goldens)
    }

    private val output: File
        get() = File(compose.activity.getExternalFilesDir(null), "explore-acceptance").apply { mkdirs() }
    private val goldens: Set<String>
        get() = setOf(320, 390, 411, 600, 840, 1280, 1600).map { "explore-music-$it" }.toSet() +
            setOf("explore-gaming-390", "explore-news-390", "explore-sports-390",
                "explore-music-390-landscape", "explore-music-390-font2")
    private val record: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordExploreGoldens") == "true"

    private fun capture(name: String, output: File, record: Boolean, goldens: Set<String>) {
        compose.waitForIdle()
        val actual = compose.onNodeWithTag("explore-screen").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (record || name !in goldens) return
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("explore/goldens/$name.png").use(BitmapFactory::decodeStream)
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
