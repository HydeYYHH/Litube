@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.hhst.youtubelite.core.DeviceEvidence
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Vertical fullscreen swipe driven by real motion events against a real
 * WatchActivity: the same SurfaceView and prepared media survive both ways,
 * the picture follows the finger, and a reversed or short drag cancels.
 */
class WatchFullscreenSwipeAndroidTest {

    private fun windowBounds(fixture: OfflineWatchFixture): androidx.compose.ui.geometry.Rect {
        fun node(root: SemanticsNode): SemanticsNode? =
            if (root.config.getOrNull(SemanticsProperties.TestTag) == "player-window") root
            else root.children.firstNotNullOfOrNull(::node)
        fun find(view: android.view.View): SemanticsNode? {
            val owner = runCatching { view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner }.getOrNull()
            owner?.let { node(it.rootSemanticsNode) }?.let { return it }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        var bounds: androidx.compose.ui.geometry.Rect? = null
        val read = { bounds = find(fixture.activity.window.decorView)?.boundsInWindow }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) read()
        else fixture.instrumentation.runOnMainSync { read() }
        return requireNotNull(bounds) { "Player window semantics are unavailable" }
    }

    private fun surfaceBounds(fixture: OfflineWatchFixture): Rect? {
        val view = fixture.surface() ?: return null
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    private fun swipe(fixture: OfflineWatchFixture, fromX: Float, fromY: Float, toY: Float, steps: Int, stepMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        fixture.instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        for (i in 1..steps) {
            val y = fromY + (toY - fromY) * i / steps
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, fromX, y, 0))
            Thread.sleep(stepMs)
        }
        fixture.instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, fromX, toY, 0))
    }

    private fun hideChrome(fixture: OfflineWatchFixture) {
        fixture.instrumentation.runOnMainSync { fixture.model.onHideControls() }
        fixture.await { !fixture.model.uiState.value.controlsVisible }
        Thread.sleep(400) // Let the fade-out remove the chrome from the hit path.
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    /** Auto-rotate would let the emulator's flat orientation sensor auto-enter fullscreen. */
    private fun <T> withAutoRotateOff(block: () -> T): T {
        val previous = shell("settings get system accelerometer_rotation").trim().ifBlank { "1" }
        val confirmation = shell("settings get secure immersive_mode_confirmations").trim()
        shell("settings put system accelerometer_rotation 0")
        shell("settings put secure immersive_mode_confirmations confirmed")
        try {
            return block()
        } finally {
            shell("settings put system accelerometer_rotation $previous")
            shell(if (confirmation == "null" || confirmation.isEmpty()) "settings delete secure immersive_mode_confirmations"
                else "settings put secure immersive_mode_confirmations $confirmation")
        }
    }

    @Test fun swipeFromRealWatchInterpolatesCommitsCancelsAndSurvivesRotation() = withAutoRotateOff {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        // Landscape fullscreen fills the screen height only on a phone-aspect
        // host; the 4:3 matrix window letterboxes and cannot satisfy it.
        assumeTrue(
            "Requires the phone-aspect host (wm size 1080x2400); the 1600x1200 " +
                "matrix host letterboxes landscape fullscreen by design",
            metrics.heightPixels.toFloat() / metrics.widthPixels >= 16f / 9f - 0.02f,
        )
        OfflineWatchFixture("swipeFs0001").use { fixture ->
            fixture.launch()
            val activity = fixture.activity
            val engine = fixture.engine
            val model = fixture.model
            val screenH = activity.resources.displayMetrics.heightPixels
            val screenW = activity.resources.displayMetrics.widthPixels

            val embeddedSurface = fixture.surface()!!
            val embedded = surfaceBounds(fixture)!!
            val preparesAtStart = engine.mediaPrepareCount
            val positionAtStart = model.positionState.value

            // Hold the upward drag before committing fullscreen.
            // Chrome (center controls, time bar hit canvas) covers the vertical
            // middle of an embedded 16:9 player, so the chrome is hidden first —
            // swiping with hidden chrome is the primary real-world path.
            hideChrome(fixture)
            val startX = embedded.centerX().toFloat()
            val startY = embedded.centerY().toFloat()
            val downTime = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, startX, startY, 0))
            val heldY = startY - 400f
            for (i in 1..10) {
                val y = startY + (heldY - startY) * i / 10
                fixture.instrumentation.sendPointerSync(
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, startX, y, 0))
                Thread.sleep(40)
            }
            val midBounds = surfaceBounds(fixture)!!
            assertTrue("The image enlarges inside its fixed frame (mid=$midBounds embedded=$embedded)",
                midBounds.top <= embedded.top)
            assertTrue("Dragging keeps the surface height", abs(embedded.height() - midBounds.height()) <= 2)
            assertFalse("Not fullscreen yet mid-drag", model.uiState.value.fullscreen)
            assertSame(embeddedSurface, fixture.surface())
            DeviceEvidence.captureScene("watch-swipe-mid-drag")
            // Release past the commit progress: settles into fullscreen.
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, startX, heldY, 0))
            fixture.await { model.uiState.value.fullscreen }
            // Portrait fullscreen anchors the letterboxed 16:9 frame to the top edge at full width.
            fixture.await {
                val bounds = surfaceBounds(fixture)
                bounds != null && bounds.width() >= screenW - 20 && bounds.top < screenH / 4 &&
                    abs(bounds.height() - bounds.width() * 9f / 16f) < 40
            }
            assertSame("Fullscreen must reuse the embedded SurfaceView", embeddedSurface, fixture.surface())
            assertEquals("A swipe transition must not re-prepare media", preparesAtStart, engine.mediaPrepareCount)
            assertTrue(model.uiState.value.isPlaying)

            // Rotation inside fullscreen keeps mode, surface and position. Forced
            // (not sensor) landscape: auto-rotate is off, so a sensor request never fires.
            fixture.instrumentation.runOnMainSync {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            fixture.await { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            fixture.await {
                val frame = windowBounds(fixture)
                model.uiState.value.fullscreen &&
                    abs((surfaceBounds(fixture)?.height() ?: 0) - activity.resources.displayMetrics.heightPixels) < 20 &&
                    abs(frame.width - activity.resources.displayMetrics.widthPixels) < 20 &&
                    abs(frame.height - activity.resources.displayMetrics.heightPixels) < 20
            }
            assertSame(embeddedSurface, fixture.surface())
            assertEquals(preparesAtStart, engine.mediaPrepareCount)
            val landscape = surfaceBounds(fixture)!!
            val positionAfterRotation = model.positionState.value
            assertTrue("Position advances through rotation", positionAfterRotation >= positionAtStart)

            // A reversed drag cancels: down 10% of the height, back up, release.
            hideChrome(fixture)
            val cancelStartY = landscape.centerY().toFloat()
            val cancelX = landscape.centerX().toFloat()
            val cancelDown = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, cancelX, cancelStartY, 0))
            val cancelLow = cancelStartY + landscape.height() * 0.1f
            val points = (1..6).map { cancelStartY + (cancelLow - cancelStartY) * it / 6 } +
                (1..6).map { cancelLow - (cancelLow - cancelStartY) * it / 6 }
            for (y in points) {
                fixture.instrumentation.sendPointerSync(
                    MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, cancelX, y, 0))
                Thread.sleep(40)
            }
            fixture.instrumentation.sendPointerSync(
                MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, cancelX, cancelStartY, 0))
            Thread.sleep(600)
            fixture.await { model.uiState.value.fullscreen }
            assertSame(embeddedSurface, fixture.surface())
            assertEquals(preparesAtStart, engine.mediaPrepareCount)

            // Committed exit: slow downward drag returns to the embedded slot.
            hideChrome(fixture)
            DeviceEvidence.captureScene("watch-fullscreen-before-exit")
            DeviceEvidence.writeJson("watch-fullscreen-exit-geometry.json", com.hhst.youtubelite.core.TestJson.any(mapOf(
                "surface" to surfaceBounds(fixture).toString(), "previousSurface" to landscape.toString(),
                "frame" to windowBounds(fixture).toString(), "x" to cancelX, "y" to cancelStartY,
                "width" to activity.resources.displayMetrics.widthPixels, "height" to activity.resources.displayMetrics.heightPixels,
            )))
            swipe(fixture, cancelX, cancelStartY, cancelStartY + landscape.height() * 0.25f, 12, 40)
            fixture.await { !model.uiState.value.fullscreen }
            fixture.await {
                val bounds = surfaceBounds(fixture)
                bounds != null && kotlin.math.abs(bounds.height() - (bounds.width() * 9 / 16)) < 40
            }
            assertSame(embeddedSurface, fixture.surface())
            assertEquals("Exit must not re-prepare either", preparesAtStart, engine.mediaPrepareCount)
            val positionAtEnd = model.positionState.value
            assertTrue("Playback position kept running", positionAtEnd > positionAfterRotation)
            DeviceEvidence.captureScene("watch-swipe-embedded-restored")
        }
    }

    @Test fun belowThresholdDragBouncesBackToEmbedded() = withAutoRotateOff {
        OfflineWatchFixture("swipeFs0002").use { fixture ->
            fixture.launch()
            val surface = fixture.surface()!!
            val embedded = surfaceBounds(fixture)!!
            val prepares = fixture.engine.mediaPrepareCount
            hideChrome(fixture)
            val startX = embedded.centerX().toFloat()
            val startY = embedded.centerY().toFloat()
            // 6% of the window height: below 35% of the 40% travel, no fling.
            swipe(fixture, startX, startY, startY - fixture.activity.resources.displayMetrics.heightPixels * 0.06f, 8, 60)
            Thread.sleep(600)
            fixture.await { !fixture.model.uiState.value.fullscreen }
            assertEquals(embedded, surfaceBounds(fixture))
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
        }
    }

    @Test fun centerDownwardDragReturnsToTheSourceAndRestoresWithoutPreparingAgain() = withAutoRotateOff {
        OfflineWatchFixture("swipeMini01").use { fixture ->
            val sourceIntent = android.content.Intent(fixture.app, com.hhst.youtubelite.home.HomeActivity::class.java)
                .putExtra("playback_test", true)
            androidx.test.core.app.ActivityScenario.launch<com.hhst.youtubelite.home.HomeActivity>(sourceIntent).use { source ->
                lateinit var sourceActivity: com.hhst.youtubelite.home.HomeActivity
                source.onActivity { sourceActivity = it }
                fixture.launch(source = sourceActivity)
                val model = fixture.model
                if (!model.uiState.value.controlsVisible) {
                    fixture.instrumentation.runOnMainSync { model.onToggleControls() }
                }
                val originalHost = fixture.activity
                val originalSurface = fixture.surface()
                fixture.await {
                    val frame = windowBounds(fixture)
                    val surface = surfaceBounds(fixture)
                    surface != null && abs(frame.width - surface.width()) < 2 &&
                        abs(frame.height - surface.height()) < 2
                }
                Thread.sleep(300)
                val embedded = surfaceBounds(fixture)!!
                val prepares = fixture.engine.mediaPrepareCount
                val startX = embedded.centerX().toFloat()
                val startY = embedded.centerY().toFloat()
                val endY = startY + fixture.activity.resources.displayMetrics.heightPixels * 0.20f
                val downTime = SystemClock.uptimeMillis()
                var halfwayWidth = embedded.width()
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0))
                for (i in 1..12) {
                    fixture.instrumentation.sendPointerSync(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE, startX, startY + (endY - startY) * i / 12, 0))
                    Thread.sleep(40)
                    if (i == 6) halfwayWidth = windowBounds(fixture).width.toInt()
                }
                val held = windowBounds(fixture)
                assertFalse(model.uiState.value.mini)
                assertTrue("The frame shrinks during the drag", halfwayWidth < embedded.width())
                assertTrue("Shrinking continues with finger travel", held.width < halfwayWidth)
                DeviceEvidence.captureScene("watch-mini-held")
                assertTrue("The center follows the finger (frame=$held start=$embedded finger=$endY)", abs(held.center.y - endY) < 16f)
                assertSame(originalSurface, fixture.surface())
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
                assertTrue(held.top > embedded.top)
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, startX, endY, 0))
                fixture.await { model.uiState.value.mini && originalHost.isFinishing && sourceActivity.hasWindowFocus() }
                fun sourceSurface(view: android.view.View): SurfaceView? {
                    if (view is SurfaceView && view.isShown) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        sourceSurface(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                fixture.await { sourceSurface(sourceActivity.window.decorView) != null }
                assertTrue(model.uiState.value.isPlaying)
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
                val monitor = fixture.instrumentation.addMonitor(com.hhst.youtubelite.watch.WatchActivity::class.java.name, null, false)
                try {
                    fixture.instrumentation.runOnMainSync { model.onMiniRestore() }
                    fixture.activity = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) as com.hhst.youtubelite.watch.WatchActivity
                } finally { fixture.instrumentation.removeMonitor(monitor) }
                fixture.await { !model.uiState.value.mini && surfaceBounds(fixture) == embedded }
                assertTrue(model.uiState.value.isPlaying)
                assertEquals(prepares, fixture.engine.mediaPrepareCount)
            }
        }
    }

    @Test fun cancelledDownwardDragReturnsToItsEmbeddedBounds() = withAutoRotateOff {
        OfflineWatchFixture("swipeMini02").use { fixture ->
            fixture.launch()
            hideChrome(fixture)
            val surface = fixture.surface()!!
            val embedded = surfaceBounds(fixture)!!
            val prepares = fixture.engine.mediaPrepareCount
            val x = embedded.centerX().toFloat()
            val startY = embedded.centerY().toFloat()
            val downTime = SystemClock.uptimeMillis()
            fixture.instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
            for (i in 1..8) {
                fixture.instrumentation.sendPointerSync(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, x, startY + fixture.activity.resources.displayMetrics.heightPixels * 0.10f * i / 8, 0))
                Thread.sleep(40)
            }
            fixture.instrumentation.sendPointerSync(MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, startY, 0))
            fixture.await { !fixture.model.uiState.value.mini && surfaceBounds(fixture) == embedded }
            assertSame(surface, fixture.surface())
            assertEquals(prepares, fixture.engine.mediaPrepareCount)
            assertTrue(fixture.model.uiState.value.isPlaying)
        }
    }
}
