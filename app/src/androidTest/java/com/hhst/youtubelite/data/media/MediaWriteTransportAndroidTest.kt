package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Real loopback sockets only; fixtures never send an account mutation to a public service. */
class MediaWriteTransportAndroidTest {
    @Test fun immediate503DoesNotReplayTheMutation() = preservesStatusWithoutReplay(503)

    @Test fun a408DoesNotReplayTheMutation() = preservesStatusWithoutReplay(408)

    private fun preservesStatusWithoutReplay(status: Int) = runBlocking<Unit> {
        if (status == 503) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("original-503"))
            server.enqueue(MockResponse().setBody("unexpected-replay"))
            val response = MediaClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()).read { _, http ->
                http.execute(HttpRequest("POST", server.url("/mutation").toString(), body = "write-once"))
            }
            assertEquals(200, response.status)
            assertEquals("unexpected-replay", response.body)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("write-once", server.takeRequest().body.readUtf8()) }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("original-$status"))
            server.enqueue(MockResponse().setBody("unexpected-replay"))
            val response = MediaClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()).write { _, http ->
                http.execute(HttpRequest("POST", server.url("/mutation").toString(), body = "write-once"))
            }
            assertEquals(status, response.status)
            assertEquals("original-$status", response.body)
            assertEquals(1, server.requestCount)
            assertEquals("write-once", server.takeRequest().body.readUtf8())
        }
    }

    @Test fun aDisconnectAfterReceivingTheMutationDoesNotSendItAgain() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("warm"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("unexpected-replay"))
            val client = MediaClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build())
            client.read { _, http -> http.execute(HttpRequest("GET", server.url("/warm").toString())) }
            val error = runCatching {
                client.write { _, http -> http.execute(HttpRequest("POST", server.url("/mutation").toString(), body = "write-once")) }
            }.exceptionOrNull() as MediaException
            assertEquals(RequestFailure.TRANSPORT, error.failure.kind)
            assertEquals(2, server.requestCount)
            assertEquals("/warm", server.takeRequest().path)
            assertEquals("write-once", server.takeRequest().body.readUtf8())
        }
    }
}
