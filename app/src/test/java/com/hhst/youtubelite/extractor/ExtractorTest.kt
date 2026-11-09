package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Live playback resolution, opt-in via YTL_NETWORK_TESTS=1: real endpoints block datacenter/VPN egress in ways a connectivity probe cannot see. The JS solver is JVM-unavailable, so only the solver-free direct-URL path is covered here; challenge coverage runs on device. */
class ExtractorTest {

    private lateinit var playback: MscPlayback

    private val diagnostics = object : EngineDiagnostics {
        override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) {}
    }

    @Before
    fun setUp() {
        assumeTrue(
            "set YTL_NETWORK_TESTS=1 to run the live extraction tests",
            System.getenv("YTL_NETWORK_TESTS") == "1",
        )
        assumeTrue("network unavailable", isOnline())
        playback = MscPlayback(
            solver = object : SignatureSolver {
                override fun signatureTimestamp(playerUrl: String, context: ExtractionContext): Int = 0
                override fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext) =
                    throw IOException("solver unavailable on JVM")
            },
            tokens = object : PoTokenMinter {
                override fun mint(request: PoTokenMinter.TokenRequest, context: ExtractionContext) = throw IOException("no minter on JVM")
                override fun invalidate(scope: String) {}
            },
        )
    }

    private fun context(videoId: String): ExtractionContext {
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val session = YoutubeWebSession("live-test", WebAccount.ANONYMOUS, 0, null, null, null, null,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", 0, null, "live-test", JsonObject(emptyMap())) { "" }
        return ExtractionContext(session, object : ExtractionTransport {
            override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse {
                val builder = Request.Builder().url(url)
                headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                return http.newCall(builder.get().build()).execute().use {
                    PageResponse(it.code, it.headers.toMultimap(), it.body?.string().orEmpty(), it.request.url.toString())
                }
            }
            override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                val builder = Request.Builder().url(url)
                headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                return http.newCall(builder.post(body.toRequestBody(null)).build()).execute().use {
                    PageResponse(it.code, it.headers.toMultimap(), it.body?.string().orEmpty(), it.request.url.toString())
                }
            }
        }, false, false, true, "",
            System.nanoTime() + TimeUnit.SECONDS.toNanos(60), { true }, diagnostics)
    }

    @Test
    fun noChapters_YQHsXMglC9A() {
        val resolved = playback.resolve("YQHsXMglC9A", context("YQHsXMglC9A"), demand = null, fresh = false, deferred = emptySet())
        assertEquals("YQHsXMglC9A", resolved.metadata.id)
        assertTrue(resolved.metadata.title.isNotBlank())
        assertNotNull(resolved.metadata.thumbnailUrl)
        assertTrue(resolved.metadata.duration > 0)
        assertFalse(resolved.stream.formats.isEmpty())
        assertTrue(resolved.stream.formats.any { it.url.startsWith("http") })
        assertTrue(resolved.stream.formats.all { it.requestPlan != null })

        val chapters = playback.chapters("YQHsXMglC9A", context("YQHsXMglC9A"))
        assertTrue("YQHsXMglC9A should have no chapters, got ${chapters.size}", chapters.isEmpty())
    }

    @Test
    fun hasChapters_gJrjgg1KVL4() {
        val resolved = playback.resolve("gJrjgg1KVL4", context("gJrjgg1KVL4"), demand = null, fresh = false, deferred = emptySet())
        assertEquals("gJrjgg1KVL4", resolved.metadata.id)
        assertFalse(resolved.stream.formats.isEmpty())

        val chapters = playback.chapters("gJrjgg1KVL4", context("gJrjgg1KVL4"))
        assertFalse("gJrjgg1KVL4 should have chapters", chapters.isEmpty())
        assertTrue(chapters.all { it.startSeconds >= 0 })
        assertTrue(chapters.any { it.title.isNotBlank() })
    }

    private fun isOnline(): Boolean = try {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
            .newCall(
                Request.Builder()
                    .url("https://www.youtube.com/generate_204")
                    .get()
                    .build(),
            )
            .execute()
            .use { it.isSuccessful || it.code in 200..399 }
    } catch (_: Exception) {
        false
    }
}
