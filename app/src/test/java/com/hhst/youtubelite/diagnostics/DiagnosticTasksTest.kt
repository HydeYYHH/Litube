package com.hhst.youtubelite.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiagnosticTasksTest {
    @Test fun unresponsiveProvidersCannotBlockOrGrowTheWorkerPool() {
        val tasks = DiagnosticTasks()
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            repeat(10) {
                assertNull(tasks.call(25) {
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    true
                })
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        } finally { release.countDown() }
    }

    @Test fun failuresAndExpiredBudgetsRemainUnknown() {
        val tasks = DiagnosticTasks()
        assertNull(tasks.call(100) { error("unavailable") })
        assertNull(tasks.call(0) { error("must not execute") })
        assertEquals("ready", tasks.call(1000) { "ready" })
    }
}
