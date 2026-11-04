@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.surface

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoReplacementFrameAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun replacingAVideoCoversTheOldBufferWithoutRecreatingTheSurface() {
        val state = mutableStateOf(PlayerUiState(videoId = "first", visible = true,
            hasRenderedVideoFrame = true, firstFrameRendered = true, controlsVisible = false))
        val oldFrame = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        var surface: SurfaceView? = null
        var attaches = 0
        compose.setContent { AppTheme {
            Box(Modifier.size(320.dp, 180.dp).testTag("picture")) {
                PlayerSurface(state.value, remember { mutableStateOf(0L) }, remember { mutableStateOf(0L) },
                    remember { mutableStateOf(null) }, remember { mutableStateOf(null) },
                    remember { mutableStateOf(emptyList()) }, proxyActions("replacement"),
                    { attaches++; surface = it }, {}, managedByHost = true,
                    transitionFrame = oldFrame.takeIf { state.value.videoId == "first" })
            }
        } }
        val originalSurface = compose.runOnIdle { surface }
        fun centerPixel(): Color = compose.onNodeWithTag("picture").captureToImage().toPixelMap().let {
            it[it.width / 2, it.height / 2]
        }
        assertEquals(Color.Red, centerPixel())
        compose.runOnIdle {
            state.value = state.value.copy(videoId = "second", hasRenderedVideoFrame = false, firstFrameRendered = false)
        }
        compose.onNodeWithTag("player-frame-shutter").assertIsDisplayed()
        assertEquals("The previous video's image must be opaque-covered", Color.Black, centerPixel())
        compose.runOnIdle { state.value = state.value.copy(hasRenderedVideoFrame = true, firstFrameRendered = true) }
        compose.onNodeWithTag("player-frame-shutter").assertDoesNotExist()
        // Reattaching the same video's output keeps its valid handoff image.
        compose.runOnIdle { state.value = state.value.copy(firstFrameRendered = false) }
        compose.onNodeWithTag("player-frame-shutter").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, attaches); assertSame(originalSurface, surface) }
    }
}
