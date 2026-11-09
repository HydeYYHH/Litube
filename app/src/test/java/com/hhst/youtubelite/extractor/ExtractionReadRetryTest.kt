package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class ExtractionReadRetryTest {
    private val player = "https://www.youtube.com/youtubei/v1/player?key=secret"
    private val script = "https://www.youtube.com/s/player/test/base.js"

    @Test fun transientReadFailureRecoversBeforePublishingTheResult() {
        var calls = 0
        val events = mutableListOf<String>()
        val task = context(events) {
            if (++calls == 1) throw EOFException("unexpected end of stream on $player")
            PageResponse(200, emptyMap(), "ok", player)
        }
        assertEquals("ok", task.post(player, emptyMap(), "same-body".toByteArray()).responseBody)
        assertEquals(2, calls)
        assertEquals(1, events.count { it.startsWith("request-retry:player:") })
        assertTrue(events.any { it.startsWith("request-failed:player:POST:transport_io:EOFException") })
        assertTrue(events.none { it.contains("secret") || it.contains("https://") })
    }

    @Test fun aPersistentFailureGetsOnlyOneExtraNetworkAttempt() {
        var calls = 0
        val failure = SocketException("Connection reset")
        val task = context { calls++; throw failure }
        assertSame(failure, runCatching { task.get(script, emptyMap()) }.exceptionOrNull())
        assertEquals(2, calls)
    }

    @Test fun aRealSocketDisconnectRecoversOnTheNextHttpCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setBody("recovered"))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val requestUrl = server.url("/player").newBuilder().host("127.0.0.1").build()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            var calls = 0
            val task = context {
                calls++
                client.newCall(Request.Builder().url(requestUrl).build()).execute().use { response ->
                    PageResponse(response.code, emptyMap(), response.body!!.string(), player)
                }
            }
            assertEquals("recovered", task.post(player, emptyMap(), byteArrayOf()).responseBody)
            assertEquals(2, calls)
        }
    }

    @Test fun nestedPhasesShareOneRetryAllowance() {
        var calls = 0
        val task = context {
            calls++
            if (calls != 2) throw EOFException()
            PageResponse(200, emptyMap(), "ok", script)
        }
        task.withTimeLimit(5_000).get(script, emptyMap())
        assertTrue(runCatching { task.post(player, emptyMap(), byteArrayOf()) }.isFailure)
        assertEquals(3, calls)
    }

    @Test fun recoveryKeepsTheCapturedSessionAndCapsItsNetworkDeadline() {
        val attempts = mutableListOf<ExtractionContext>()
        val task = context {
            attempts += it
            if (attempts.size == 1) throw EOFException()
            PageResponse(200, emptyMap(), "ok", player)
        }
        task.post(player, emptyMap(), byteArrayOf())
        assertEquals(2, attempts.size)
        assertSame(task.session, attempts[1].session)
        assertTrue(attempts[1].deadlineNanos < task.deadlineNanos)
        assertTrue(attempts[1].remainingMillis() in 1..2_000)
    }

    @Test fun sessionChangeAbortsWithoutReplayingTheCapturedRequest() {
        var current = true
        var calls = 0
        val task = context(current = { current }) {
            calls++
            current = false
            throw EOFException()
        }
        val failure = runCatching { task.post(player, emptyMap(), byteArrayOf()) }.exceptionOrNull()
        assertEquals("SESSION_CHANGED_OR_CANCELLED", failure?.message)
        assertEquals(1, calls)
    }

    @Test fun anExpiredBudgetCannotStartAnotherAttempt() {
        var calls = 0
        val task = context {
            calls++
            throw EOFException()
        }
        val child = task.withTimeLimit(0)
        assertEquals("EXTRACTION_DEADLINE", runCatching { child.get(script, emptyMap()) }.exceptionOrNull()?.message)
        assertEquals(0, calls)
    }

    @Test fun insufficientRemainingBudgetKeepsTheOriginalFailure() {
        var calls = 0
        val task = context {
            calls++
            throw EOFException()
        }.withTimeLimit(400)
        assertTrue(runCatching { task.get(script, emptyMap()) }.exceptionOrNull() is EOFException)
        assertEquals(1, calls)
    }

    @Test fun httpDenialsAreReturnedToTheExistingClientRecovery() {
        for (status in listOf(401, 403, 429, 500)) {
            var calls = 0
            val task = context { calls++; PageResponse(status, emptyMap(), "denied", player) }
            assertEquals(status, task.post(player, emptyMap(), byteArrayOf()).responseCode)
            assertEquals(1, calls)
        }
    }

    @Test fun mutationsAndTokenMintingAreNeverReplayed() {
        for (url in listOf("https://www.youtube.com/youtubei/v1/like/like",
            "https://www.youtube.com/api/jnn/v1/GenerateIT", "https://other.test/youtubei/v1/player")) {
            var calls = 0
            val task = context { calls++; throw EOFException() }
            assertTrue(runCatching { task.post(url, emptyMap(), byteArrayOf()) }.isFailure)
            assertEquals(1, calls)
        }
    }

    @Test fun policyErrorsCancellationAndCertificateFailuresAreNotRetried() {
        val certificate = SSLHandshakeException("certificate rejected").apply { initCause(CertificateException()) }
        for (error in listOf(IOException("RESPONSE_TOO_LARGE"), IOException("EMPTY_RESPONSE"),
            InterruptedIOException("interrupted"), SocketException("canceled"), certificate,
            SSLHandshakeException("Received fatal alert: protocol_version"))) {
            var calls = 0
            val task = context { calls++; throw error }
            assertSame(error, runCatching { task.get(script, emptyMap()) }.exceptionOrNull())
            assertEquals(1, calls)
        }
    }

    @Test fun wrappedTlsEofCanRecover() {
        val failure = IOException("wrapped", SSLHandshakeException("connection closed").apply { initCause(EOFException()) })
        assertTrue(ExtractionReadRetry.allowed("POST", player, failure))
    }

    private fun context(events: MutableList<String> = mutableListOf(), current: () -> Boolean = { true },
        reply: (ExtractionContext) -> PageResponse): ExtractionContext {
        val session = YoutubeWebSession("session", WebAccount.ANONYMOUS, 0, null, null, null, "visitor",
            "agent", 0, script, "test", JsonObject(emptyMap())) { "" }
        val transport = object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) = reply(context)
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray) = reply(context)
        }
        return ExtractionContext(session, transport, false, false, true, "",
            System.nanoTime() + TimeUnit.SECONDS.toNanos(10), current, object : EngineDiagnostics {
                override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) {
                    events += "$stage:$profile:$detail"
                }
            })
    }
}
