package com.hhst.youtubelite.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** Framework duration scaling must not also be applied to the animation spec. */
class MotionScaleTest {
    @Test fun motionDurationMeasuresEachSystemScale() {
        assertEquals(0, motionDuration(300, 0f))
        assertEquals(300, motionDuration(300, 0.5f))
        assertEquals(300, motionDuration(300, 1f))
        assertEquals(300, motionDuration(300, 2f))
        // A negative value is unreadable input, not a request for negative time.
        assertEquals(0, motionDuration(300, -1f))
        // A nonzero scale never rounds a short duration down to nothing.
        assertEquals(1, motionDuration(1, 0.5f))
    }
}
