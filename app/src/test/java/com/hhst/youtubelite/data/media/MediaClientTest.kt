package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class MediaClientTest {
    @Test fun saturationSuspendsAdmissionWithoutRunningOnTheCaller() = runBlocking<Unit> {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val client = MediaClient(OkHttpClient())
        val jobs = (0 until 20).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                client.read { _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val overflow = async(start = CoroutineStart.UNDISPATCHED) {
            client.read { _, _ -> Thread.currentThread() }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(overflow.isCompleted)
            release.countDown()
            assertNotSame(caller, withTimeout(5_000) { overflow.await() })
            jobs.awaitAll()
        } finally {
            release.countDown()
            jobs.forEach { it.cancel() }
            overflow.cancel()
        }
    }

    @Test fun cancelledQueuedAndWaitingReadsNeverExecuteAndReleaseAdmission() = runBlocking {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val executed = java.util.concurrent.atomic.AtomicInteger()
        val client = MediaClient(OkHttpClient())
        val active = (0 until 4).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                client.read { _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val pending = (0 until 17).map {
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.read { _, _ -> executed.incrementAndGet() }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.forEach { it.cancelAndJoin() }
            release.countDown()
            active.awaitAll()
            withTimeout(5_000) { client.read { _, _ -> Unit } }
            assertEquals(0, executed.get())
        } finally {
            release.countDown()
            active.forEach { it.cancel() }
            pending.forEach { it.cancel() }
        }
    }

    @Test fun redirectsReachTheCoreWithoutFollowingTheChallenge() = runBlocking {
        MockWebServer().use { server ->
            val challenge = "https://www.google.com/sorry/index?q=x"
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", challenge))
            val result = MediaClient(OkHttpClient()).read { _, http -> http.execute(HttpRequest("GET", server.url("/").toString())) }
            assertEquals(302, result.status)
            assertEquals(challenge, result.header("Location"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancellationAbortsTheActiveHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = MediaClient(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build())
            val request = launch { client.read { _, http -> http.execute(HttpRequest("GET", server.url("/").toString())) } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }
    @Test fun aChangedSessionCannotDeliverThePreviousAccountsResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("old data").setBodyDelay(200, TimeUnit.MILLISECONDS))
            server.start()
            val generation = AtomicLong(1)
            val client = MediaClient(OkHttpClient()) { RequestContext(generation = generation.get()) }
            val request = async { runCatching { client.read { _, http -> http.execute(HttpRequest("GET", server.url("/").toString())) } } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            generation.set(2)
            val failure = request.await().exceptionOrNull() as MediaException
            assertEquals("session_changed", failure.failure.code)
        }
    }
    @Test fun refusesOversizedResponses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(8 * 1024 * 1024 + 1)))
            server.start()
            val result = runCatching { MediaClient(OkHttpClient()).read { _, http -> http.execute(HttpRequest("GET", server.url("/").toString())) } }
            assertEquals("response_too_large", result.exceptionOrNull()?.message)
        }
    }

    @Test fun anAccountChangePreventsTheNextRequestInAMultiRequestOperation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("first"))
            server.start()
            val generation = AtomicLong(1)
            val client = MediaClient(OkHttpClient()) { RequestContext(generation = generation.get()) }
            val error = runCatching {
                client.read { _, http ->
                    http.execute(HttpRequest("GET", server.url("/first").toString()))
                    generation.incrementAndGet()
                    http.execute(HttpRequest("GET", server.url("/second").toString()))
                }
            }.exceptionOrNull()
            assertEquals("session_changed", error?.message)
            assertEquals(1, server.requestCount)
        }
    }
}
