package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class MediaClientTest {
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
