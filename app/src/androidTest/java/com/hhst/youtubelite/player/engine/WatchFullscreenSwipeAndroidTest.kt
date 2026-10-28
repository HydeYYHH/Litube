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
        shell("settings put system accelerometer_rotation 0")
        try {
            return block()
        } finally {
            shell("settings put system accelerometer_rotation $previous")
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

            // Slow upward drag held mid-travel: the picture follows the finger.
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
            assertTrue("The video frame must follow the finger (mid=$midBounds embedded=$embedded)",
                midBounds.top > embedded.top + 40)
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
                model.uiState.value.fullscreen &&
                    (surfaceBounds(fixture)?.height() ?: 0) >= activity.resources.displayMetrics.heightPixels - 20
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
}
