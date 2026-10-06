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
