package com.hhst.youtubelite.watch.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class WatchPageMotionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun labelsWaitForThePictureAndNeverClipIntoAnExpandingPage() {
        var progress by mutableFloatStateOf(0f)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(200.dp, 300.dp).background(Color.Green).testTag("browse-backdrop")) {
                Box(Modifier.fillMaxSize().watchPageContent { progress }.background(Color.Blue))
                // The picture is independently composited and stays opaque.
                Box(Modifier.offset(60.dp, 100.dp).size(80.dp, 60.dp).background(Color.Red))
            }
        }

        fun assertPixel(xDp: Int, yDp: Int, color: Color) {
            // Capture the parent: capturing the clipped page alone loses the backdrop.
            val pixels = compose.onNodeWithTag("browse-backdrop").captureToImage().toPixelMap()
            assertEquals("($xDp, $yDp) at progress $progress", color,
                pixels[(xDp * density).roundToInt(), (yDp * density).roundToInt()])
        }

        assertPixel(100, 140, Color.Red)
        assertPixel(10, 10, Color.Green)
        compose.runOnIdle { progress = 0.5f }
        assertPixel(100, 140, Color.Red)
        assertPixel(40, 140, Color.Green)
        assertPixel(30, 50, Color.Green)
        assertPixel(10, 150, Color.Green)
        assertPixel(100, 30, Color.Green)
        assertPixel(190, 150, Color.Green)
        assertPixel(100, 280, Color.Green)
        compose.runOnIdle { progress = 1f }
        assertPixel(1, 1, Color.Blue)
        assertPixel(199, 299, Color.Blue)
    }

    @Test fun motionNeverChangesThePlayerSlotLayout() {
        var progress by mutableFloatStateOf(0f)
        var slot: Rect? = null
        var page: Rect? = null
        compose.setContent {
            val density = LocalDensity.current.density
            Box(Modifier.size(200.dp, 300.dp).testTag("geometry-probe")) {
                Box(Modifier.fillMaxSize()
                    .watchPageContent { progress }
                    .onGloballyPositioned { page = it.boundsInRoot() }) {
                    Box(Modifier.offset(12.dp, 24.dp).size(176.dp, 99.dp)
                        .onGloballyPositioned { slot = it.boundsInRoot() })
                }
            }
        }
        compose.waitForIdle()
        val initialSlot = slot
        val initialPage = page
        assertNotNull(initialSlot)
        assertNotNull(initialPage)
        for (fraction in listOf(0.25f, 0.5f, 1f)) {
            compose.runOnIdle { progress = fraction }
            compose.onNodeWithTag("geometry-probe").captureToImage()
            compose.runOnIdle {
                assertEquals("Player layout at $fraction", initialSlot, slot)
                assertEquals("Page layout at $fraction", initialPage, page)
            }
        }
    }

    @Test fun reversingBeforeLandingNeverFlashesLabelsOrBrightensTheBackdrop() {
        compose.setContent {
            Box(Modifier.size(100.dp).background(Color.Green).testTag("source-free-entry")) {
                Box(Modifier.fillMaxSize().watchPageContent { watchPageProgress(0.65f, 0.2f) }.background(Color.Blue))
            }
        }
        val pixels = compose.onNodeWithTag("source-free-entry").captureToImage().toPixelMap()
        assertEquals(Color.Green, pixels[pixels.width / 2, pixels.height / 2])
        var previous = 0.95f
        for (collapse in listOf(0f, 0.1f, 0.25f, 0.5f, 1f)) {
            val progress = watchPageProgress(0.95f, collapse)
            org.junit.Assert.assertTrue(progress <= previous)
            previous = progress
        }
    }
}
