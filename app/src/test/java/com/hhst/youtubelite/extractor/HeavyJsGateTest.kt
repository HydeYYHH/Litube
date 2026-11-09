package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeavyJsGateTest {
    private val noopDiagnostics = object : EngineDiagnostics {
        override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) {}
    }
    private val noopTransport = object : ExtractionTransport {
        override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) = throw IOException("offline")
        override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray) = throw IOException("offline")
    }
    private fun context(deadline: Long = Long.MAX_VALUE, playback: Boolean = true, current: () -> Boolean = { true }) =
        ExtractionContext(YoutubeWebSession("test", WebAccount.ANONYMOUS, 0, null, null,
            null, null, "UA", 0, null, "test", JsonObject(emptyMap()), { "" }), noopTransport,
            false, false, playback, "", deadline, current, noopDiagnostics)

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

    @Test fun anAdoptedPreparationMovesAheadOfQueuedDownloads() {
        val gate = HeavyJsGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = CountDownLatch(2)
        val observedPromotion = CountDownLatch(1)
        lateinit var promoted: ExtractionContext
        promoted = context(playback = false, current = {
            if (promoted.playbackPriority) observedPromotion.countDown()
            true
        })
        val nested = promoted.withTimeLimit(10_000)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(3)
        try {
            val owner = pool.submit { gate.run(context()) { entered.countDown(); release.await() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val download = pool.submit { queued.countDown(); gate.run(context(playback = false)) { order += "download" } }
            val preparation = pool.submit { queued.countDown(); gate.run(nested) { order += "playback" } }
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            promoted.prioritizePlayback()
            assertTrue(nested.playbackPriority)
            assertTrue(observedPromotion.await(2, TimeUnit.SECONDS))
            release.countDown()
            owner.get(2, TimeUnit.SECONDS)
            download.get(2, TimeUnit.SECONDS)
            preparation.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("playback", "download"), order)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
