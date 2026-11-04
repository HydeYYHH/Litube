package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMathTest {

    @Test
    fun doubleTapZone_thirds() {
        assertEquals(GestureMath.DoubleTapZone.LEFT, GestureMath.doubleTapZone(100f, 1000f))
        assertEquals(GestureMath.DoubleTapZone.CENTER, GestureMath.doubleTapZone(500f, 1000f))
        assertEquals(GestureMath.DoubleTapZone.RIGHT, GestureMath.doubleTapZone(900f, 1000f))
        assertEquals(GestureMath.DoubleTapZone.CENTER, GestureMath.doubleTapZone(0f, 0f))
    }

    @Test
    fun seekOffset_fullWidthMapsTo120s() {
        assertEquals(120_000L, GestureMath.seekOffsetMs(1000f, 1000f))
        assertEquals(-120_000L, GestureMath.seekOffsetMs(-1000f, 1000f))
        assertEquals(60_000L, GestureMath.seekOffsetMs(500f, 1000f))
        assertEquals(0L, GestureMath.seekOffsetMs(0f, 1000f))
        assertEquals(0L, GestureMath.seekOffsetMs(100f, 0f))
    }

    @Test
    fun brightnessDelta_upIncreases() {
        assertEquals(0.15f, GestureMath.brightnessDelta(-100f, 1000f), 0.001f)
        assertEquals(-0.15f, GestureMath.brightnessDelta(100f, 1000f), 0.001f)
    }

    @Test
    fun volumeDelta_usesVolumeFactor() {
        assertEquals(0.12f, GestureMath.volumeDelta(-100f, 1000f), 0.001f)
    }

    @Test
    fun resolveDragMode_horizontalIsSeek() {
        assertEquals(
            GestureMath.DragMode.SEEK,
            GestureMath.resolveDragMode(dx = 50f, dy = 10f, startX = 500f, width = 1000f),
        )
    }

    @Test
    fun resolveDragMode_verticalZones() {
        assertEquals(
            GestureMath.DragMode.BRIGHTNESS,
            GestureMath.resolveDragMode(10f, 50f, startX = 100f, width = 1000f),
        )
        assertEquals(
            GestureMath.DragMode.VOLUME,
            GestureMath.resolveDragMode(10f, 50f, startX = 900f, width = 1000f),
        )
        assertEquals(
            GestureMath.DragMode.NONE,
            GestureMath.resolveDragMode(10f, 50f, startX = 500f, width = 1000f),
        )
    }

    @Test
    fun resolveDragMode_verticalFeedIgnoresSideZones() {
        assertEquals(
            GestureMath.DragMode.NONE,
            GestureMath.resolveDragMode(10f, 50f, startX = 100f, width = 1000f, verticalFeed = true),
        )
        assertEquals(
            GestureMath.DragMode.SEEK,
            GestureMath.resolveDragMode(dx = 50f, dy = 10f, startX = 100f, width = 1000f, verticalFeed = true),
        )
    }

    @Test fun embeddedUpwardEntryHasAWiderZoneWithoutTakingOverFullscreenEdgeSliders() {
        for (x in listOf(250f, 750f)) {
            assertEquals(GestureMath.DragMode.NONE,
                GestureMath.resolveDragMode(10f, -50f, x, 1000f, fullscreenEntry = true))
        }
        assertEquals(GestureMath.DragMode.BRIGHTNESS,
            GestureMath.resolveDragMode(10f, -50f, 100f, 1000f, fullscreenEntry = true))
        assertEquals(GestureMath.DragMode.VOLUME,
            GestureMath.resolveDragMode(10f, -50f, 900f, 1000f, fullscreenEntry = true))
        assertEquals(GestureMath.DragMode.BRIGHTNESS,
            GestureMath.resolveDragMode(10f, -50f, 250f, 1000f))
        assertEquals(GestureMath.DragMode.VOLUME,
            GestureMath.resolveDragMode(10f, 50f, 750f, 1000f, fullscreenEntry = true))
        assertEquals(GestureMath.DragMode.SEEK,
            GestureMath.resolveDragMode(100f, -50f, 500f, 1000f, fullscreenEntry = true))
    }

    @Test
    fun accumulateLevel_clampsWithoutOscillating() {
        assertEquals(1f, GestureMath.accumulateLevel(0.9f, 0.2f, 0.01f, 1f), 0.001f)
        assertEquals(0.01f, GestureMath.accumulateLevel(0.05f, -0.1f, 0.01f, 1f), 0.001f)
        val stepped = GestureMath.accumulateLevel(7.4f, 0.3f, 0f, 15f)
        assertEquals(7.7f, stepped, 0.001f)
        assertEquals(8.0f, GestureMath.accumulateLevel(stepped, 0.3f, 0f, 15f), 0.001f)
    }

    @Test
    fun levelPercent_mapsSessionLevel() {
        assertEquals(50, GestureMath.levelPercent(0.5f, 1f))
        assertEquals(100, GestureMath.levelPercent(15f, 15f))
        assertEquals(0, GestureMath.levelPercent(0f, 15f))
        assertEquals(0, GestureMath.levelPercent(1f, 0f))
        assertEquals(67, GestureMath.levelPercent(10f, 15f))
    }


}

class VelocityTrackerTest {

    @Test
    fun emptyOrSingleSampleIsZero() {
        val tracker = GestureMath.VelocityTracker()
        assertEquals(0f, tracker.velocityY(), 0.001f)
        tracker.add(0, 10f)
        assertEquals(0f, tracker.velocityY(), 0.001f)
    }

    @Test
    fun steadyMotionReportsSignedVelocity() {
        val tracker = GestureMath.VelocityTracker()
        // 500 px upward per second: y decreases (up) at 5 px per 10 ms.
        for (i in 0..10) tracker.add(i * 10L, 1000f - i * 5f)
        assertEquals(-500f, tracker.velocityY(), 1f)
    }

    @Test
    fun slowHoldBeforeReleaseIsNotAFLing() {
        val tracker = GestureMath.VelocityTracker()
        // Fast motion then a 300 ms hold: the window drops the old fast samples, so the release reports ~0 instead of a fling.
        for (i in 0..5) tracker.add(i * 10L, 1000f - i * 20f)
        for (i in 6..36) tracker.add(i * 10L, 900f)
        assertEquals(0f, tracker.velocityY(), 1f)
    }

    @Test
    fun resetClearsSamples() {
        val tracker = GestureMath.VelocityTracker()
        for (i in 0..10) tracker.add(i * 10L, i * 5f)
        tracker.reset()
        assertEquals(0f, tracker.velocityY(), 0.001f)
    }
}
