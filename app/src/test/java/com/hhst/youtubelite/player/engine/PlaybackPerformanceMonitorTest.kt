package com.hhst.youtubelite.player.engine

import org.junit.Assert.*
import org.junit.Test

class PlaybackPerformanceMonitorTest {
    @Test fun expectedRateIncludesSpeedAndRefreshCap() {
        assertEquals(60f, PlaybackPerformanceMonitor.expectedFps(60f, 2f, 60f)!!, 0f)
        assertEquals(30f, PlaybackPerformanceMonitor.expectedFps(60f, 0.5f, 120f)!!, 0f)
        assertNull(PlaybackPerformanceMonitor.expectedFps(null, 1f, 60f))
    }
    @Test fun lowFpsNeedsTwoWindowsThenRecovers() {
        val events = mutableListOf<String>()
        val monitor = PlaybackPerformanceMonitor { kind, state, _ -> events += "$kind:$state" }
        monitor.reset(0)
        monitor.sample(3000, 0, 0, 30f, true, false)
        monitor.sample(8000, 100, 0, 30f, true, false)
        assertFalse(events.contains("low_fps:start"))
        monitor.sample(13000, 200, 0, 30f, true, false)
        assertTrue(events.contains("low_fps:start"))
        monitor.sample(18000, 350, 0, 30f, true, false)
        assertTrue(events.contains("low_fps:recovered"))
    }
    @Test fun dropsNeedBothAbsoluteAndRelativeThresholds() {
        val events = mutableListOf<String>()
        val monitor = PlaybackPerformanceMonitor { kind, state, _ -> events += "$kind:$state" }
        monitor.sample(0, 0, 0, 30f, true, false)
        monitor.sample(5000, 100, 9, 30f, true, false)
        assertFalse(events.contains("dropped_frames:start"))
        monitor.sample(10000, 250, 19, 30f, true, false)
        assertTrue(events.contains("dropped_frames:start"))
        monitor.sample(15000, 550, 29, 60f, true, false)
        assertTrue(events.contains("dropped_frames:recovered"))
    }
    @Test fun pausedHiddenBufferingAndStabilizationCannotTriggerFpsOrStall() {
        val events = mutableListOf<String>()
        val monitor = PlaybackPerformanceMonitor { kind, state, _ -> events += "$kind:$state" }
        monitor.reset(0)
        for (time in 0L..2000L step 1000) monitor.sample(time, 0, 0, 60f, true, false)
        for (time in 3000L..10000L step 1000) monitor.sample(time, 0, 0, 60f, false, false)
        for (time in 11000L..15000L step 1000) monitor.sample(time, 0, 0, 60f, true, true)
        assertTrue(events.isEmpty())
    }
    @Test fun stallAndBufferTransitionsHaveCooldownAndRecovery() {
        val events = mutableListOf<String>()
        val monitor = PlaybackPerformanceMonitor { kind, state, _ -> events += "$kind:$state" }
        monitor.reset(0)
        monitor.sample(3000, 0, 0, null, true, false)
        monitor.sample(5000, 0, 0, null, true, false)
        monitor.sample(6000, 0, 0, null, true, false)
        assertEquals(1, events.count { it == "render_stall:start" })
        monitor.sample(7000, 1, 0, null, true, false)
        assertTrue(events.contains("render_stall:recovered"))
        monitor.sample(8000, 1, 0, null, true, true)
        monitor.sample(14000, 1, 0, null, true, true)
        assertTrue(events.contains("long_buffer:start"))
        monitor.sample(15000, 1, 0, null, true, false)
        assertTrue(events.contains("long_buffer:recovered"))
        monitor.sample(16000, 1, 0, null, true, true)
        monitor.sample(17000, 1, 0, null, true, false)
        monitor.sample(18000, 1, 0, null, true, true)
        assertTrue(events.contains("frequent_buffer:start"))
    }
}
