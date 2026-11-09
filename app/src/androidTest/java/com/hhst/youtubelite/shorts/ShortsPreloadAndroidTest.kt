@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonObject
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.datasource.PlaybackClaim
import com.hhst.youtubelite.player.datasource.YoutubeHttpDataSource
import com.hhst.youtubelite.player.engine.streamCache
import com.hhst.youtubelite.shorts.data.ShortsMediaPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class ShortsPreloadAndroidTest {
    @Test fun relativeHlsSegmentsAreAvailableToPlaybackWithoutAnotherRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        verifyCachedSegment(bytes)
    }

    @Test fun largeHlsPlaylistAndItsSegmentsAreAvailableWithoutAnotherRequest() {
        verifyCachedSegment(ByteArray(1024) { it.toByte() }, largePlaylist = true)
    }

    @Test fun largeHlsSegmentUsesOneRequestAndCountsOnlyAvailableBytes() {
        verifyCachedSegment(ByteArray(512 * 1024) { it.toByte() }, singleRequest = true)
    }

    @Test fun slowHlsSegmentStopsWithinBudgetAndKeepsPartialBytes() {
        verifyCachedSegment(ByteArray(512 * 1024) { it.toByte() }, singleRequest = true, slow = true)
    }

    @Test fun foregroundHandoverKeepsPartialBytesAvailableToPlayback() {
        verifyCachedSegment(ByteArray(512 * 1024) { it.toByte() }, singleRequest = true, slow = true, claim = true)
    }

    @Test fun wholeSegmentResponseBoundsAnOversizedRead() = verifyWholeSegmentAtOffset(0)

    @Test fun wholeSegmentResponseBoundsATailRead() = verifyWholeSegmentAtOffset(12_345)

    private fun verifyWholeSegmentAtOffset(position: Int) {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(Buffer().write(bytes)))
            start()
        }
        val session = YoutubeWebSession("whole-segment", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap())) { "" }
        val plan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE)
        val http = OkHttpClient()
        val source = YoutubeHttpDataSource.Factory(http, "fixture-UA")
            .setMediaRequests(YoutubeMediaRequests({ true }, ExtractionDiagnostics()), plan).createDataSource()
        try {
            val available = bytes.size - position
            assertEquals("200 Content-Length must bound the requested budget", available.toLong(),
                source.open(DataSpec.Builder().setUri(server.url("/segment.ts").toString())
                    .setPosition(position.toLong()).setLength(1024 * 1024L).build()))
            val actual = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                actual.write(buffer, 0, count)
            }
            assertArrayEquals(bytes.copyOfRange(position, bytes.size), actual.toByteArray())
            assertEquals(1, server.requestCount)
        } finally {
            source.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun verifyCachedSegment(bytes: ByteArray, singleRequest: Boolean = false, slow: Boolean = false, claim: Boolean = false, largePlaylist: Boolean = false) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = ConcurrentLinkedQueue<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    paths.add(path)
                    return when (path) {
                        "/root/master.m3u8" -> MockResponse().setBody("""
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=128000,RESOLUTION=270x480
                            variant/media.m3u8
                        """.trimIndent())
                        "/root/variant/media.m3u8" -> MockResponse().setBody("""
                            #EXTM3U
                            #EXT-X-TARGETDURATION:21
                            #EXTINF:20.0,
                            ../segments/audio.ts
                            #EXT-X-ENDLIST
                        """.trimIndent() + if (largePlaylist) "\n#" + "x".repeat(400 * 1024) + "\n" else "")
                        "/root/segments/audio.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t")
                            .setBody(Buffer().write(bytes)).apply {
                                if (slow) throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS)
                            }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        val session = YoutubeWebSession("prefetch", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap())) { "" }
        val plan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE)
        val stream = Stream().apply {
            hlsUrl = server.url("/root/master.m3u8").toString()
            hlsRequestPlan = plan
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        val sources = PlayerDataSource.create(app, http, YoutubeMediaRequests({ true }, ExtractionDiagnostics()))
        val preloader = ShortsMediaPreloader(app, Extractor(streamCache(stream), scope), sources)
        try {
            PlaybackClaim.videoId = null
            val started = SystemClock.elapsedRealtime()
            preloader.preload("prefetch001")
            if (claim) {
                val requestDeadline = SystemClock.elapsedRealtime() + 5_000
                while (!paths.contains("/root/segments/audio.ts") && SystemClock.elapsedRealtime() < requestDeadline)
                    SystemClock.sleep(10)
                SystemClock.sleep(250)
                PlaybackClaim.videoId = "prefetch001"
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (preloader.prefetchedBytes("prefetch001") == 0L && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(20)
            assertTrue("No HLS bytes were warmed: $paths", preloader.prefetchedBytes("prefetch001") > 0)
            assertTrue(paths.contains("/root/segments/audio.ts"))
            val warmed = preloader.prefetchedBytes("prefetch001")
            if (singleRequest) {
                assertEquals("Prefetch reopened the same segment", 1, paths.count { it == "/root/segments/audio.ts" })
                if (slow) {
                    assertTrue("Time budget/handover did not stop the read", warmed in 1 until bytes.size.toLong())
                    assertTrue("Prefetch exceeded the cooperative read budget", SystemClock.elapsedRealtime() - started < 3_000)
                } else assertEquals("Warmed bytes must exclude reads past EOF", bytes.size.toLong(), warmed)
            }
            val requests = server.requestCount
            // Playback loads child playlists as MANIFEST through the manifest cache; warming them through the media-byte cache leaves an avoidable network round trip.
            for (path in listOf("/root/master.m3u8", "/root/variant/media.m3u8")) {
                val manifest = sources.vodHls(plan).createDataSource(C.DATA_TYPE_MANIFEST)
                try {
                    manifest.open(DataSpec.Builder().setUri(server.url(path).toString()).build())
                    val buffer = ByteArray(4096)
                    while (manifest.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) { }
                } finally { manifest.close() }
            }
            assertEquals("Playback missed a prefetched playlist", requests, server.requestCount)
            val source = sources.vodHls(plan).createDataSource(C.DATA_TYPE_MEDIA)
            try {
                source.open(DataSpec.Builder().setUri(server.url("/root/segments/audio.ts").toString())
                    .setLength(if (slow) warmed else bytes.size.toLong()).build())
                val cached = ByteArray(if (slow) warmed.toInt() else bytes.size)
                var offset = 0
                while (offset < cached.size) {
                    val read = source.read(cached, offset, cached.size - offset)
                    if (read == C.RESULT_END_OF_INPUT) break
                    offset += read
                }
                assertEquals(cached.size, offset)
                assertArrayEquals(bytes.copyOf(cached.size), cached)
            } finally { source.close() }
            assertEquals("Playback missed the prefetched media", requests, server.requestCount)
        } finally {
            preloader.cancel()
            PlaybackClaim.videoId = null
            scope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
