package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReportConcurrencyTest {
    @Test fun queuedVideoReportsReserveOnlyOneHttpWorker() = runBlocking {
        MediaSession.initialize(MemoryAuthStore())
        MediaSession.rememberVisitor("visitor", MediaSession.snapshot().generation)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maximum = AtomicInteger()
        val requests = ConcurrentLinkedQueue<String>()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            val active = concurrent.incrementAndGet()
            maximum.accumulateAndGet(active, ::maxOf)
            try {
                requests += chain.request().url.toString()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("OK")
                    .body("".toResponseBody()).build()
            } finally { concurrent.decrementAndGet() }
        }.build(), MediaSession::snapshot)
        val reporter = WatchTimeReporter(client, CoroutineScope(Dispatchers.Default))
        val tracking = PlayerTracking("https://www.youtube.com/api/stats/playback?ei=test",
            "https://www.youtube.com/api/stats/watchtime?ei=test")
        try {
            reporter.watch("first", tracking)
            reporter.tick(0, 30_000, true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (id in listOf("second", "third", "fourth")) {
                reporter.watch(id, tracking)
                reporter.tick(0, 30_000, true)
            }
            release.countDown()
            reporter.awaitPendingReport()
            assertEquals(4, requests.size)
            assertEquals(1, maximum.get())
        } finally {
            release.countDown()
            reporter.stop()
            MediaSession.initialize(MemoryAuthStore())
        }
    }
}
