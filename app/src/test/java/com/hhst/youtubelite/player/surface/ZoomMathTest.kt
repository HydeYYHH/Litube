package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {

    @Test
    fun clampScale_staysBetweenOneAndFive() {
        assertEquals(1f, ZoomMath.clampScale(0.5f))
        assertEquals(1f, ZoomMath.clampScale(1f))
        assertEquals(2.5f, ZoomMath.clampScale(2.5f))
        assertEquals(5f, ZoomMath.clampScale(9f))
    }

    @Test
    fun clampTranslation_zeroWhenNotZoomed() {
        assertEquals(0f, ZoomMath.clampTranslation(40f, 1000f, 1f))
        assertEquals(0f, ZoomMath.clampTranslation(-40f, 0f, 2f))
    }

    @Test
    fun clampTranslation_limitsToVisibleCrop() {
        // 2× on a 200 px view → ±100 px travel.
        assertEquals(100f, ZoomMath.clampTranslation(180f, 200f, 2f))
        assertEquals(-100f, ZoomMath.clampTranslation(-180f, 200f, 2f))
        assertEquals(40f, ZoomMath.clampTranslation(40f, 200f, 2f))
    }

    @Test
    fun isZoomed_scaleOrPan() {
        assertFalse(ZoomMath.isZoomed(1f, 0f, 0f))
        assertTrue(ZoomMath.isZoomed(1.5f, 0f, 0f))
        assertTrue(ZoomMath.isZoomed(1f, 8f, 0f))
        assertTrue(ZoomMath.isZoomed(1f, 0f, -8f))
    }
}
