@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaOpenTimeoutAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun defaultBudgetBoundsSeekBeforeDelayedHeaders() {
        withServer { server, http ->
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-131171/131172")
                .setBody(Buffer().write(ByteArray(131_072)))
                .setHeadersDelay(9, TimeUnit.SECONDS))
            val source = YoutubeHttpDataSource.Factory(http, "fixture-UA").createDataSource()
            val began = System.nanoTime()
            var outcome = "headers_returned"
            try {
                source.open(DataSpec.Builder().setUri(server.url("/default-seek.mp4").toString())
                    .setPosition(100).setLength(131_072).build())
                fail("The default seek open budget did not stop delayed headers")
            } catch (failure: IOException) {
                outcome = "open_timeout"
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                assertTrue("Default seek open took ${elapsed}ms", elapsed in 5_000..8_000)
                assertTrue(failure.causes().any { it is SocketTimeoutException && it.message == "MEDIA_OPEN_TIMEOUT" })
                assertEquals(1, server.requestCount)
            } finally {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "seek-open-budget.json")
                    .writeText("{\"elapsedMs\":$elapsed,\"outcome\":\"$outcome\"}")
                source.close()
            }
        }
    }

    @Test fun aLargeSeekRequestWithoutHeadersHasOneBoundedOpenAttempt() {
        withServer { server, http ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val spec = DataSpec.Builder().setUri(server.url("/seek.mp4").toString())
                .setPosition(100).setLength(131_072).build()
            val source = YoutubeHttpDataSource.Factory(http, "fixture-UA").setOpenTimeoutMs(300).createDataSource()
            val began = System.nanoTime()
            try {
                source.open(spec)
                fail("A nonzero, large seek request must not wait for the full media timeout")
            } catch (failure: IOException) {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                assertTrue("Open timeout took ${elapsed}ms", elapsed in 200..2_000)
                assertTrue(failure.causes().any { it is SocketTimeoutException && it.message == "MEDIA_OPEN_TIMEOUT" })
                assertEquals(PlaybackRecovery.Reason.TRANSPORT_FAILED, PlaybackRecovery.classify(failure))
                val load = info(spec, failure)
                val policy = PlaybackLoadErrorPolicy()
                assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(load))
                assertNull(policy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(1, 0, 3, 0), load))
                assertEquals(1, server.requestCount)
            } finally { source.close() }
        }
    }

    @Test fun aHealthyMediaBodyCanOutliveTheOpenBudget() {
        withServer { server, http ->
            val body = ByteArray(131_072) { (it % 251).toByte() }
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 100-131171/131172").setBody(Buffer().write(body))
                .throttleBody(8192, 60, TimeUnit.MILLISECONDS))
            val source = YoutubeHttpDataSource.Factory(http, "fixture-UA").setOpenTimeoutMs(300).createDataSource()
            val received = java.io.ByteArrayOutputStream()
            val began = System.nanoTime()
            try {
                source.open(DataSpec.Builder().setUri(server.url("/slow-body.mp4").toString())
                    .setPosition(100).setLength(body.size.toLong()).build())
                val buffer = ByteArray(8192)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    received.write(buffer, 0, read)
                }
                assertArrayEquals(body, received.toByteArray())
                assertTrue("Fixture did not outlive the open budget", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) > 600)
            } finally { source.close() }
        }
    }

    @Test fun explicitCancellationKeepsItsCancellationCause() {
        withServer { server, http ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = http.newCall(Request.Builder().url(server.url("/cancel")).build())
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<IOException> {
                    try { MediaOpenTimeout.execute(call, 2_000).close(); throw AssertionError("Expected cancellation") }
                    catch (failure: IOException) { failure }
                }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                call.cancel()
                val failure = result.get(2, TimeUnit.SECONDS)
                assertTrue(call.isCanceled())
                assertFalse(failure.causes().any { it.message == "MEDIA_OPEN_TIMEOUT" })
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun readFailuresAfterProgressKeepTheNormalRetryPolicy() {
        val spec = DataSpec(Uri.parse("https://media.invalid/seek"))
        val load = info(spec, IOException("wrapped", SocketTimeoutException("MEDIA_OPEN_TIMEOUT")), 1)
        assertEquals(DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(load), PlaybackLoadErrorPolicy().getRetryDelayMsFor(load))
    }

    @Test fun delayedInitializationHeadersNotifyOnceWithoutRetrying() {
        withServer { server, http ->
            server.enqueue(MockResponse().setBody("initialization").setHeadersDelay(750, TimeUnit.MILLISECONDS))
            val notifications = java.util.concurrent.atomic.AtomicInteger()
            val source = YoutubeHttpDataSource.Factory(http, "fixture-UA")
                .setSlowInitialReadListener { notifications.incrementAndGet() }.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(server.url("/init").toString()).setLength(14).build())
                assertEquals(1, notifications.get())
                assertEquals(1, server.requestCount)
            } finally { source.close() }
        }
    }

    @Test fun slowBodyAfterPromptHeadersDoesNotNotify() {
        withServer { server, http ->
            server.enqueue(MockResponse().setBody("initialization").setBodyDelay(750, TimeUnit.MILLISECONDS))
            val notifications = java.util.concurrent.atomic.AtomicInteger()
            val source = YoutubeHttpDataSource.Factory(http, "fixture-UA")
                .setSlowInitialReadListener { notifications.incrementAndGet() }.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(server.url("/init").toString()).setLength(14).build())
                assertEquals(14, source.read(ByteArray(14), 0, 14))
                assertEquals(0, notifications.get())
            } finally { source.close() }
        }
    }

    @Test fun speculativeReadsKeepTheirBudgetWithoutStartingAnotherPreparation() {
        withServer { server, http ->
            server.enqueue(MockResponse().setBody("initialization").setHeadersDelay(750, TimeUnit.MILLISECONDS))
            val notifications = java.util.concurrent.atomic.AtomicInteger()
            val source = YoutubeHttpDataSource.Factory(http, "fixture-UA")
                .setSlowInitialReadListener { notifications.incrementAndGet() }.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(server.url("/init").toString()).setLength(14)
                    .setCustomData(PrefetchDeadline(android.os.SystemClock.elapsedRealtime()+2_000)).build())
                assertEquals(0, notifications.get())
            } finally { source.close() }
        }
    }

    private fun info(spec: DataSpec, failure: IOException, bytes: Long = 0) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1, spec, spec.uri, emptyMap(), 0, 300, bytes), MediaLoadData(C.DATA_TYPE_MEDIA), failure, 2,
    )

    private fun Throwable.causes() = generateSequence(this) { it.cause }.take(16)

    private inline fun withServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val server = MockWebServer().apply { start() }
        val http = OkHttpClient()
        try { block(server, http) } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
