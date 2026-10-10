package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class PlaybackLoadErrorAndroidTest {
    private val spec = DataSpec(Uri.parse("https://media.invalid/init"))
    private val policy = PlaybackLoadErrorPolicy()

    private fun info(error: IOException, type: Int = C.DATA_TYPE_MEDIA, bytes: Long = 0) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(1, spec, spec.uri, emptyMap(), 0, 30_000, bytes),
            MediaLoadData(type), error, 2,
        )

    private fun status(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code, "fixture", null, emptyMap(), spec, byteArrayOf(),
    )

    @Test fun expiredRequestsAndSessionFencesImmediatelyReachSourceRecovery() {
        for (error in listOf(status(403), IOException("MEDIA_SESSION_CHANGED"),
            IOException("MEDIA_OBJECT_CHANGED"), IOException("MEDIA_URL_EXPIRED"))) {
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(IOException("wrapper", error))))
        }
    }

    @Test fun onlyZeroProgressInitializationTransportFailureSkipsRequestRetries() {
        val timeout = IOException("read", SocketTimeoutException())
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(timeout, C.DATA_TYPE_MEDIA_INITIALIZATION)))
        val normal = DefaultLoadErrorHandlingPolicy()
        for ((type, bytes) in listOf(C.DATA_TYPE_MEDIA_INITIALIZATION to 1L,
            C.DATA_TYPE_MEDIA to 0L, C.DATA_TYPE_MANIFEST to 0L)) {
            val load = info(timeout, type, bytes)
            assertEquals(normal.getRetryDelayMsFor(load), policy.getRetryDelayMsFor(load))
        }
    }

    @Test fun transientResponsesAndUnclassifiedIoKeepMedia3RetryPolicy() {
        val normal = DefaultLoadErrorHandlingPolicy()
        for (error in listOf(status(404), status(416), status(429), status(500),
            IOException("ordinary I/O"), java.io.EOFException())) {
            val load = info(error)
            assertEquals(normal.getRetryDelayMsFor(load), policy.getRetryDelayMsFor(load))
        }
        assertEquals(PlaybackRecovery.Reason.HTTP_403, PlaybackRecovery.classify(status(403)))
        assertFalse(PlaybackRecovery.Reason.HTTP_403.needsLightRefresh)
    }

    @Test fun productionVodAndLiveSourcesSurface403AfterOneHttpRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (live in listOf(false, true)) for (kind in listOf("hls", "dash", "progressive")) {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            var player: ExoPlayer? = null
            try {
                val path = when (kind) { "hls" -> "/master.m3u8"; "dash" -> "/manifest.mpd"; else -> "/media.mp4" }
                val url = server.url(path).toString()
                val stream = Stream().apply {
                    when (kind) {
                        "hls" -> hlsUrl = url
                        "dash" -> dashUrl = url
                        else -> formats = listOf(Format(url = url, height = 360,
                            codec = "avc1.42001e,mp4a.40.2", mimeType = "video/mp4", itag = 18))
                    }
                }
                val metadata = Metadata().apply { id = "Wh9klmWAm5s"; duration = 60; isLive = live }
                val resolver = MediaSourceResolver(PlayerDataSource.create(instrumentation.targetContext, OkHttpClient()))
                val source = resolver.resolve(stream, metadata).mediaSource
                val done = CountDownLatch(1)
                val failure = AtomicReference<PlaybackException>()
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { failure.set(error); done.countDown() }
                        })
                        setMediaSource(source)
                        prepare()
                    }
                }
                assertTrue("$kind live=$live still retrying the rejected URL", done.await(5, TimeUnit.SECONDS))
                assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, failure.get().errorCode)
                assertEquals("$kind live=$live repeated the rejected request", 1, server.requestCount)
            } finally {
                instrumentation.runOnMainSync { player?.release() }
                server.shutdown()
            }
        }
    }

    @Test fun productionHlsChild403RetainsTheManifestClient() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("""
            #EXTM3U
            #EXT-X-TARGETDURATION:21
            #EXTINF:20.0,
            child.ts
            #EXT-X-ENDLIST
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        var player: ExoPlayer? = null
        try {
            val session = YoutubeSession("child-fixture", YoutubeSession.Account.ANONYMOUS, 0, null,
                "", "", "visitor", "fixture-UA", 1, null, "fixture", JsonObject(), { "" })
            val plan = RequestPlan(session, ClientProfile.VISIONOS, RequestPlan.Protocol.HLS, RequestPlan.Range.NONE, false)
            val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val child = server.url("/child.ts").toString()
            val prefetched = RequestPlan(session, ClientProfile.WEB_SAFARI, RequestPlan.Protocol.HLS, RequestPlan.Range.NONE, false)
            plans.register(child, prefetched)
            val stream = Stream().apply { hlsUrl = server.url("/master.m3u8").toString(); hlsRequestPlan = plan }
            val metadata = Metadata().apply { id = "hlsChild001"; duration = 20 }
            val resolver = MediaSourceResolver(PlayerDataSource.create(instrumentation.targetContext, OkHttpClient(), plans))
            val source = resolver.resolve(stream, metadata).mediaSource
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { done.countDown() }
                    })
                    setMediaSource(source); prepare()
                }
            }
            assertTrue("The refused HLS child did not reach recovery", done.await(5, TimeUnit.SECONDS))
            assertSame("The selected manifest owns its child, not the prefetched client", plan, plans.plan(child))
            assertEquals(2, server.requestCount)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            server.shutdown()
        }
    }
}
