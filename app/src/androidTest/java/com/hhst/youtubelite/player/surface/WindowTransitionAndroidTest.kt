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
import com.tencent.mmkv.MMKV
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WindowTransitionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun inactivePipBoundsDoNotOverwriteMiniPlacement() {
        val store = MiniPlayerStore(MMKV.mmkvWithID("transition-placement")!!.apply { clearAll() })
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
        val store = MiniPlayerStore(MMKV.mmkvWithID("transition-fullscreen")!!.apply { clearAll() })
        var fullscreen by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var density = 1f
        var surfaces = 0
        var handle: FullscreenSwipeHandle? = null
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 0, store, false, {}, mini = false,
                fillsWindow = fullscreen, embeddedTopDp = 24, embeddedHeightDp = 200,
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
        assertTrue("Player must grow before release", preview.height > embedded.height + 10*density)
        assertTrue(preview.height < 600*density)
        assertFalse(fullscreen)
        root.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(fullscreen)
        assertEquals(600*density, bounds().height, 2f)
        val full = bounds()
        root.performTouchInput { down(full.center); moveBy(Offset(0f, 80*density)) }
        compose.waitForIdle()
        assertTrue(bounds().height < full.height)
        root.performTouchInput { up() }
        compose.waitForIdle()
        assertFalse(fullscreen)
        assertEquals(embedded.height, bounds().height, 2f)
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
