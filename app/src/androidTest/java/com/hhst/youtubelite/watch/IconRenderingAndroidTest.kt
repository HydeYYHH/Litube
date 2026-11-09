package com.hhst.youtubelite.watch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hhst.youtubelite.ui.design.AppIcon
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IconRenderingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playbackAndMenuGlyphsHaveHollowInteriorsInBothRenderers() {
        val glyphs = listOf(
            Triple(AppIcon.Play, com.hhst.youtubelite.R.drawable.ic_play, 10 to 12),
            Triple(AppIcon.Pause, com.hhst.youtubelite.R.drawable.ic_pause, 8 to 12),
            Triple(AppIcon.Delete, com.hhst.youtubelite.R.drawable.ic_delete, 12 to 12),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                Row(Modifier.background(Color.White)) {
                    glyphs.forEach { (icon, resource, _) ->
                        AppIcon(icon, Modifier.testTag("app-${icon.name}"), color = Color.Black)
                        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(resource), null,
                            Modifier.size(24.dp).testTag("xml-${icon.name}"), tint = Color.Black)
                    }
                }
            }
        }
        glyphs.forEach { (icon, _, center) ->
            for (prefix in listOf("app", "xml")) {
                val pixels = compose.onNodeWithTag("$prefix-${icon.name}").captureToImage().toPixelMap()
                assertTrue("$prefix ${icon.name} interior", pixels[center.first * 2, center.second * 2].red > .9f)
                val dark = (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y -> pixels[x, y].red < .5f } }
                assertTrue("$prefix ${icon.name} outline must remain visible", dark > 100)
            }
        }
    }

    @Test fun playlistBadgeRetainsAllThreeLinesAtItsSourceSize() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Row(Modifier.background(Color.White)) {
                    AppIcon(AppIcon.Playlist, Modifier.size(12.dp).testTag("playlist-badge"))
                    AppIcon(AppIcon.Playlist, Modifier.size(24.dp).testTag("playlist-large"))
                }
            }
        }
        val small = compose.onNodeWithTag("playlist-badge").captureToImage().toPixelMap()
        val large = compose.onNodeWithTag("playlist-large").captureToImage().toPixelMap()
        listOf(3, 6, 9).forEach { y ->
            assertTrue("12-unit line at $y must remain visible", small[5, y].red < 0.8f)
        }
        listOf(6, 12, 18).forEach { y ->
            assertTrue("Scaled line at $y must remain visible", large[10, y].red < 0.5f)
        }
    }

    @Test fun externalWindowAndChevronRetainTheirCompletePaths() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Row(Modifier.background(Color.White)) {
                    AppIcon(AppIcon.OpenWith, Modifier.testTag("external"))
                    AppIcon(AppIcon.ChevronDown, Modifier.testTag("chevron"))
                }
            }
        }
        val external = compose.onNodeWithTag("external").captureToImage().toPixelMap()
        assertTrue("Left frame", external[3, 12].red < 0.2f)
        assertTrue("Bottom frame", external[12, 20].red < 0.2f)
        assertTrue("Arrow head", external[20, 5].red < 0.2f)
        val chevron = compose.onNodeWithTag("chevron").captureToImage().toPixelMap()
        assertTrue("Left arm", chevron[7, 11].red < 0.6f)
        assertTrue("Right arm", chevron[16, 11].red < 0.6f)
    }

    @Test fun negativeViewBoxOriginsRemainInsideTheIcon() {
        val icons = listOf(AppIcon.Download, AppIcon.QueueAdd, AppIcon.OpenWith)
        compose.setContent {
            Row(Modifier.background(Color.White)) {
                icons.forEach { icon -> AppIcon(icon, Modifier.testTag(icon.name)) }
            }
        }
        icons.forEach { icon ->
            val pixels = compose.onNodeWithTag(icon.name).captureToImage().toPixelMap()
            val dark = (0 until pixels.width).sumOf { x ->
                (0 until pixels.height).count { y -> pixels[x, y].red < 0.5f }
            }
            assertTrue("${icon.name} must contain a visible glyph", dark > 20)
            assertTrue("${icon.name} must retain its outline", dark < pixels.width * pixels.height / 2)
        }
    }
}
