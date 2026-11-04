@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.extractor.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production cache and diagnostic wrappers rather than the HTTP source alone. */
class PlannedStartupAndroidTest {
    @Test fun cachedDiagnosedInitializationRetainsItsPlanAndConnectionBudget() = exercise()

    @Test fun stalledTlsInitializationUsesTheSameBudget() = exercise(tls = true)

    @Test fun legacyInitializationCannotBypassTheBudget() = exercise(planned = false)

    @Test fun parsedDashInitializationReusesThePrefetchedCacheEntry() {
        val bytes = "x".repeat(797)
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(bytes))
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }
        val http = OkHttpClient()
        val session = YoutubeWebSession("cached-init", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap()), { "" })
        val plan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HTTPS, MediaRange.QUERY)
        val url = server.url("/videoplayback?itag=135&sig=a%2Bb%2Fc%3D&sparams=expire%2Cid%2Citag").toString()
        val format = Format(url = url, requestPlan = plan, videoOnly = true, mimeType = "video/mp4",
            codec = "avc1.4d401e", initStart = 0, initEnd = 99, indexStart = 100, indexEnd = 796)
        val sources = PlayerDataSource.create(InstrumentationRegistry.getInstrumentation().targetContext,
            http, YoutubeMediaRequests({ true }, ExtractionDiagnostics()))
        fun read(uri: String): ByteArray = androidx.media3.datasource.DataSourceInputStream(
            sources.withDiagnostics(DiagnosticContext(videoId = "cachedInit")).formats(listOf(format)).createDataSource(),
            DataSpec.Builder().setUri(uri).setLength(797).build(),
        ).use { it.readBytes() }
        try {
            assertArrayEquals(bytes.toByteArray(), read(url))
            val parsedUrl = parseDashBaseUrl(DashManifestFactory.dataUri(DashManifestFactory.build(format, 1_000)))
            assertArrayEquals(bytes.toByteArray(), read(parsedUrl))
            assertEquals("Playback must reuse the prefetched init without another TLS connection", 1, server.requestCount)
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun exercise(tls: Boolean = false, planned: Boolean = true) {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(if (tls) SocketPolicy.STALL_SOCKET_AT_START else SocketPolicy.NO_RESPONSE))
            start()
        }
        val http = OkHttpClient()
        val session = YoutubeWebSession("planned-init", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap()), { "" })
        val plan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HTTPS, MediaRange.HEADER)
        val encodedPath = "/videoplayback?itag=135&sig=a%2Bb%2Fc%3D&sparams=expire%2Cid%2Citag"
        val url = server.url(encodedPath).newBuilder()
            .scheme(if (tls) "https" else "http").build().toString()
        val format = Format(url = url, requestPlan = plan.takeIf { planned }, videoOnly = true,
            mimeType = "video/mp4", codec = "avc1.4d401e",
            initStart = 0, initEnd = 99, indexStart = 100, indexEnd = 796)
        val parsedUrl = parseDashBaseUrl(DashManifestFactory.dataUri(DashManifestFactory.build(format, 1_000)))
        assertEquals("The DASH round trip must preserve the exact plan/cache lookup key", url, parsedUrl)
        val source = PlayerDataSource.create(InstrumentationRegistry.getInstrumentation().targetContext,
            http, YoutubeMediaRequests({ true }, ExtractionDiagnostics()))
            .withDiagnostics(DiagnosticContext(videoId = "plannedInit"))
            .formats(listOf(format))
            .createDataSource()
        val began = System.nanoTime()
        try {
            source.open(DataSpec.Builder().setUri(parsedUrl).setLength(797).build())
            fail("A silent initialization must not use the full media budget")
        } catch (expected: IOException) {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            assertTrue("Production wrappers lost the startup budget: $elapsed", elapsed in 5_000..10_000)
            assertEquals(PlaybackRecovery.Reason.TRANSPORT_FAILED, PlaybackRecovery.classify(expected))
            if (!tls) {
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                if (planned) assertEquals(plan.userAgent, request.getHeader("User-Agent"))
                assertEquals(if (planned) "$encodedPath&range=0-796" else encodedPath, request.path)
                assertEquals(1, server.requestCount)
            }
        } finally {
            source.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
