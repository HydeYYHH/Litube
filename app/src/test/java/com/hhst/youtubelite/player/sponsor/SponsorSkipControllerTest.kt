package com.hhst.youtubelite.player.sponsor

import com.hhst.youtubelite.player.sponsor.SponsorBlockManager.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SponsorSkipControllerTest {

    private lateinit var controller: SponsorSkipController
    private val segments = listOf(
        Segment(startMs = 10_000, endMs = 20_000, category = "sponsor"),
        Segment(startMs = 60_000, endMs = 70_000, category = "sponsor"),
    )

    @Before
    fun setUp() {
        controller = SponsorSkipController(countdownMs = 5_000)
    }

    @Test
    fun `countdown shows before segment then skips at entry`() {
        assertNull(controller.tick(7_000, segments, countdownEnabled = true))
        assertEquals(3, controller.countdownSec)
        assertEquals(20_000L, controller.tick(10_000, segments, countdownEnabled = true))
    }

    @Test
    fun `landing mid-segment counts down before skipping`() {
        assertNull(controller.tick(12_000, segments, countdownEnabled = true))
        assertEquals(5, controller.countdownSec)
        assertNull(controller.tick(15_000, segments, countdownEnabled = true))
        assertEquals(2, controller.countdownSec)
        assertEquals(20_000L, controller.tick(17_000, segments, countdownEnabled = true))
    }

    @Test
    fun `cancel suppresses the segment and offers the chip`() {
        controller.tick(12_000, segments, countdownEnabled = true)
        controller.cancelPending()
        assertNull(controller.tick(13_000, segments, countdownEnabled = true))
        assertTrue(controller.chipActive)
        assertEquals(20_000L, controller.chipTarget(13_000, segments))
    }

    @Test
    fun `tapping the countdown card skips the pending segment now`() {
        controller.tick(12_000, segments, countdownEnabled = true)
        assertEquals(20_000L, controller.skipPending())
        assertNull(controller.tick(13_000, segments, countdownEnabled = true))
        assertTrue(controller.chipActive)
    }

    @Test
    fun `skip pending without a pending segment is a no-op`() {
        assertNull(controller.skipPending())
    }

    @Test
    fun `user seek into segment suppresses it`() {
        controller.onUserSeek(15_000, segments)
        assertNull(controller.tick(16_000, segments, countdownEnabled = true))
        assertTrue(controller.chipActive)
    }

    @Test
    fun `countdown off skips instantly (legacy)`() {
        assertEquals(20_000L, controller.tick(11_000, segments, countdownEnabled = false))
        assertNull(controller.countdownSec)
    }

    @Test
    fun `suppression survives a refetch with equal segments`() {
        controller.tick(12_000, segments, countdownEnabled = true)
        controller.cancelPending()
        val refetched = segments.map { it.copy() }
        assertNull(controller.tick(13_000, refetched, countdownEnabled = true))
        assertTrue(controller.chipActive)
    }

    @Test
    fun `reset clears suppression`() {
        controller.onUserSeek(15_000, segments)
        controller.reset()
        assertNull(controller.tick(16_000, segments, countdownEnabled = true))
        assertFalse(controller.chipActive)
    }

    @Test
    fun `no countdown outside the pre-window`() {
        assertNull(controller.tick(2_000, segments, countdownEnabled = true))
        assertNull(controller.countdownSec)
    }

    @Test
    fun `fired skip does not re-arm on keyframe snap-back`() {
        assertNull(controller.tick(7_000, segments, countdownEnabled = true))
        assertEquals(20_000L, controller.tick(10_000, segments, countdownEnabled = true))
        // The 20s seek snapped back to 19s (cue granularity): the segment must stay suppressed to break the skip -> snap-back loop.
        assertNull(controller.tick(19_000, segments, countdownEnabled = true))
        assertTrue(controller.chipActive)
        assertEquals(20_000L, controller.chipTarget(19_000, segments))
    }

    @Test
    fun `legacy instant skip does not loop on snap-back`() {
        assertEquals(20_000L, controller.tick(11_000, segments, countdownEnabled = false))
        assertNull(controller.tick(19_000, segments, countdownEnabled = false))
        assertTrue(controller.chipActive)
    }

    @Test
    fun `zero-length poi segments do not arm countdown`() {
        val poi = listOf(Segment(startMs = 10_000, endMs = 10_000, category = "poi_highlight"))
        assertNull(controller.tick(8_000, poi, countdownEnabled = true))
        assertNull(controller.countdownSec)
        assertNull(controller.tick(10_000, poi, countdownEnabled = true))
        assertNull(controller.countdownSec)
    }

    private val highlights = listOf(
        Segment(startMs = 60_000, endMs = 60_000, category = "poi_highlight"),
    )

    @Test
    fun `highlight chip shows within window and jumps to the point`() {
        // 50s in: 10s before the point, chip window (±15s) is open.
        assertNull(controller.tick(50_000, highlights, countdownEnabled = true))
        assertTrue(controller.chipActive)
        assertTrue(controller.chipIsHighlight)
        assertEquals(60_000L, controller.chipTarget(50_000, highlights))
        assertNull(controller.tick(0, highlights, countdownEnabled = true))
        assertFalse(controller.chipActive)
    }

    @Test
    fun `dismissed highlight chip stays retired for the video`() {
        controller.tick(55_000, highlights, countdownEnabled = true)
        controller.dismissHighlightChip()
        assertFalse(controller.chipActive)
        assertNull(controller.tick(59_000, highlights, countdownEnabled = true))
        assertFalse(controller.chipActive)
        assertFalse(controller.chipIsHighlight)
    }

    @Test
    fun `segment chip wins over highlight chip`() {
        val mixed = segments + Segment(startMs = 65_000, endMs = 65_000, category = "poi_highlight")
        controller.onUserSeek(66_000, mixed)
        assertNull(controller.tick(66_500, mixed, countdownEnabled = true))
        assertTrue(controller.chipActive)
        assertEquals(70_000L, controller.chipTarget(66_500, mixed))
        assertFalse(controller.chipIsHighlight)
    }

    @Test
    fun `reset clears retired highlights`() {
        controller.tick(55_000, highlights, countdownEnabled = true)
        controller.dismissHighlightChip()
        controller.reset()
        assertTrue(controller.tick(55_000, highlights, countdownEnabled = true) == null)
        assertTrue(controller.chipActive)
        assertTrue(controller.chipIsHighlight)
    }
}
