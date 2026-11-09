package com.hhst.youtubelite.cast

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.engine.CastSource
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 acceptance for the DLNA route on the real UPnPCast stack. Renderers are
 * not part of CI; these tests pin the graceful no-renderer behavior. End-to-end
 * playback to a TV is a manual acceptance item.
 */
@RunWith(AndroidJUnit4::class)
class DlnaRouteAndroidTest {
    @Test fun openEndedProgressiveRangesReadBeyondTheProxyChunkLimit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val upstream = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val range = session.headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                val end = (range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    "video/mp4", bytes.copyOfRange(start, end + 1).inputStream(), (end - start + 1).toLong()).apply {
                    addHeader("Accept-Ranges", "bytes")
                    if (range != null) addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                }
            }
        }
        upstream.start()
        val http = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val controller = CastController(context, http)
        try {
            val source = CastSource("video", "Title", 240,
                Format(url = "http://127.0.0.1:${upstream.listeningPort}/video", mimeType = "video/mp4"), null)
            val url = requireNotNull(controller.dlnaStreamUrl(source))
            // First response discovers the upstream total; the second uses the cached total.
            for (start in listOf(1024 * 1024, 0)) {
                http.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=$start-").build())
                    .execute().use { response ->
                        assertEquals(206, response.code)
                        assertEquals("bytes $start-${bytes.lastIndex}/${bytes.size}", response.header("Content-Range"))
                        assertArrayEquals(bytes.copyOfRange(start, bytes.size), response.body!!.bytes())
                    }
            }
        } finally { controller.stopLink(); upstream.stop() }
    }

    @Test fun progressiveMediaWithoutDashIndicesSupportsRangeReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = CastVmFixture.bytes("cast-video.mp4")
        val upstream = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val range = session.headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                val end = (range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    "video/mp4", bytes.copyOfRange(start, end + 1).inputStream(), (end - start + 1).toLong()).apply {
                    addHeader("Accept-Ranges", "bytes")
                    if (range != null) addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                }
            }
        }
        upstream.start()
        val http = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val controller = CastController(context, http)
        try {
            val source = CastSource("video", "Title", 30,
                Format(url = "http://127.0.0.1:${upstream.listeningPort}/video", mimeType = "video/mp4"), null)
            val url = controller.dlnaStreamUrl(source)
            assertNotNull("Progressive formats do not require DASH indices", url)
            http.newCall(okhttp3.Request.Builder().url(requireNotNull(url)).header("Range", "bytes=32-95").build())
                .execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 32-95/${bytes.size}", response.header("Content-Range"))
                    assertArrayEquals(bytes.copyOfRange(32, 96), response.body!!.bytes())
                }
            assertNull(controller.dlnaStreamUrl(source.copy(video = source.video?.copy(videoOnly = true))))
        } finally { controller.stopLink(); upstream.stop() }
    }

    @Test fun realSsdpSearchCompletesWithoutRenderers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DLNACast.init(context)
        val devices = runBlocking { DLNACast.search(timeout = 3_000) }
        DLNACast.cleanup()
        // A renderer on the test network would invalidate the no-device assertion below.
        assumeTrue("DLNA renderer present on test network", devices.isEmpty())
    }

    @Test fun castRefusedWithoutSelectedRouteAndStateStaysClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = DlnaController(context)
        controller.initialize()
        controller.setDiscoveryEnabled(true)
        SystemClock.sleep(6_000) // one SSDP burst window
        controller.setDiscoveryEnabled(false)
        assumeTrue(
            "DLNA renderer present on test network",
            controller.state.value.devices.isEmpty(),
        )
        val started = runBlocking {
            controller.startCasting(
                CastSource(
                    videoId = "abc12345678",
                    title = "Test",
                    durationSec = 100,
                    video = Format(),
                    audio = null,
                    author = null,
                    thumbnailUrl = null,
                ),
                0L,
                "http://127.0.0.1:1/stream/v",
            )
        }
        assertFalse("Cast started without a selected device", started)
        assertFalse(controller.state.value.session)
    }
}
