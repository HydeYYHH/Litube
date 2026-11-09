package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MediaClientPreSendRecoveryTest {
    @Test fun unsentCreationCanRecoverFromAConnectFailure() = runBlocking {
        val calls = AtomicInteger()
        val transmissions = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) throw ConnectException("refused")
            chain.request().body!!.writeTo(Buffer())
            transmissions.incrementAndGet()
            success(chain.request())
        }.build())

        val reply = client.write { _, http -> http.execute(creation()) }

        assertEquals(200, reply.status)
        assertEquals(2, calls.get())
        assertEquals(1, transmissions.get())
    }

    @Test fun aPersistentDnsFailureHasOnlyOneRecoveryAttempt() = runBlocking {
        val calls = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw UnknownHostException()
        }.build())
        try {
            client.write { _, http -> http.execute(creation()) }
            fail("DNS failure must be returned")
        } catch (error: MediaException) {
            assertEquals(RequestFailure.TRANSPORT, error.failure.kind)
        }
        assertEquals(2, calls.get())
    }

    @Test fun aFailureAfterStartingTheBodyNeverRepeatsCreation() = runBlocking {
        val calls = AtomicInteger()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            chain.request().body!!.writeTo(Buffer())
            throw ConnectException("connection reset")
        }.build())
        try {
            client.write { _, http -> http.execute(creation()) }
            fail("A sent request remains uncertain")
        } catch (error: MediaException) {
            assertEquals(RequestFailure.TRANSPORT, error.failure.kind)
        }
        assertEquals(1, calls.get())
    }

    @Test fun aSessionChangeStopsRecoveryBeforeTheNextCall() = runBlocking {
        val calls = AtomicInteger()
        val generation = AtomicLong(1)
        val client = MediaClient(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            generation.incrementAndGet()
            throw ConnectException("refused")
        }.build()) { RequestContext(generation = generation.get()) }
        try {
            client.write { _, http -> http.execute(creation()) }
            fail("Old session must stop")
        } catch (error: MediaException) {
            assertEquals(RequestFailure.AUTH, error.failure.kind)
            assertEquals("session_changed", error.failure.code)
        }
        assertEquals(1, calls.get())
    }

    private fun creation() = HttpRequest("POST", "https://www.youtube.com/create", body = "{\"title\":\"one\"}")

    private fun success(request: okhttp3.Request) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
}
