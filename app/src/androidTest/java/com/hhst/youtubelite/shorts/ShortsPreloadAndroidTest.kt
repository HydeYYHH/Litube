@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.datasource.PlayerDataSource
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

class ShortsPreloadAndroidTest {
    @Test fun relativeHlsSegmentsAreAvailableToPlaybackWithoutAnotherRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
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
                        """.trimIndent())
                        "/root/segments/audio.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t")
                            .setBody(Buffer().write(bytes))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        val session = YoutubeWebSession("prefetch", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject()) { "" }
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
            preloader.preload("prefetch001")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (preloader.prefetchedBytes("prefetch001") == 0L && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(20)
            assertTrue("No HLS bytes were warmed: $paths", preloader.prefetchedBytes("prefetch001") > 0)
            assertTrue(paths.contains("/root/segments/audio.ts"))
            val requests = server.requestCount
            val source = sources.vodHls(plan).createDataSource(C.DATA_TYPE_MEDIA)
            try {
                source.open(DataSpec.Builder().setUri(server.url("/root/segments/audio.ts").toString())
                    .setLength(bytes.size.toLong()).build())
                val cached = ByteArray(bytes.size)
                var offset = 0
                while (offset < cached.size) {
                    val read = source.read(cached, offset, cached.size - offset)
                    if (read == C.RESULT_END_OF_INPUT) break
                    offset += read
                }
                assertEquals(bytes.size, offset)
                assertArrayEquals(bytes, cached)
            } finally { source.close() }
            assertEquals("Playback missed the prefetched media", requests, server.requestCount)
        } finally {
            preloader.cancel()
            scope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
