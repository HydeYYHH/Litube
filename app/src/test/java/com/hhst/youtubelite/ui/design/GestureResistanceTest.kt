package com.hhst.youtubelite.ui.design

import org.junit.Assert.*
import org.junit.Test

class GestureResistanceTest {
    @Test fun tinyFastFlicksNeverCommitAtAnyDensity() {
        for (density in listOf(1f, 2.75f, 4f)) {
            val threshold = GestureResistance.fullscreenThreshold(840f * density, density)
            assertFalse(GestureResistance.commits(20f * density, 10_000f * density, threshold, density))
            assertFalse(GestureResistance.commits(70f * density, 500f * density, threshold, density))
            assertTrue(GestureResistance.commits(90f * density, 1_200f * density, threshold, density))
            assertTrue(GestureResistance.commits(threshold, 0f, threshold, density))
            assertFalse(GestureResistance.commits(threshold, -1_200f * density, threshold, density))
        }
    }

    @Test fun resistanceIsContinuousMonotonicAndRetracesTheFinger() {
        var previous = 0f
        for (finger in 0..500) {
            val offset = GestureResistance.distance(finger.toFloat(), 1f)
            assertTrue(offset >= previous)
            assertTrue(offset <= finger)
            assertTrue(offset - previous < 1f)
            if (offset > 0f) assertEquals(finger.toFloat(), GestureResistance.fingerDistance(offset, 1f), .001f)
            previous = offset
        }
        assertEquals(0f, GestureResistance.distance(12f, 1f), 0f)
        assertEquals(GestureResistance.distance(180f, 1f) * 3f,
            GestureResistance.distance(540f, 3f), .001f)
    }

    @Test fun edgeTravelRemainsFreeInsideAndRequiresDeliberateOverscrollToFold() {
        assertEquals(90f, GestureResistance.beyondBounds(90f, 0f, 170f, 128f), 0f)
        val small = GestureResistance.beyondBounds(210f, 0f, 170f, 128f)
        val deliberate = GestureResistance.beyondBounds(310f, 0f, 170f, 128f)
        assertTrue(small - 170f < 48f)
        assertTrue(deliberate - 170f > 48f)
        assertEquals(-small + 170f, GestureResistance.beyondBounds(-40f, 0f, 170f, 128f), .001f)
        assertTrue(GestureResistance.beyondBounds(10_000f, 0f, 170f, 128f) < 298f)
    }

    @Test fun thresholdDoesNotBuzzAgainWhenJitteringOrOnRelease() {
        val cue = GestureThreshold()
        assertFalse(cue.crossed(79f, 80f))
        assertTrue(cue.crossed(80f, 80f))
        assertFalse(cue.crossed(78f, 80f))
        assertFalse(cue.crossed(81f, 80f))
        assertFalse(cue.crossed(100f, 80f))
        cue.reset()
        assertTrue(cue.crossed(80f, 80f))
    }

    @Test fun thresholdsRemainReachableInBothOrientations() {
        assertEquals(144f, GestureResistance.fullscreenThreshold(900f, 1f), .001f)
        assertEquals(80f, GestureResistance.fullscreenThreshold(390f, 1f), .001f)
        assertTrue(GestureResistance.commits(100f, 0f, GestureResistance.fullscreenThreshold(390f, 1f), 1f))
    }
}
