package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PromiseTest {
    @Test fun anAlreadyCancelledOwnerSettlesThePromiseWithoutStartingWork() = runBlocking<Unit> {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        owner.cancel()
        val work = AtomicInteger()
        val listeners = AtomicInteger()
        val notified = CountDownLatch(1)
        val promise = Promise(Unit, owner, autostart = false) { work.incrementAndGet() }
        promise.whenDone { listeners.incrementAndGet(); notified.countDown() }
        val result = runCatching { withTimeout(1_000) { promise.await() } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue(promise.done)
        assertFalse(promise.success)
        assertEquals(0, work.get())
        assertTrue(notified.await(1, TimeUnit.SECONDS))
        assertEquals(1, listeners.get())
    }

    @Test fun unusedOptionalWorkStaysIdleAndConcurrentWaitersShareIt() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val promise = Promise("chapters", CoroutineScope(SupervisorJob() + Dispatchers.IO), autostart = false) {
            calls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        assertFalse(promise.done)
        assertEquals(0, calls.get())
        val first = async(Dispatchers.IO) { promise.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { promise.get() }
        release.countDown()
        assertEquals("chapters", first.await())
        assertEquals("chapters", second.await())
        assertEquals(1, calls.get())
        assertTrue(promise.success)
    }

    @Test fun optionalFailureSettlesWithoutBlockingOtherBranches() {
        val optional = Promise(Unit, autostart = false) { throw IllegalStateException("optional") }
        val media = Promise("media") { }
        assertEquals("media", media.get(2, TimeUnit.SECONDS))
        assertFalse(optional.done)
        assertTrue(runCatching { optional.get(2, TimeUnit.SECONDS) }.isFailure)
        assertTrue(optional.done)
    }
}
