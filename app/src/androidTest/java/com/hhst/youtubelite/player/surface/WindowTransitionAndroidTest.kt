package com.hhst.youtubelite.player.surface

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.ui.theme.AppTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WindowTransitionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun embeddedBoundsAreReportedAfterTheFirstLayout() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(compose.activity, "embedded-bounds").apply { clearAll() })
        var reported: androidx.compose.ui.geometry.Rect? = null
        var density = 1f
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(390, 0, store, false, {}, mini = false,
                embeddedTopDp = 24, embeddedHeightDp = 219,
                onBoundsChanged = { reported = it }, modifier = Modifier.size(390.dp, 600.dp)) {
                Box(Modifier.fillMaxSize())
            }
        } }
        compose.waitForIdle()
        assertNotNull("A static Watch player must publish its bounds before navigation", reported)
        assertEquals(390*density, reported!!.width, 2f)
        assertEquals(219*density, reported!!.height, 2f)
    }

    @Test fun navigationEntryDrawsBeforeSettlingAndKeepsOneSurface() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(compose.activity, "mini-back").apply { clearAll() })
        var ready = false
        var firstReadyWidth: Float? = null
        var currentWidth = 0f
        var surfaces = 0
        var density = 1f
        var videoSurface: SurfaceView? = null
        var parentHeight by mutableStateOf(600.dp)
        var hostReady by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(390, 0, store, false, {},
                animateFromBounds = androidx.compose.ui.geometry.Rect(0f, 24*density, 390*density, 243*density),
                entryAnimationReady = hostReady,
                onBoundsChanged = { currentWidth = it.width },
                onReadyForDisplay = { ready = true; if (firstReadyWidth == null) firstReadyWidth = currentWidth },
                modifier = Modifier.size(390.dp, parentHeight)) {
                AndroidView(factory = { context -> surfaces++; SurfaceView(context).also {
                    videoSurface = it; it.holder.setFixedSize(640, 360)
                } }, modifier = Modifier.fillMaxSize())
            }
        } }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertTrue("The destination must draw the collapse, not wait until it settles", ready)
            assertTrue("The first displayed frame must not flash at the dock before expanding", firstReadyWidth!! > store.load().widthDp * density)
            assertEquals(640, videoSurface!!.holder.surfaceFrame.width())
        }
        val waiting = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        assertEquals("Time spent waiting for the destination must not consume the animation", 390*density, waiting.width, 2f)
        compose.runOnIdle { hostReady = true }
        compose.mainClock.advanceTimeBy(128)
        val shrinking = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        assertTrue(shrinking.width < 390*density)
        assertTrue(shrinking.width > store.load().widthDp * density)
        compose.runOnIdle { parentHeight = 560.dp }
        compose.mainClock.advanceTimeBy(32)
        val resized = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        assertTrue("Settling destination insets must not skip the remaining collapse", resized.width > store.load().widthDp * density)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(1, surfaces)
            assertEquals(640, videoSurface!!.holder.surfaceFrame.width())
            assertEquals(360, videoSurface!!.holder.surfaceFrame.height())
        }
        val settled = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        assertEquals(store.load().widthDp * density, settled.width, 2f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun localBackWaitsForOriginAndDockDrawBeforeHandoff() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(compose.activity, "mini-back-draw").apply { clearAll() })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decor = compose.activity.window.decorView
        fun flushPendingLayout(view: View) {
            (view as? RootForTest)?.measureAndLayoutForTest()
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) flushPendingLayout(view.getChildAt(index))
            }
        }
        val blockDraw = AtomicBoolean(false)
        val blockedDraws = AtomicInteger()
        val bounds = AtomicReference(Rect.Zero)
        val drawnBounds = CopyOnWriteArrayList<Rect>()
        val settledBounds = AtomicReference<Rect?>()
        val settled = AtomicInteger()
        val miniProgress = AtomicReference(0f)
        var mini by mutableStateOf(false)
        var parentHeight by mutableStateOf(600.dp)
        var density = 1f
        var surfaces = 0
        var videoSurface: SurfaceView? = null
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(390, 0, store, false, {}, mini = mini,
                embeddedTopDp = 24, embeddedHeightDp = 219, embeddedWidthDp = 390,
                onBoundsChanged = { bounds.set(it) },
                onMiniDragProgress = { miniProgress.set(it) },
                onMiniSettled = { settledBounds.set(it); settled.incrementAndGet() },
                modifier = Modifier.size(390.dp, parentHeight)) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawnBounds.add(bounds.get())
                }) {
                    AndroidView(factory = { context -> surfaces++; SurfaceView(context).also {
                        videoSurface = it
                        it.holder.setFixedSize(640, 360)
                    } }, modifier = Modifier.fillMaxSize())
                }
            }
        } }
        compose.waitForIdle()
        val origin = bounds.get()
        val originalSurface = videoSurface
        var originDraws = 0
        val gate = ViewTreeObserver.OnPreDrawListener {
            if (blockDraw.get()) { blockedDraws.incrementAndGet(); false } else true
        }
        compose.mainClock.autoAdvance = false
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnPreDrawListener(gate) }
        try {
            instrumentation.runOnMainSync {
                blockDraw.set(true)
                originDraws = drawnBounds.size
                mini = true
                decor.postInvalidateOnAnimation()
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitUntil(5_000) { blockedDraws.get() > 0 }
            compose.mainClock.advanceTimeBy(600)
            instrumentation.runOnMainSync {
                flushPendingLayout(decor)
                assertEquals("A blocked origin draw must not consume the Back clock", origin.width, bounds.get().width, 2f)
                assertEquals(0f, miniProgress.get(), 0.001f)
                assertEquals(originDraws, drawnBounds.size)
                assertEquals(0, settled.get())
                blockDraw.set(false)
                decor.postInvalidateOnAnimation()
            }
            compose.waitUntil(5_000) { drawnBounds.size > originDraws }
            instrumentation.runOnMainSync {
                assertEquals("The first actual draw is still the origin", origin.width, drawnBounds.last().width, 2f)
                assertEquals(0, settled.get())
            }
            compose.mainClock.advanceTimeBy(64)
            val dockWidth = MiniPlayerLayout.computeSpec(390, 0, MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP).widthDp * density
            compose.waitUntil(5_000) {
                drawnBounds.any { it.width < origin.width - 4f && it.width > dockWidth + 4f }
            }
            val blockedBeforeDock = blockedDraws.get()
            var drawsBeforeDock = 0
            instrumentation.runOnMainSync {
                blockDraw.set(true)
                drawsBeforeDock = drawnBounds.size
                decor.postInvalidateOnAnimation()
            }
            compose.mainClock.advanceTimeBy(600)
            instrumentation.runOnMainSync { flushPendingLayout(decor) }
            compose.waitUntil(5_000) {
                blockedDraws.get() > blockedBeforeDock && miniProgress.get() == 1f &&
                    kotlin.math.abs(bounds.get().width - dockWidth) < 2f
            }
            val beforeRetarget = bounds.get()
            instrumentation.runOnMainSync {
                assertEquals("Finishing the clock cannot hand off an undrawn dock", 0, settled.get())
                // Retarget while waiting for the terminal draw, without another animation.
                parentHeight = 560.dp
            }
            compose.mainClock.advanceTimeBy(32)
            try {
                compose.waitUntil(5_000) {
                    // Android may defer Compose-only layout until dispatchDraw; flush that work while PreDraw still rejects actual drawing.
                    compose.mainClock.advanceTimeByFrame()
                    instrumentation.runOnMainSync { flushPendingLayout(decor) }
                    kotlin.math.abs(bounds.get().top - (beforeRetarget.top - 40 * density)) < 2f
                }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Dock must retarget by -40dp before drawing: before=$beforeRetarget, " +
                    "current=${bounds.get()}, density=$density, latestDraw=${drawnBounds.lastOrNull()}, " +
                    "progress=${miniProgress.get()}, settled=${settled.get()}, blocked=${blockedDraws.get()}", failure)
            }
            instrumentation.runOnMainSync {
                assertEquals(0, settled.get())
                assertEquals("Flushing layout must not draw the pending dock", drawsBeforeDock, drawnBounds.size)
                blockDraw.set(false)
                decor.postInvalidateOnAnimation()
            }
            compose.waitUntil(5_000) { settled.get() == 1 }
            instrumentation.runOnMainSync {
                assertEquals("Publish the dock that actually drew, including its current insets", drawnBounds.last(), settledBounds.get())
                assertEquals(dockWidth, settledBounds.get()!!.width, 2f)
                assertEquals(1, surfaces)
                assertSame(originalSurface, videoSurface)
                assertEquals(640, videoSurface!!.holder.surfaceFrame.width())
                assertEquals(360, videoSurface!!.holder.surfaceFrame.height())
            }
        } finally {
            instrumentation.runOnMainSync {
                blockDraw.set(false)
                decor.viewTreeObserver.removeOnPreDrawListener(gate)
                decor.postInvalidateOnAnimation()
            }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun miniCornersRevealTheSameBackdropDuringDragAndAfterSettle() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(compose.activity, "mini-corners").apply { clearAll() })
        var mini by mutableStateOf(false)
        var swipe: FullscreenSwipeHandle? = null
        var density = 1f
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            Box(Modifier.fillMaxSize().background(Color.Green)) {
                MiniPlayerWindow(390, 0, store, false, {}, mini = mini,
                    embeddedTopDp = 24, embeddedHeightDp = 219, embeddedWidthDp = 390,
                    onMiniSwipe = { mini = true }, modifier = Modifier.size(390.dp, 600.dp)) {
                    if (!mini) swipe = LocalFullscreenSwipeHandle.current
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                }
            }
        } }
        fun assertRounded() {
            val pixels = compose.onNodeWithTag("player-window").captureToImage().toPixelMap()
            assertEquals("Rounded corners must show the browse background, without a black square", Color.Green, pixels[1, 1])
            assertEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
        }
        compose.waitForIdle()
        compose.runOnIdle { swipe!!.drag(100 * density) }
        compose.waitForIdle()
        assertRounded()
        compose.runOnIdle { swipe!!.end(false) }
        compose.waitForIdle()
        assertFalse(mini)
        compose.runOnIdle { swipe!!.drag(200 * density); swipe!!.release(200 * density, 0f) }
        compose.waitForIdle()
        assertTrue(mini)
        assertRounded()
    }

    @Test fun miniSwipeFollowsBothAxesAndFinishesOnlyAfterSettling() {
        val store = MiniPlayerStore(com.hhst.youtubelite.core.PreferenceStore.of(compose.activity, "mini-swipe").apply { clearAll() })
        var mini by mutableStateOf(false)
        var handle: FullscreenSwipeHandle? = null
        var revealed = false
        var finished = 0
        var surfaces = 0
        var density = 1f
        var videoSurface: SurfaceView? = null
        var miniPreview = false
        compose.setContent { AppTheme {
            density = LocalDensity.current.density
            MiniPlayerWindow(LocalConfiguration.current.screenWidthDp, 0, store, false, {},
                mini = mini, embeddedTopDp = 24, embeddedHeightDp = 200, embeddedWidthDp = 390,
                onMiniSwipe = { mini = true }, onMiniDragChanged = { revealed = it },
                onMiniSwipeFinished = { finished++ }, modifier = Modifier.size(390.dp, 600.dp)) {
                if (!mini) handle = LocalFullscreenSwipeHandle.current
                miniPreview = LocalMiniTransitionActive.current
                Box(Modifier.fillMaxSize().testTag("window")) {
                    AndroidView(factory = { context -> surfaces++; SurfaceView(context).also {
                        videoSurface = it; it.holder.setFixedSize(640, 360)
                    } }, modifier = Modifier.fillMaxSize())
                }
            }
        } }
        compose.waitForIdle()
        val initial = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { handle!!.horizontal = 20 * density; handle!!.drag(100 * density) }
        compose.waitForIdle()
        val held = compose.onNodeWithTag("player-window").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.center.x + 20*density, held.center.x, 2f)
        assertEquals(initial.center.y + 100*density, held.center.y, 2f)
        assertTrue("Chrome uses the visible mini window size", held.width < initial.width)
        assertEquals(held.width, compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot.width, 2f)
        assertTrue(miniPreview)
        assertEquals(640, videoSurface!!.holder.surfaceFrame.width())
        assertTrue(revealed)
        assertEquals(0, finished)
        compose.runOnIdle { handle!!.end(false) }
        compose.waitForIdle()
        assertFalse(revealed)
        assertFalse(mini)
        assertFalse(miniPreview)
        assertEquals(640, videoSurface!!.holder.surfaceFrame.width())
        compose.runOnIdle { handle!!.horizontal = 0f; handle!!.drag(200*density) }
        compose.waitForIdle()
        compose.runOnIdle { handle!!.release(200*density, 0f) }
        compose.waitForIdle()
        assertTrue(mini)
        assertEquals(1, finished)
        assertEquals(1, surfaces)
        assertEquals("Settling must not resize the video buffer", 640, videoSurface!!.holder.surfaceFrame.width())
        assertEquals(360, videoSurface!!.holder.surfaceFrame.height())
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
                onFullscreenSwipe = { fullscreen = it },
                modifier = Modifier.size(LocalConfiguration.current.screenWidthDp.dp, 600.dp).testTag("root")) {
                handle = LocalFullscreenSwipeHandle.current
                val swipe = handle
                val callbacks = remember(swipe) { object : GestureCallbacks {
                    override fun onFullscreenSwipeProgress(distancePx: Float) { swipe?.drag?.invoke(distancePx) }
                    override fun onWindowSwipeProgress(dx: Float, dy: Float) {
                        swipe?.horizontal = dx
                        swipe?.drag?.invoke(dy)
                    }
                    override fun onFullscreenSwipeCancel() { swipe?.end?.invoke(false) }
                    override fun onGestureEnd() { swipe?.end?.invoke(false) }
                    override fun onFullscreenSwipeRelease(distancePx: Float, velocityPxPerSec: Float): Boolean =
                        swipe?.release?.invoke(distancePx, velocityPxPerSec) ?: false
                    override fun onFullscreenSwipe(up: Boolean) { fullscreen = up }
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
        // Deliberate travel clears the production distance threshold without a fling.
        root.performTouchInput { down(embedded.center); moveBy(Offset(0f, -120*density)) }
        compose.waitForIdle()
        val preview = bounds()
        assertEquals("Drag keeps the player size", embedded.height, preview.height, 2f)
        assertEquals(embedded.top, preview.top, 2f)
        val heldScale = requireNotNull(handle).pictureScale
        assertTrue(heldScale > 1f)
        assertFalse(fullscreen)
        compose.mainClock.autoAdvance = false
        try {
            root.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(96)
            assertTrue(fullscreen)
            assertTrue("The picture scale must settle with the frame, without snapping",
                handle!!.pictureScale > 1f && handle!!.pictureScale < heldScale)
            val settling = bounds()
            assertTrue(settling.height > embedded.height && settling.height < 600*density)
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        assertTrue(fullscreen)
        assertEquals(600*density, bounds().height, 2f)
        val full = bounds()
        root.performTouchInput { down(full.center); moveBy(Offset(0f, 120*density)) }
        compose.waitForIdle()
        assertEquals(full.top + 120*density, bounds().top, 2f)
        root.performTouchInput { up() }
        compose.waitForIdle()
        assertFalse(fullscreen)
        assertEquals(embedded.height, bounds().height, 2f)
        assertEquals(1f, requireNotNull(handle).pictureScale, 0.001f)
        root.performTouchInput { down(bounds().center); moveBy(Offset(0f, -50*density)) }
        compose.waitForIdle()
        val cancelledScale = handle!!.pictureScale
        assertTrue(cancelledScale > 1f)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { enabled = false }
            root.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(96)
            assertTrue("Cancellation must settle the displayed picture scale",
                handle!!.pictureScale > 1f && handle!!.pictureScale < cancelledScale)
            assertEquals(embedded.height, bounds().height, 2f)
            compose.mainClock.advanceTimeBy(400)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        assertFalse(fullscreen)
        assertEquals(embedded.height, bounds().height, 2f)
        assertEquals(1f, handle!!.pictureScale, 0.001f)
        compose.runOnIdle { handle!!.drag(-80*density); handle!!.end(false) }
        compose.waitForIdle()
        assertEquals(embedded.height, bounds().height, 2f)
        assertEquals(1, surfaces)
    }
}
