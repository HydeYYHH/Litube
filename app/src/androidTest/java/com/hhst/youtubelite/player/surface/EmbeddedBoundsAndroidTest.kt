package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Embedded player geometry follows the reported page rect without recreating the video host. */
class EmbeddedBoundsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<PlayerTestActivity>()

    @Test fun embeddedBoundsFollowWidthAndOffsetWithoutReplacingContent() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), "embedded-bounds").apply { clearAll() })
        var state by mutableStateOf(PlayerUiState(pageLeftDp = 60, pageTopDp = 48,
            pageWidthDp = 240, pageHeightDp = 135, pageViewportWidthDp = 400))
        var fullscreen by mutableStateOf(false)
        var creations = 0
        var density = 1f
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            val bounds = PlayerUi.embeddedBounds(state, 400)
            MiniPlayerWindow(400, 0, store, false, {}, mini = false, fillsWindow = fullscreen,
                embeddedLeftDp = bounds.left, embeddedWidthDp = bounds.width,
                embeddedTopDp = bounds.top ?: 0, embeddedHeightDp = bounds.height,
                modifier = Modifier.size(400.dp, 500.dp).testTag("root")) {
                remember { creations++; Any() }
                Box(Modifier.fillMaxSize().testTag("viewport"))
            }
        } }
        fun check(left: Int, width: Int) {
            compose.waitForIdle()
            val rect = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            // The compose root cannot exceed the physical window: a 400dp window on a 440dpi screen is capped at the device width.
            val cap = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot.width
            assertEquals(left * density, rect.left, 1.5f)
            assertEquals(minOf(width * density, cap), rect.width, 1.5f)
        }
        check(60, 240)
        compose.runOnIdle { state = state.copy(pageLeftDp = 20, pageWidthDp = 300) }
        check(20, 300)
        compose.runOnIdle { fullscreen = true }
        check(0, 400)
        compose.runOnIdle { fullscreen = false; state = state.copy(pageLeftDp = 100, pageWidthDp = 500, pageViewportWidthDp = 1000) }
        check(40, 200)
        compose.runOnIdle { assertEquals("Geometry changes must preserve the video host", 1, creations) }
        DeviceEvidence.captureScene("embedded-player-width")
    }
}
