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
    @Test fun optionalRepairsAcrossClientsLeaveRoomForForegroundWork() = runBlocking<Unit> {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val clients = List(2) { MediaClient(OkHttpClient()) }
        val repairs = (0 until 6).map { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                clients[index % 2].readBackgroundIdempotent { _, _ ->
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val foreground = (0 until 2).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    clients[index].read { _, _ -> "foreground-$index" }
                }
            }
            assertEquals(listOf("foreground-0", "foreground-1"), withTimeout(2_000) { foreground.awaitAll() })
            repairs.drop(2).forEach { it.cancelAndJoin() }
            release.countDown()
            repairs.take(2).awaitAll()
            assertEquals("reused", withTimeout(2_000) { clients.first().readBackground { _, _ -> "reused" } })
        } finally {
            release.countDown()
            repairs.forEach { it.cancelAndJoin() }
        }
    }

    @Test fun preservesEveryCookieHeaderRegardlessOfHeaderCasing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("set-cookie", "VISITOR_INFO1_LIVE=visitor; Path=/")
                .addHeader("set-cookie", "YSC=session; Path=/"))
            val reply = MediaClient(OkHttpClient()).read { _, http ->
                http.execute(HttpRequest("GET", server.url("/").toString()))
            }
            assertEquals("VISITOR_INFO1_LIVE=visitor; Path=/\nYSC=session; Path=/", reply.header("Set-Cookie"))
        }
    }

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

    @Test fun anIdempotentReadUsesItsFirstAdmissionBeforeLaterSlowReads() = runBlocking {
        val entered = CountDownLatch(3)
        val captured = CountDownLatch(1)
        val finishCapture = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = client()
        val active = (0 until 3).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                reads.read { _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        var later: kotlinx.coroutines.Deferred<Unit>? = null
        val contexts = java.util.concurrent.atomic.AtomicInteger()
        val client = MediaClient(OkHttpClient()) {
            if (contexts.incrementAndGet() == 1) {
                captured.countDown()
                check(finishCapture.await(5, TimeUnit.SECONDS))
            }
            RequestContext()
        }
        val read = async(start = CoroutineStart.UNDISPATCHED) { client.readIdempotent { _, _ -> "ready" } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            later = async(start = CoroutineStart.UNDISPATCHED) {
                reads.read { _, _ -> check(release.await(5, TimeUnit.SECONDS)); Unit }
            }
            finishCapture.countDown()
            assertEquals("ready", withTimeout(1_000) { read.await() })
        } finally {
            finishCapture.countDown()
            release.countDown()
            read.cancel()
            later?.cancel()
            active.forEach { it.cancel() }
        }
    }

    @Test fun acquiringAnIdempotentReadsContextNeverRetries() = runBlocking {
        var contexts = 0
        val failure = RequestError(RequestFailure.TRANSPORT, "transport_connect")
        val client = MediaClient(OkHttpClient()) { contexts++; throw MediaException(failure) }
        val error = runCatching { client.readIdempotent<Unit> { _, _ -> fail("Context acquisition failed") } }
            .exceptionOrNull() as MediaException
        assertEquals(failure, error.failure)
        assertEquals(1, contexts)
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
            val error = runCatching { MediaClient(OkHttpClient()).read { _, http -> http.execute(HttpRequest("GET", server.url("/").toString())) } }
                .exceptionOrNull() as MediaException
            assertEquals("response_too_large", error.failure.code)
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

    @Test fun aNetworkShapedTransportFailureRetriesOnceAndThenSucceeds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            var attempts = 0
            val result = client().readIdempotent { _, http ->
                attempts++
                http.execute(HttpRequest("GET", server.url("/").toString())).body
            }
            assertEquals("{}", result)
            assertEquals(2, attempts)
        }
    }

    @Test fun aSecondTransportFailureSurfacesWithItsCauseAndStopsRetrying() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            var attempts = 0
            val error = runCatching {
                client().readIdempotent { _, http ->
                    attempts++
                    http.execute(HttpRequest("GET", server.url("/").toString())).body
                }
            }.exceptionOrNull() as MediaException
            assertEquals(2, attempts)
            assertEquals(RequestFailure.TRANSPORT, error.failure.kind)
            assertNotNull(error.failure.cause)
        }
    }

    @Test fun authHttpParseAndCancellationFailuresAreNeverRetried() = runBlocking {
        val cases = listOf(
            RequestError(RequestFailure.AUTH, "http_401", 401),
            RequestError(RequestFailure.HTTP, "http_429", 429, 1000L),
            RequestError(RequestFailure.PARSE, "invalid_json"),
            RequestError(RequestFailure.CANCELLED, "cancelled"),
            RequestError(RequestFailure.TRANSPORT, "transport_protocol"),
            RequestError(RequestFailure.TRANSPORT, "transport_failed"))
        for (failure in cases) {
            var attempts = 0
            val error = runCatching {
                client().readIdempotent { _, _ ->
                    attempts++
                    throw MediaException(failure)
                }
            }.exceptionOrNull() as MediaException
            assertEquals("kind=${failure.kind} code=${failure.code}", 1, attempts)
            assertEquals(failure, error.failure)
        }
    }

    @Test fun aSessionChangeDuringARetryWindowStillAborts() = runBlocking {
        val generation = AtomicLong(1)
        val client = MediaClient(OkHttpClient()) { RequestContext(generation = generation.get()) }
        var attempts = 0
        val error = runCatching {
            client.readIdempotent { _, _ ->
                attempts++
                generation.incrementAndGet()
                throw MediaException(RequestError(RequestFailure.TRANSPORT, "transport_connect"))
            }
        }.exceptionOrNull() as MediaException
        assertEquals(1, attempts)
        assertEquals("session_changed", error.failure.code)
    }

    private fun client() = MediaClient(OkHttpClient())
}
