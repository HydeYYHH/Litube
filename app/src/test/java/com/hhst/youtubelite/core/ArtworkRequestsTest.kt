package com.hhst.youtubelite.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkRequestsTest {
    @Test fun readersShareAnOwnerAndOneCancellationDoesNotCancelTheOthers() = runTest {
        val ready = CompletableDeferred<String>()
        var loads = 0
        val requests = ArtworkRequests<String, String>(backgroundScope) { loads++; ready.await() }
        val readers = List(20) { async { requests.get("avatar") } }
        runCurrent()
        assertEquals(1, loads)
        readers.first().cancelAndJoin()
        assertFalse(ready.isCancelled)
        ready.complete("image")
        assertTrue(readers.drop(1).all { it.await() == "image" })
    }

    @Test fun abandoningTheLastReaderCancelsTheOwnerAndAllowsARetry() = runTest {
        var loads = 0
        var cancelled = 0
        val hold = CompletableDeferred<Unit>()
        val requests = ArtworkRequests<String, String>(backgroundScope) {
            loads++
            try { hold.await(); "image" } finally { cancelled++ }
        }
        val first = async { requests.get("cover") }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()
        assertEquals(1, cancelled)
        val retry = async { requests.get("cover") }
        runCurrent()
        assertEquals(2, loads)
        hold.complete(Unit)
        assertEquals("image", retry.await())
    }

    @Test fun scrollingCannotExceedTheWorkerLimit() = runTest {
        var active = 0
        var peak = 0
        val hold = CompletableDeferred<Unit>()
        val requests = ArtworkRequests<Int, Int>(backgroundScope, parallelism = 2) {
            active++; peak = maxOf(peak, active)
            try { hold.await(); it } finally { active-- }
        }
        val readers = List(40) { key -> async { requests.get(key) } }
        runCurrent()
        assertEquals(2, active)
        hold.complete(Unit)
        readers.forEachIndexed { key, reader -> assertEquals(key, reader.await()) }
        assertEquals(2, peak)
    }

    @Test fun capacitySuspendsAndCancellationAdmitsTheNextVisibleItem() = runTest {
        val started = mutableListOf<Int>()
        val hold = CompletableDeferred<Unit>()
        val requests = ArtworkRequests<Int, Int>(backgroundScope, parallelism = 2, capacity = 2) {
            started += it; hold.await(); it
        }
        val readers = List(3) { key -> async { requests.get(key) } }
        runCurrent()
        assertEquals(listOf(0, 1), started)
        readers[0].cancelAndJoin()
        runCurrent()
        assertEquals(listOf(0, 1, 2), started)
        hold.complete(Unit)
        assertEquals(2, readers[2].await())
        readers[1].await()
    }

    @Test fun clearingCancelsOwnersAndDoesNotPoisonFutureRequests() = runTest {
        val hold = CompletableDeferred<Unit>()
        val requests = ArtworkRequests<Int, Int>(backgroundScope) { hold.await(); it }
        val reader = async { requests.get(1) }
        runCurrent()
        requests.clear()
        runCurrent()
        assertTrue(reader.isCancelled)
        hold.complete(Unit)
        assertEquals(1, requests.get(1))
    }

    @Test fun aCancelledScopeSettlesLazyOwnersAndReleasesAdmission() = runTest {
        val job = SupervisorJob().also { it.cancel() }
        val requests = ArtworkRequests<Int, Int>(CoroutineScope(coroutineContext + job), capacity = 1) { it }
        repeat(3) {
            val reader = async { requests.get(it) }
            runCurrent()
            assertTrue(reader.isCancelled)
        }
    }
}
