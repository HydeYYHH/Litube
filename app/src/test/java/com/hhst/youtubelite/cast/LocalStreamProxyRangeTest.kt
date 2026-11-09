@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.cast

import android.content.ContextWrapper
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalStreamProxyRangeTest {

    private val unset: Long = -1L // C.LENGTH_UNSET as a long

    @Test
    fun parseRange_concreteRange() {
        assertArrayEquals(longArrayOf(0, 99), LocalStreamProxy.parseRange("bytes=0-99", unset))
        assertArrayEquals(longArrayOf(100, 199), LocalStreamProxy.parseRange("bytes=100-199", 500))
    }

    @Test
    fun parseRange_openEnded_reportsUnset() {
        assertArrayEquals(longArrayOf(100, unset), LocalStreamProxy.parseRange("bytes=100-", unset))
        assertArrayEquals(longArrayOf(0, unset), LocalStreamProxy.parseRange("bytes=0-", unset))
    }

    @Test
    fun parseRange_suffixRange_needsKnownLength() {
        assertArrayEquals(longArrayOf(950, 999), LocalStreamProxy.parseRange("bytes=-50", 1000))
        // Without a total a suffix range is unsatisfiable, not 0-N.
        assertNull(LocalStreamProxy.parseRange("bytes=-50", unset))
        assertNull(LocalStreamProxy.parseRange("bytes=-50", 0))
        assertNull(LocalStreamProxy.parseRange("bytes=-0", 100))
    }

    @Test
    fun parseRange_malformed() {
        assertNull(LocalStreamProxy.parseRange("bytes=abc", unset))
        assertNull(LocalStreamProxy.parseRange("bytes", unset))
        assertNull(LocalStreamProxy.parseRange("", unset))
        // Multi-range specs are rejected, not faked into an open-ended 206.
        assertNull(LocalStreamProxy.parseRange("bytes=0-1,5-6", unset))
        // end < start is syntactically parseable; the caller rejects it (400/416).
        assertArrayEquals(longArrayOf(5, 2), LocalStreamProxy.parseRange("bytes=5-2", unset))
    }

    @Test
    fun acceptsGeneration_requiresExactConfigId() {
        assertTrue(LocalStreamProxy.acceptsGeneration(4L, 4L))
        assertFalse(LocalStreamProxy.acceptsGeneration(3L, 4L))
        assertFalse(LocalStreamProxy.acceptsGeneration(null, 4L))
        assertFalse(LocalStreamProxy.acceptsGeneration(4L, 5L))
    }

    @Test
    fun chunkMaxBytes_byUserAgent() {
        val receiver = "Mozilla/5.0 (CrKey armv7l 1.56.500000) Chromecast/131"
        val browser = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131"
        assertEquals(256 * 1024, LocalStreamProxy.chunkMaxBytes(receiver))
        assertEquals(2 * 1024 * 1024, LocalStreamProxy.chunkMaxBytes(browser))
        assertEquals(2 * 1024 * 1024, LocalStreamProxy.chunkMaxBytes(null))
    }

    @Test
    fun backfill_staleConfigId_ignoredAfterSameVideoReconfigure() {
        val proxy = newProxy()
        configureVideo(proxy, contentLength = 0L)
        val staleEntry = streamEntry(proxy, "v")
        // Same id/URL and still-unknown total: only configId can reject the stale request.
        configureVideo(proxy, contentLength = 0L)
        backfill(proxy, staleEntry, chunkStart = 0L, resolved = 900L)
        assertEquals(
            "old config's resolved length must not claim the new config's stream",
            0L,
            entryLong(streamEntry(proxy, "v"), "contentLength"),
        )
    }

    @Test
    fun backfill_currentConfigId_storesTotal() {
        val proxy = newProxy()
        configureVideo(proxy, contentLength = 0L)
        backfill(proxy, streamEntry(proxy, "v"), chunkStart = 10L, resolved = 90L)
        assertEquals(100L, entryLong(streamEntry(proxy, "v"), "contentLength"))
    }

    @Test
    fun asHeadResponse_keepsGetContentLengthWithEmptyBody() {
        val body = "abcdef".toByteArray()
        val get = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/dash+xml",
            ByteArrayInputStream(body),
            body.size.toLong(),
        )
        val head = LocalStreamProxy.asHeadResponse(get)
        assertEquals(body.size.toLong(), LocalStreamProxy.nanoContentLength(head))
        assertEquals(0, head.data.available())
        assertEquals(body.size, get.data.available())
    }

    @Test
    fun head_reportsSameContentLengthAsGet_forManifest() {
        val proxy = newProxy()
        proxy.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            proxy.configure(
                manifestXml = "abcdef",
                videoTitle = "t",
                videoId = null,
                videoUrl = "https://example.invalid/v",
                videoMime = null,
                audioUrl = null,
                audioMime = null,
                video = LocalStreamProxy.StreamRanges(2, 3, contentLength = 6L),
                audio = null,
            )
            val url = proxy.proxyUrl("/manifest.mpd")
            val get = http(url, "GET")
            val head = http(url, "HEAD")
            assertEquals(200, get.code)
            assertEquals(200, head.code)
            assertEquals(get.length, head.length)
            assertTrue("GET must advertise a positive length", get.length > 0)
            assertEquals(0, head.body.size)
            assertEquals(get.length.toInt(), get.body.size)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun manifestRejectsAnUnrelatedHost() {
        val proxy = newProxy()
        proxy.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            configureManifest(proxy, "video-one")
            val client = OkHttpClient()
            val url = proxy.proxyUrl("/manifest.mpd")
            client.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
            }
            for (host in listOf("example.com", "localhost:${proxy.listeningPort}", "127.0.0.1:1")) {
                client.newCall(Request.Builder().url(url).header("Host", host).build()).execute().use {
                    assertEquals(host, 403, it.code)
                }
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun manifestUsesAnOpaqueMarkerStableOnlyForTheCurrentVideo() {
        val proxy = newProxy()
        proxy.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val url = proxy.proxyUrl("/manifest.mpd")
            fun marker(): String {
                val xml = http(url, "GET").body.toString(Charsets.UTF_8)
                assertFalse(xml.contains("video-one"))
                assertFalse(xml.contains("766964656f2d6f6e65"))
                return requireNotNull(Regex("<!-- vid:([a-z0-9-]+) -->").find(xml)).groupValues[1]
            }
            configureManifest(proxy, "video-one")
            val first = marker()
            configureManifest(proxy, "video-one")
            assertEquals(first, marker())
            configureManifest(proxy, "video-two")
            assertFalse(first == marker())
        } finally {
            proxy.stop()
        }
    }

    private fun configureManifest(proxy: LocalStreamProxy, id: String) {
        proxy.configure("<?xml version=\"1.0\"?><MPD/>", "t", id,
            null, null, null, null, null, null)
    }

    /** Constructor only: no start(), so no socket and no background threads. */
    private fun newProxy(): LocalStreamProxy =
        LocalStreamProxy(ContextWrapper(null), OkHttpClient(), advertisedHost = "127.0.0.1")

    private fun configureVideo(proxy: LocalStreamProxy, contentLength: Long) {
        proxy.configure(
            manifestXml = null,
            videoTitle = "t",
            videoId = "same-video-id",
            videoUrl = "https://example.invalid/v",
            videoMime = null,
            audioUrl = null,
            audioMime = null,
            video = LocalStreamProxy.StreamRanges(2, 3, contentLength = contentLength),
            audio = null,
        )
    }

    private fun streamEntry(proxy: LocalStreamProxy, token: String): Any {
        val field = LocalStreamProxy::class.java.getDeclaredField("streamInfo").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val table = field.get(proxy) as Map<String, Any>
        return requireNotNull(table[token]) { "no streamInfo entry for '$token'" }
    }

    private fun entryLong(entry: Any, name: String): Long {
        val field = entry.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return (field.get(entry) as Number).toLong()
    }

    // Exercise the real mutation without Android HTTP plumbing or a test-only production API.
    private fun backfill(proxy: LocalStreamProxy, entry: Any, chunkStart: Long, resolved: Long) {
        val method = LocalStreamProxy::class.java.getDeclaredMethod(
            "maybeBackfillContentLength",
            String::class.java,
            entry.javaClass,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }
        method.invoke(proxy, "v", entry, chunkStart, resolved)
    }

    private data class HttpResult(val code: Int, val length: Long, val body: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HttpResult

            if (code != other.code) return false
            if (length != other.length) return false
            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = code
            result = 31 * result + length.hashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    private fun http(url: String, method: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.connect()
        val code = conn.responseCode
        val length = conn.contentLengthLong
        val body = if (method == "HEAD") {
            ByteArray(0)
        } else {
            conn.inputStream.use { it.readBytes() }
        }
        conn.disconnect()
        return HttpResult(code, length, body)
    }
}
