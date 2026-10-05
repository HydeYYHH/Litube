package com.hhst.youtubelite.player.engine

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocation
import com.hhst.youtubelite.downloader.android.DeviceEvidence
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class PlaybackMemoryAndroidTest {
    @Test fun highBitrateReadAheadStopsAtBudgetEvenBeforeTheTimeWindowIsFull() {
        val budget = mediaBufferBudgetBytes(Runtime.getRuntime().maxMemory())
        fun atLimit(control: DefaultLoadControl): Boolean {
            val id = PlayerId("memory-regression")
            control.onPrepared(id)
            val allocator = control.getAllocator(id)
            val allocations = ArrayList<Allocation>()
            val timeline = SinglePeriodTimeline(60_000_000L, true, false, false, null,
                MediaItem.fromUri("https://fixture.invalid/media"))
            val parameters = LoadControl.Parameters(id, timeline,
                MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0, 1_000_000,
                1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
            try {
                assertTrue(control.shouldContinueLoading(parameters))
                while (allocator.totalBytesAllocated < budget) allocations += allocator.allocate()
                return control.shouldContinueLoading(parameters)
            } finally {
                allocations.forEach(allocator::release)
                allocations.clear()
                control.onReleased(id)
            }
        }
        val legacy = DefaultLoadControl.Builder().setBufferDurationsMs(50_000, 60_000, 500, 4_000)
            .setTargetBufferBytes(budget).setPrioritizeTimeOverSizeThresholds(true).build()
        assertTrue("Previous time-priority policy still loads past its byte target", atLimit(legacy))
        assertFalse("Production policy must stop at its byte budget", atLimit(defaultLoadControl()))
        assertTrue(budget <= Runtime.getRuntime().maxMemory() / 4)
        DeviceEvidence.writeJson("playback-memory-budget.json",
            """{"heapLimitBytes":${Runtime.getRuntime().maxMemory()},"bufferBudgetBytes":$budget,"legacyLoadsPastBudget":true,"candidateStopsAtBudget":true}""")
    }
}
