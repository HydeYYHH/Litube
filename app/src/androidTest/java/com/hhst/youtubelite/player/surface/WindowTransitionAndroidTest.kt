package com.hhst.youtubelite.player.surface

import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hhst.youtubelite.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WindowTransitionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun miniSwipeFollowsBothAxesAndFinishesOnlyAfterSettling() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(compose.activity, "mini-swipe").apply { clearAll() })
        var mini by mutableStateOf(false)
        var handle: FullscreenSwipeHandle? = null
        var revealed = false
        var finished = 0
        var surfaces = 0
        var density = 1f
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 0, store, false, {},
                mini = mini, embeddedTopDp = 24, embeddedHeightDp = 200,
                onMiniSwipe = { mini = true }, onMiniDragChanged = { revealed = it },
                onMiniSwipeFinished = { finished++ }, modifier = Modifier.size(390.dp, 600.dp)) {
                if (!mini) handle = LocalFullscreenSwipeHandle.current
                Box(Modifier.fillMaxSize().testTag("window")) {
                    AndroidView(factory = { context -> surfaces++; SurfaceView(context) }, modifier = Modifier.fillMaxSize())
                }
            }
        } }
        compose.waitForIdle()
        val initial = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { handle!!.horizontal = 20 * density; handle!!.drag(100 * density) }
        compose.waitForIdle()
        val held = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.left + 20*density, held.left, 2f)
        assertEquals(initial.top + 100*density, held.top, 2f)
        assertTrue(revealed)
        assertEquals(0, finished)
        compose.runOnIdle { handle!!.end(false) }
        compose.waitForIdle()
        assertFalse(revealed)
        assertFalse(mini)
        compose.runOnIdle { handle!!.horizontal = 0f; handle!!.drag(200*density) }
        compose.waitForIdle()
        compose.runOnIdle { handle!!.release(200*density, 0f) }
        compose.waitForIdle()
        assertTrue(mini)
        assertEquals(1, finished)
        assertEquals(1, surfaces)
    }

    @Test fun inactivePipBoundsDoNotOverwriteMiniPlacement() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), "transition-placement").apply { clearAll() })
        store.save(220, -180f, -150f)
        var mini by mutableStateOf(true)
        var pip by mutableStateOf(false)
        compose.setContent { AppTheme {
            val width = LocalConfiguration.current.screenWidthDp
            MiniPlayerWindow(width, 0, store, false, {}, mini = mini, fillsWindow = pip,
                fullscreenSwipeEnabled = !pip, embeddedTopDp = 24, embeddedHeightDp = 200,
                modifier = Modifier.size(if (pip) 160.dp else width.dp, if (pip) 90.dp else 600.dp)) {
                Box(Modifier.fillMaxSize().testTag("window"))
            }
        } }
        compose.waitForIdle()
        val saved = store.load()
        val initial = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { mini = false; pip = true }
        compose.waitForIdle()
        assertEquals(saved, store.load())
        compose.runOnIdle { pip = false }
        compose.waitForIdle()
        assertEquals(saved, store.load())
        compose.runOnIdle { mini = true }
        compose.waitForIdle()
        val restored = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.left, restored.left, 1f)
        assertEquals(initial.top, restored.top, 1f)
        assertEquals(saved, store.load())
    }

    @Test fun fullscreenFollowsFingerAndCancelsWithoutReplacingSurface() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(), "transition-fullscreen").apply { clearAll() })
        var fullscreen by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var density = 1f
        var surfaces = 0
        var handle: FullscreenSwipeHandle? = null
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 0, store, false, {}, mini = false,
                fillsWindow = fullscreen, embeddedTopDp = 100, embeddedHeightDp = 200,
                modifier = Modifier.size(LocalConfiguration.current.screenWidthDp.dp, 600.dp).testTag("root")) {
                handle = LocalFullscreenSwipeHandle.current
                val swipe = handle
                val callbacks = remember(swipe) { object : GestureCallbacks {
                    override fun onFullscreenSwipeProgress(distancePx: Float) { swipe?.drag?.invoke(distancePx) }
                    override fun onFullscreenSwipeCancel() { swipe?.end?.invoke(false) }
                    override fun onGestureEnd() { swipe?.end?.invoke(false) }
                    override fun onFullscreenSwipe(up: Boolean) {
                        if (up != fullscreen) { swipe?.end?.invoke(true); fullscreen = up }
                    }
                } }
                Box(Modifier.fillMaxSize().testTag("window").playerGestures(callbacks, enabled = enabled)) {
                    AndroidView(factory = { context -> surfaces++; SurfaceView(context) }, modifier = Modifier.fillMaxSize())
                }
            }
        } }
        compose.waitForIdle()
        fun bounds() = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        val embedded = bounds()
        val root = compose.onNodeWithTag("root")
        root.performTouchInput { down(embedded.center); moveBy(Offset(0f, -50*density)) }
        compose.waitForIdle()
        val preview = bounds()
        assertEquals("Drag keeps the player size", embedded.height, preview.height, 2f)
        assertEquals(embedded.top, preview.top, 2f)
        assertTrue(requireNotNull(handle).pictureScale > 1f)
        assertFalse(fullscreen)
        root.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(fullscreen)
        assertEquals(600*density, bounds().height, 2f)
        val full = bounds()
        root.performTouchInput { down(full.center); moveBy(Offset(0f, 80*density)) }
        compose.waitForIdle()
        assertEquals(full.top + 80*density, bounds().top, 2f)
        root.performTouchInput { up() }
        compose.waitForIdle()
        assertFalse(fullscreen)
        assertEquals(embedded.height, bounds().height, 2f)
        assertEquals(1f, requireNotNull(handle).pictureScale, 0.001f)
        root.performTouchInput { down(bounds().center); moveBy(Offset(0f, -50*density)) }
        compose.waitForIdle()
        compose.runOnIdle { enabled = false }
        root.performTouchInput { cancel() }
        compose.waitForIdle()
        assertFalse(fullscreen)
        assertEquals(embedded.height, bounds().height, 2f)
        compose.runOnIdle { handle!!.drag(-80*density); handle!!.end(false) }
        compose.waitForIdle()
        assertEquals(embedded.height, bounds().height, 2f)
        assertEquals(1, surfaces)
    }
}
