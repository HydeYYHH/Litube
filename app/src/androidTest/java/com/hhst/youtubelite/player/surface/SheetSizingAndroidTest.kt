package com.hhst.youtubelite.player.surface

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.ui.components.LocalSheetPlayerBottomFraction
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class SheetSizingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun watchSheetReachesThePlayerEvenWithVeryLittleContent() {
        lateinit var sheetView: android.view.View
        val placedTop = java.util.concurrent.atomic.AtomicReference<Float>(Float.NaN)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { AppTheme {
            CompositionLocalProvider(LocalSheetPlayerBottomFraction provides .35f) {
                PlayerModalSheet(onDismiss = {}) {
                    val view = LocalView.current
                    SideEffect { sheetView = view }
                    Text("Queue content", androidx.compose.ui.Modifier.onGloballyPositioned {
                        placedTop.set(it.parentLayoutCoordinates!!.boundsInWindow().top)
                    })
                }
            }
        } }
        val height = compose.activity.window.decorView.height
        try {
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                abs(placedTop.get() - height * .35f) < 4f
            }
        } finally { com.hhst.youtubelite.core.DeviceEvidence.captureScene("watch-sheet-alignment") }
        val bounds = compose.onNodeWithTag("player-modal-sheet").fetchSemanticsNode().boundsInWindow
        val screen = IntArray(2)
        val window = IntArray(2)
        compose.runOnUiThread {
            sheetView.getLocationOnScreen(screen)
            sheetView.getLocationInWindow(window)
        }
        val top = placedTop.get() + screen[1] - window[1]
        assertTrue("Top must meet the player bottom ($bounds, top=$top, window=$height)", abs(top - height * .35f) < 4)
    }

    @Test fun libraryQueueFillsTheWindowWithVeryLittleContent() {
        val placedTop = java.util.concurrent.atomic.AtomicReference<Float>(Float.NaN)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { AppTheme {
            PlayerModalSheet(onDismiss = {}, expanded = true) {
                Text("Queue content", androidx.compose.ui.Modifier.onGloballyPositioned {
                    placedTop.set(it.parentLayoutCoordinates!!.boundsInWindow().top)
                })
            }
        } }
        val height = compose.activity.window.decorView.height
        compose.waitUntil(5_000) {
            val bounds = compose.onNodeWithTag("player-modal-sheet").fetchSemanticsNode().boundsInWindow
            abs(placedTop.get() + bounds.height - height) < 4f
        }
        val bounds = compose.onNodeWithTag("player-modal-sheet").fetchSemanticsNode().boundsInWindow
        assertTrue("Library queue must fill nearly all the window", bounds.height > height * .85f)
        assertTrue("Library queue must stay anchored to the bottom", abs(placedTop.get() + bounds.height - height) < 4f)
    }
}
