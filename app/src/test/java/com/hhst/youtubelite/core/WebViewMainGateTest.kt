package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebViewMainGateTest {

    @Test
    fun checkingGate_failsIfMintWorkRunsOffAllowedDispatcher() {
        val allowed = Thread.currentThread()
        val gate = CheckingWebViewMainGate(allowed)
        assertEquals("ok", gate.run { "ok" })
        val error = AtomicReference<Throwable>()
        val t = Thread {
            error.set(runCatching { gate.run { "mint" } }.exceptionOrNull())
        }
        t.start()
        t.join(5_000)
        assertFalse(t.isAlive)
        val thrown = error.get()
        assertTrue(thrown is IllegalStateException)
        assertTrue(
            thrown!!.message!!.contains("PoToken mint invoked off the allowed dispatcher"),
        )
    }

    @Test
    fun hoppingGate_fromBackground_runsMintOnAllowedExecutor() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "po-token-allowed")
        }
        val gate = HoppingWebViewMainGate(executor)
        try {
            val seen = AtomicReference<String>()
            val latch = CountDownLatch(1)
            Thread({
                gate.run {
                    seen.set(Thread.currentThread().name)
                }
                latch.countDown()
            }, "download-io").start()
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("po-token-allowed", seen.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun hoppedEval_fromIo_keepsPotokenOccupancyUntilWorkReturns() {
        val clock = RecordingWebViewTimerClock()
        val occ = WebViewTimerOccupancy(clock)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "main-standin")
        }
        val gate = HoppingWebViewMainGate(executor)
        try {
            val browser = occ.acquire(WebViewTimerOwner.BROWSER)
            val evalThread = AtomicReference<String>()
            val latch = CountDownLatch(1)
            Thread({
                occ.withOwner(WebViewTimerOwner.POTOKEN) {
                    browser.release()
                    gate.run {
                        evalThread.set(Thread.currentThread().name)
                        assertFalse("must not pauseTimers while PoToken is evaluating", occ.isPaused)
                        assertTrue(clock.events.isEmpty())
                    }
                }
                latch.countDown()
            }, "download-io").start()
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("main-standin", evalThread.get())
            assertEquals(listOf("pause"), clock.events)
            assertTrue(occ.isPaused)
        } finally {
            executor.shutdownNow()
        }
    }
}
