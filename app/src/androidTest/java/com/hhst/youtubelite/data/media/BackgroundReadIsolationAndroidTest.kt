package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Slow optional repairs cannot make navigation or a write wait for their sockets. */
class BackgroundReadIsolationAndroidTest {
    @Test fun cancellingActiveSocketsReturnsBothBackgroundSlots() = runBlocking<Unit> {
        val requestCounts = mutableListOf<Int>()
        repeat(5) {
            MockWebServer().use { server ->
                val oldRequests = CountDownLatch(2)
                val newRequests = CountDownLatch(2)
                val release = CountDownLatch(1)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/old/") == true) {
                            oldRequests.countDown()
                            return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                        newRequests.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return MockResponse().setBody("ready")
                    }
                }
                val client = MediaClient(OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build())
                val old = (0 until 2).map { index ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        client.readBackground { _, http -> http.execute(HttpRequest("GET", server.url("/old/$index").toString())) }
                    }
                }
                var fresh = emptyList<Deferred<Unit>>()
                try {
                    assertTrue(oldRequests.await(5, TimeUnit.SECONDS))
                    old.forEach { it.cancelAndJoin() }
                    fresh = (0 until 2).map { index ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            client.readBackgroundIdempotent { _, http ->
                                assertEquals(200, http.execute(HttpRequest("GET", server.url("/new/$index").toString())).status)
                            }
                        }
                    }
                    assertTrue("Cancellation retained background admission", newRequests.await(5, TimeUnit.SECONDS))
                    release.countDown()
                    withTimeout(5_000) { fresh.awaitAll() }
                    assertEquals(4, server.requestCount)
                    requestCounts += server.requestCount
                } finally {
                    release.countDown()
                    old.forEach { it.cancelAndJoin() }
                    fresh.forEach { it.cancelAndJoin() }
                }
            }
        }
        DeviceEvidence.writeJson("msc-background-active-cancellation.json",
            """{"rounds":5,"requestsPerRound":$requestCounts,"bothSlotsRestored":true}""")
    }

    @Test fun foregroundReadsAndWritesFinishWhileRepairsRemainBlocked() = runBlocking<Unit> {
        val requestCounts = mutableListOf<Int>()
        repeat(5) {
            MockWebServer().use { server ->
                val entered = CountDownLatch(2)
                val release = CountDownLatch(1)
                val repairRequests = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/repair/") == true) {
                            repairRequests.incrementAndGet()
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return MockResponse().setBody("ready")
                    }
                }
                val clients = List(2) { MediaClient(OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()) }
                val repairs = (0 until 6).map { index ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        clients[index % 2].readBackgroundIdempotent { _, http ->
                            http.execute(HttpRequest("GET", server.url("/repair/$index").toString()))
                        }
                    }
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val foregroundRead = async(start = CoroutineStart.UNDISPATCHED) {
                        clients.first().read { _, http -> http.execute(HttpRequest("GET", server.url("/page").toString())) }
                    }
                    val foregroundWrite = async(start = CoroutineStart.UNDISPATCHED) {
                        clients.last().write { _, http -> http.execute(HttpRequest("POST", server.url("/write").toString(), body = "once")) }
                    }
                    withTimeout(5_000) {
                        assertEquals(200, foregroundRead.await().status)
                        assertEquals(200, foregroundWrite.await().status)
                    }
                    assertEquals("Background reads occupied reserved foreground workers", 2, repairRequests.get())
                    repairs.drop(2).forEach { it.cancelAndJoin() }
                    release.countDown()
                    repairs.take(2).awaitAll()
                    clients.first().readBackground { _, http ->
                        assertEquals(200, http.execute(HttpRequest("GET", server.url("/after-cancellation").toString())).status)
                    }
                    assertEquals(5, server.requestCount)
                    requestCounts += server.requestCount
                } finally {
                    release.countDown()
                    repairs.forEach { it.cancelAndJoin() }
                }
            }
        }
        DeviceEvidence.writeJson("msc-background-foreground-isolation.json",
            """{"rounds":5,"requestsPerRound":$requestCounts,"foregroundCompletedBeforeRepairRelease":true}""")
    }
}
