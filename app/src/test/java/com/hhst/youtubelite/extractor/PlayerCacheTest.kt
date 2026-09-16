package com.hhst.youtubelite.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PlayerCacheTest {

    @Test
    fun overlappingFetches_olderEndDoesNotReleaseNewerWaiter() {
        val cache = PlayerCache()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val waiterSawDone = AtomicBoolean(false)

        val first = Thread {
            cache.withInFlight(ID) {
                firstEntered.countDown()
                releaseFirst.await()
            }
        }
        first.start()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = Thread {
            cache.withInFlight(ID) {
                secondEntered.countDown()
                Thread.sleep(250)
            }
        }
        second.start()
        assertTrue(secondEntered.await(2, TimeUnit.SECONDS))

        val waiter = Thread {
            waiterSawDone.set(cache.await(ID, 5_000))
        }
        waiter.start()
        Thread.sleep(50)

        releaseFirst.countDown()
        first.join(2_000)
        Thread.sleep(50)
        assertFalse(
            "first fetch ending must not count down the second fetch's waiters",
            waiterSawDone.get(),
        )
        second.join(2_000)
        waiter.join(2_000)
        assertTrue(waiterSawDone.get())
    }

    @Test
    fun acceptsBody_rejectsEmptyAndOverTwoMegabytes() {
        val cache = PlayerCache()
        assertFalse(cache.acceptsBody(0))
        assertFalse(cache.acceptsBody(2 * 1024 * 1024 + 1))
        assertTrue(cache.acceptsBody(1))
        assertTrue(cache.acceptsBody(2 * 1024 * 1024))
    }

    private companion object {
        const val ID = "dQw4w9WgXcQ"
    }
}
