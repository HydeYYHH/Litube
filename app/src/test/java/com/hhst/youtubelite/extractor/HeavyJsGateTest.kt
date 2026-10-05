package com.hhst.youtubelite.extractor

import com.grack.nanojson.JsonObject
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeavyJsGateTest {
    private fun context(deadline: Long = Long.MAX_VALUE, playback: Boolean = true, current: () -> Boolean = { true }) =
        ExtractionContext(YoutubeSession("test", YoutubeSession.Account.ANONYMOUS, 0, null, null,
            null, null, "UA", 0, null, "test", JsonObject(), { "" }), null, null, null,
            false, false, deadline, current, { _, _, _, _, _ -> }).withPlaybackPriority(playback)

    @Test fun queuedDeadlineExpiresBeforeTheOwnerReleases() {
        val gate = HeavyJsGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit { gate.run(context(playback = false)) { entered.countDown(); release.await() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val waiter = pool.submit<IOException> {
                try {
                    gate.run(context(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50))) { fail("expired work ran") }
                    throw AssertionError("deadline was ignored")
                } catch (error: IOException) { error }
            }
            assertEquals("EXTRACTION_DEADLINE", waiter.get(1, TimeUnit.SECONDS).message)
            assertEquals(1L, release.count)
            assertFalse(gate.tryRunIdle { fail("maintenance entered a busy gate") })
            release.countDown()
            owner.get(2, TimeUnit.SECONDS)
            assertTrue(gate.tryRunIdle { })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun queuedPlaybackPrecedesEarlierDownloadWork() {
        val gate = HeavyJsGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val owner = Thread { gate.run(context(playback = false)) { entered.countDown(); release.await() } }
        val download = Thread { gate.run(context(playback = false)) { order += "download" } }
        val playback = Thread { gate.run(context()) { order += "playback" } }
        fun awaitQueued(thread: Thread) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < until) Thread.sleep(1)
            assertEquals(Thread.State.TIMED_WAITING, thread.state)
        }
        try {
            owner.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            download.start(); awaitQueued(download)
            playback.start(); awaitQueued(playback)
            release.countDown()
            download.join(2_000); playback.join(2_000)
            assertEquals(listOf("playback", "download"), order)
        } finally { release.countDown(); owner.join(2_000); download.interrupt(); playback.interrupt() }
    }
}
