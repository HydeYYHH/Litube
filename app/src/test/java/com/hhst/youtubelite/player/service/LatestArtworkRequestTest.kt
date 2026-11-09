package com.hhst.youtubelite.player.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestArtworkRequestTest {
    @Test fun rapidRetargetingCancelsOldReadsAndOnlyLoadsTheNewestPendingImage() = runTest {
        val loaded = mutableListOf<String>()
        val published = mutableListOf<String>()
        var active = 0
        var peak = 0
        var cancelled = false
        val requests = LatestArtworkRequest<String, String>(backgroundScope, load = { key ->
            loaded += key
            active++
            peak = maxOf(peak, active)
            try {
                if (key == "old") awaitCancellation()
                key
            } finally {
                if (key == "old") { cancelled = true; withContext(NonCancellable) { delay(10) } }
                active--
            }
        }, publish = { _, value -> published += value }, discard = { fail("No owned value to discard") },
            loadDispatcher = StandardTestDispatcher(testScheduler))
        requests.submit("old")
        runCurrent()
        requests.submit("old") // duplicate show() does not cancel the shared owner
        requests.submit("middle")
        requests.submit("new")
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf("old", "new"), loaded)
        assertEquals(listOf("new"), published)
        assertEquals(1, peak)
    }

    @Test fun hidingCancelsTheReadAndTheSameUrlCanBeRequestedAgain() = runTest {
        var calls = 0
        var cancelled = 0
        val requests = LatestArtworkRequest<String, String>(backgroundScope, load = {
            calls++
            try { awaitCancellation() } finally { cancelled++ }
        }, publish = { _, _ -> fail("Hidden request published") }, discard = { },
            loadDispatcher = StandardTestDispatcher(testScheduler))
        requests.submit("image")
        runCurrent()
        requests.cancel()
        runCurrent()
        assertEquals(1, cancelled)
        requests.submit("image")
        runCurrent()
        assertEquals(2, calls)
        requests.cancel()
        runCurrent()
        assertEquals(2, cancelled)
    }

    @Test fun cancellationDuringDispatcherHandoffDisposesTheUnpublishedImage() = runTest {
        val discarded = mutableListOf<String>()
        lateinit var requests: LatestArtworkRequest<String, String>
        requests = LatestArtworkRequest(backgroundScope, load = {
            requests.cancel()
            "owned-bitmap"
        }, publish = { _, _ -> fail("Cancelled handoff published") }, discard = { discarded += it },
            loadDispatcher = StandardTestDispatcher(testScheduler))
        requests.submit("image")
        runCurrent()
        assertEquals(listOf("owned-bitmap"), discarded)
    }
}
