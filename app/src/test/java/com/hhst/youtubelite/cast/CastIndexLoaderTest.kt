package com.hhst.youtubelite.cast

import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CastIndexLoaderTest {
    @Test fun evenOneWorkerCanCompleteBothReadsWithoutStarvation() = runBlocking {
        val workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        val loader = CastIndexLoader<String>(workers)
        val events = Channel<Triple<String?, String?, Boolean>>(Channel.UNLIMITED)
        try {
            loader.load({ "video" }, { "audio" }) { v, a, upgrade -> events.trySend(Triple(v, a, upgrade)) }
            assertEquals(Triple("video", "audio", false), withTimeout(2_000) { events.receive() })
        } finally { loader.cancel(); workers.shutdownNow() }
    }

    @Test fun aSoftTimeoutDoesNotCancelTheLaterUpgrade() = runBlocking {
        val workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))
        val loader = CastIndexLoader<String>(workers, initialTimeoutMs = 50, upgradeTimeoutMs = 2_000)
        val release = CountDownLatch(1)
        val events = Channel<Triple<String?, String?, Boolean>>(Channel.UNLIMITED)
        try {
            loader.load({ release.await(); "video" }, { release.await(); "audio" }) { v, a, upgrade -> events.trySend(Triple(v, a, upgrade)) }
            assertEquals(Triple<String?, String?, Boolean>(null, null, false), withTimeout(2_000) { events.receive() })
            release.countDown()
            assertEquals(Triple("video", "audio", true), withTimeout(2_000) { events.receive() })
        } finally { release.countDown(); loader.cancel(); workers.shutdownNow() }
    }

    @Test fun replacementInterruptsObsoleteReadsAndOnlyPublishesTheNewMedia() = runBlocking {
        val workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))
        val loader = CastIndexLoader<String>(workers)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val events = Channel<Triple<String?, String?, Boolean>>(Channel.UNLIMITED)
        try {
            fun old(): String { entered.countDown(); release.await(); return "old" }
            loader.load(::old, ::old) { v, a, upgrade -> events.trySend(Triple(v, a, upgrade)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            loader.load({ "new-video" }, { "new-audio" }) { v, a, upgrade -> events.trySend(Triple(v, a, upgrade)) }
            assertEquals(Triple("new-video", "new-audio", false), withTimeout(2_000) { events.receive() })
            assertTrue(events.tryReceive().isFailure)
        } finally { release.countDown(); loader.cancel(); workers.shutdownNow() }
    }
}
