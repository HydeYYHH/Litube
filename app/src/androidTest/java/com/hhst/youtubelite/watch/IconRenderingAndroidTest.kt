package com.hhst.youtubelite.watch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
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
