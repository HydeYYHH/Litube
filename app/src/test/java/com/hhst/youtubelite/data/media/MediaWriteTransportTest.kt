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

class MediaWriteTransportTest {
    @Test fun writesAndTheCompatibilityWriterPreserve408And503WithoutReplayingTheBody() = runBlocking {
        // The same 503 fixture must actually provoke the ordinary HTTP stack's replay.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("original-503"))
            server.enqueue(MockResponse().setBody("unexpected-replay"))
            val response = MediaClient(OkHttpClient()).read { _, http ->
                http.execute(HttpRequest("POST", server.url("/mutation").toString(), body = "{\"write\":true}"))
            }
            assertEquals(200, response.status)
            assertEquals("unexpected-replay", response.body)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("{\"write\":true}", server.takeRequest().body.readUtf8()) }
        }
        for (status in listOf(408, 503)) for (compatibility in listOf(false, true)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("original-$status"))
                server.enqueue(MockResponse().setBody("unexpected-replay"))
                val client = MediaClient(OkHttpClient())
                val request = HttpRequest("POST", server.url("/mutation").toString(), body = "{\"write\":true}")
                val response = if (compatibility) client.forWrites().read { _, http -> http.execute(request) }
                    else client.write { _, http -> http.execute(request) }
                assertEquals(status, response.status)
                assertEquals("original-$status", response.body)
                assertEquals(1, server.requestCount)
                assertEquals("{\"write\":true}", server.takeRequest().body.readUtf8())
            }
        }
    }

    @Test fun anAmbiguousDisconnectAfterTheServerReceivesTheWriteIsNeverReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("warm"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("unexpected-replay"))
            val client = MediaClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build())
            client.read { _, http -> http.execute(HttpRequest("GET", server.url("/warm").toString())) }
            val error = runCatching {
                client.write { _, http -> http.execute(HttpRequest("POST", server.url("/mutation").toString(), body = "write")) }
            }.exceptionOrNull() as MediaException
            assertEquals(RequestFailure.TRANSPORT, error.failure.kind)
            assertEquals(2, server.requestCount)
            assertEquals("/warm", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/mutation", request.path)
            assertEquals("write", request.body.readUtf8())
        }
    }
}
