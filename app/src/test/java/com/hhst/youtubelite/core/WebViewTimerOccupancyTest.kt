package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewTimerOccupancyTest {

    @Test
    fun acquireResume_releaseLastPauses() {
        val clock = RecordingWebViewTimerClock()
        val occ = WebViewTimerOccupancy(clock)
        val browser = occ.acquire(WebViewTimerOwner.BROWSER)
        assertTrue(clock.events.isEmpty())
        browser.release()
        assertEquals(listOf("pause"), clock.events)
        val again = occ.acquire(WebViewTimerOwner.BROWSER)
        assertEquals(listOf("pause", "resume"), clock.events)
        again.release()
        assertEquals(listOf("pause", "resume", "pause"), clock.events)
    }

    @Test
    fun potokenAndBrowser_bothExiting_leavePaused_oneActiveKeepsRunning() {
        val clock = RecordingWebViewTimerClock()
        val occ = WebViewTimerOccupancy(clock)
        val browser = occ.acquire(WebViewTimerOwner.BROWSER)
        val potoken = occ.acquire(WebViewTimerOwner.POTOKEN)
        assertTrue(clock.events.isEmpty())
        browser.release()
        assertTrue("one still active leaves timers running", clock.events.isEmpty())
        assertFalse(occ.isPaused)
        potoken.release()
        assertEquals(listOf("pause"), clock.events)
        assertTrue(occ.isPaused)
    }

    @Test
    fun nestedAcquire_sameOwner_needsMatchingReleases() {
        val clock = RecordingWebViewTimerClock()
        val occ = WebViewTimerOccupancy(clock)
        val a = occ.acquire(WebViewTimerOwner.POTOKEN)
        val b = occ.acquire(WebViewTimerOwner.POTOKEN)
        a.release()
        assertFalse(occ.isPaused)
        b.release()
        assertTrue(occ.isPaused)
    }

    @Test
    fun withOwner_releasesOnException() {
        val clock = RecordingWebViewTimerClock()
        val occ = WebViewTimerOccupancy(clock)
        try {
            occ.withOwner(WebViewTimerOwner.POTOKEN) { error("boom") }
        } catch (_: IllegalStateException) {
        }
        assertTrue(occ.isPaused)
        assertEquals(0, occ.totalHolders)
    }

    @Test
    fun browserRelease_duringPotokenEval_doesNotPause() {
        val clock = RecordingWebViewTimerClock()
        val occ = WebViewTimerOccupancy(clock)
        val browser = occ.acquire(WebViewTimerOwner.BROWSER)
        occ.withOwner(WebViewTimerOwner.POTOKEN) {
            browser.release()
            assertTrue("PoToken eval must keep timers running", clock.events.isEmpty())
            assertFalse(occ.isPaused)
        }
        assertEquals(listOf("pause"), clock.events)
        assertTrue(occ.isPaused)
    }
}
