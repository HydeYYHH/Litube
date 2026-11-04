package com.hhst.youtubelite.extractor

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class SessionPageRecoveryAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun callTimeoutGetsOneBoundedRetry() {
        val timeouts = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            timeouts += chain.call().timeout().timeoutNanos() / 1_000_000
            if (timeouts.size == 1) throw InterruptedIOException("timeout")
            SessionPageFixture.response(chain.request())
        }.build()
        assertEquals("recovered", SessionPageFixture.read(client))
        assertEquals(2, timeouts.size)
        assertTrue(timeouts[1] in 1..2_000)
    }

    @Test fun handshakeEofRecoversWithoutRefreshingIdentity() {
        var calls = 0
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (++calls == 1) throw SSLHandshakeException("connection closed").apply { initCause(EOFException()) }
            SessionPageFixture.response(chain.request())
        }.build()
        assertEquals("recovered", SessionPageFixture.read(client))
        assertEquals(2, calls)
        assertEquals(requests[0].url, requests[1].url)
        assertEquals(requests[0].headers, requests[1].headers)
    }

    @Test fun certificateFailureIsNotRetried() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            throw SSLHandshakeException("untrusted").apply { initCause(CertificateException()) }
        }.build()
        assertTrue(runCatching { SessionPageFixture.read(client) }.exceptionOrNull() is SSLHandshakeException)
        assertEquals(1, calls)
    }

    @Test fun forbiddenResponseIsNotRetried() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            SessionPageFixture.response(it.request(), 403)
        }.build()
        val failure = runCatching { SessionPageFixture.read(client) }.exceptionOrNull()
        assertTrue(failure is PageHttpException)
        assertEquals(403, (failure as PageHttpException).status)
        assertEquals(1, calls)
    }

    @Test fun cancellationDoesNotReplayTheCapturedRequest() {
        var current = true
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++; current = false
            throw SocketException("Connection reset")
        }.build()
        assertTrue(runCatching { SessionPageFixture.read(client) { current } }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun persistentFailureStopsAfterOneRetry() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++; throw EOFException()
        }.build()
        assertTrue(runCatching { SessionPageFixture.read(client) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun aCallersStricterReadTimeoutIsPreserved() {
        var readTimeout = 0
        val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).addInterceptor {
            readTimeout = it.readTimeoutMillis()
            SessionPageFixture.response(it.request())
        }.build()
        assertEquals("recovered", SessionPageFixture.read(client))
        assertEquals(150, readTimeout)
    }

    @Test fun aStalledFirstResponseRecoversBeforeTheOldTenSecondTimeout() {
        SessionPageFixture.StalledFirstPage().use { server ->
            val started = System.nanoTime()
            assertEquals("recovered", SessionPageFixture.read(server.client()))
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertEquals(2, server.calls.get())
            assertTrue("elapsed=$elapsed", elapsed in 5_500..8_500)
        }
    }
}

/** Tests only the page reader: never publishes fixture configuration or touches cookies. */
internal object SessionPageFixture {
    fun response(request: okhttp3.Request, code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body("recovered".toResponseBody()).build()

    fun read(client: OkHttpClient, current: () -> Boolean = { true }): String {
        val sessions = YoutubeSessionProvider(InstrumentationRegistry.getInstrumentation().targetContext, client)
        val method = YoutubeSessionProvider::class.java.getDeclaredMethod("readPage", String::class.java,
            String::class.java, Map::class.java, Long::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }
        return try {
            method.invoke(sessions, "https://www.youtube.com/watch?v=abcdefghijk", "fixture",
                emptyMap<String, String>(), System.nanoTime() + TimeUnit.SECONDS.toNanos(20), current) as String
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    class StalledFirstPage : Closeable {
        private val server = ServerSocket(0)
        private val release = CountDownLatch(1)
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val calls = AtomicInteger()
        init {
            Thread({
                try {
                    while (!server.isClosed) {
                        val socket = server.accept(); sockets += socket
                        Thread({
                            try {
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) { }
                                if (calls.incrementAndGet() == 1) release.await(20, TimeUnit.SECONDS)
                                else socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nrecovered".toByteArray())
                                    flush()
                                }
                            } catch (_: IOException) { } finally { socket.close() }
                        }, "session-page-response").apply { isDaemon = true; start() }
                    }
                } catch (_: IOException) { }
            }, "session-page-server").apply { isDaemon = true; start() }
        }
        fun client(readTimeoutMs: Long = 10_000): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .url("http://127.0.0.1:${server.localPort}/watch").build()) }.build()
        override fun close() {
            release.countDown(); server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
        }
    }
}
