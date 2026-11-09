package com.hhst.youtubelite.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ThumbnailCancellationTest {
    @Test fun interruptedBlockingArtworkDoesNotEscapeTheNotificationWorker() {
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        Thread {
            try {
                Thread.currentThread().interrupt()
                assertNull(YoutubeThumbnail.fetch(null))
                assertTrue(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                Thread.interrupted()
                finished.countDown()
            }
        }.start()
        assertTrue("Artwork worker did not stop", finished.await(5, TimeUnit.SECONDS))
        assertNull(failure.get()?.toString(), failure.get())
    }
}
