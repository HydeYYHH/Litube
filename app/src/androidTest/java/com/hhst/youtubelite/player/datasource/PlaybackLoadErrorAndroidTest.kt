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
import com.hhst.youtubelite.extractor.MediaProtocol
import com.hhst.youtubelite.extractor.MediaRange
import com.hhst.youtubelite.extractor.MediaRequestPlan
import com.hhst.youtubelite.extractor.StreamClient
import com.hhst.youtubelite.extractor.WebAccount
import com.hhst.youtubelite.extractor.YoutubeWebSession
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    @Test fun aDeclaredReadinessWindowSchedulesOneRetryWithoutTrackFallback() {
        var now = 1_000_000L
        val policy = PlaybackLoadErrorPolicy { now }
        val load = info(IOException("wrapped", MediaNotReadyException(6_000_001L, status(403))))
        assertEquals(6L, policy.getRetryDelayMsFor(load))
        assertNull(policy.getFallbackSelectionFor(LoadErrorHandlingPolicy.FallbackOptions(1, 0, 3, 0), load))
        now = 6_000_001L
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(load))
        assertEquals(PlaybackRecovery.Reason.HTTP_403, PlaybackRecovery.classify(load.exception))
    }

    @Test fun datasourceOnlyClassifiesAnEarlyForbiddenResponseAsNotReady() {
        val server = MockWebServer()
        val http = OkHttpClient()
        val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val session = YoutubeWebSession("readiness", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap()), { "" })
        val plan = MediaRequestPlan(session, StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE,
            availableAfterMillis = 5000)
        server.start()
        try {
            for ((code, planned) in listOf(403 to true, 404 to true, 403 to false)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val source = YoutubeHttpDataSource.Factory(http, "fixture-UA").setMediaRequests(plans,
                    if (planned) plan else MediaRequestPlan(session, StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE))
                    .createDataSource()
                val failure = try {
                    source.open(DataSpec(Uri.parse(server.url("/$code-$planned").toString())))
                    fail("Expected an HTTP failure")
                    null
                } catch (failure: IOException) {
                    failure
                } finally {
                    source.close()
                }
                assertEquals(planned && code == 403, failure is MediaNotReadyException)
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun tinyInitializationReadsHaveABoundedConnectionBudget() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            start()
        }
        val http = OkHttpClient()
        val session = YoutubeWebSession("init-budget", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap()), { "" })
        val plan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HTTPS, MediaRange.HEADER)
        val source = YoutubeHttpDataSource.Factory(http, "fixture-UA")
            .setMediaRequests(YoutubeMediaRequests({ true }, ExtractionDiagnostics()), plan).createDataSource()
        val began = System.nanoTime()
        try {
            source.open(DataSpec.Builder().setUri(server.url("/init").toString()).setLength(1024).build())
            fail("A silent initialization connection must time out")
        } catch (expected: IOException) {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            assertTrue("The startup request used the full media timeout: $elapsed", elapsed in 5_000..10_000)
            assertEquals(PlaybackRecovery.Reason.TRANSPORT_FAILED, PlaybackRecovery.classify(expected))
            assertEquals("The data source must leave retries to source recovery", 1, server.requestCount)
        } finally {
            source.close()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun anEarlyForbiddenSegmentRetriesTheSameSourceWhenItsWindowEnds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        val requests = AtomicInteger()
        val secondRequestAt = AtomicLong()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    // The first refusal permits one quick probe; a second refusal must honor the announced window instead of hammering the same signed URL.
                    "/audio.ts" -> if (requests.incrementAndGet() <= 2) MockResponse().setResponseCode(403) else {
                        secondRequestAt.set(System.nanoTime())
                        MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(audio))
                    }
                    else -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("""
                        #EXTM3U
                        #EXT-X-TARGETDURATION:21
                        #EXTINF:20.0,
                        audio.ts
                        #EXT-X-ENDLIST
                    """.trimIndent())
                }
            }
            start()
        }
        val http = OkHttpClient()
        val session = YoutubeWebSession("readiness", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap()), { "" })
        val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val plan = MediaRequestPlan(session, StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE,
            availableAfterMillis = 5000)
        var player: ExoPlayer? = null
        try {
            val stream = Stream().apply {
                hlsUrl = server.url("/master.m3u8").toString()
                hlsRequestPlan = plan
            }
            val source = MediaSourceResolver(PlayerDataSource.create(instrumentation.targetContext, http, plans))
                .resolve(stream, Metadata().apply { id = "hlsReadiness"; duration = 20 }).mediaSource
            val done = CountDownLatch(1)
            val failure = AtomicReference<PlaybackException>()
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) done.countDown()
                        }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error); done.countDown() }
                    })
                    setMediaSource(source)
                    prepare()
                }
            }
            assertTrue("The readiness retry did not settle", done.await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals("Only one probe and one scheduled retry are allowed", 3, requests.get())
            assertTrue("Media3 retried before the readiness deadline", secondRequestAt.get() >= plan.availableAtNanos)
            instrumentation.runOnMainSync { assertEquals(Player.STATE_READY, player?.playbackState) }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun expiredRequestsAndSessionFencesImmediatelyReachSourceRecovery() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 3, 0)
        for (error in listOf(status(403), IOException("MEDIA_ERROR_BODY"), IOException("MEDIA_SESSION_CHANGED"),
            IOException("MEDIA_OBJECT_CHANGED"), IOException("MEDIA_URL_EXPIRED"))) {
            val load = info(IOException("wrapper", error))
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(load))
            assertNull(policy.getFallbackSelectionFor(options, load))
        }
        assertNotNull(policy.getFallbackSelectionFor(options, info(status(404))))
    }

    @Test fun rejectedHlsSegmentsDoNotProbeEveryVariantBeforeRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/master.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody("""
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=150000,RESOLUTION=426x240,CODECS="avc1.42001e,mp4a.40.2"
                        low.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,CODECS="avc1.42001e,mp4a.40.2"
                        medium.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.42001e,mp4a.40.2"
                        high.m3u8
                    """.trimIndent())
                "/low.m3u8", "/medium.m3u8", "/high.m3u8" -> MockResponse()
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("""
                        #EXTM3U
                        #EXT-X-TARGETDURATION:20
                        #EXTINF:20.0,
                        segment.ts
                        #EXT-X-ENDLIST
                    """.trimIndent())
                else -> MockResponse().setResponseCode(403)
            }
        }
        server.start()
        var player: ExoPlayer? = null
        try {
            val stream = Stream().apply { hlsUrl = server.url("/master.m3u8").toString() }
            val metadata = Metadata().apply { id = "hlsVariants"; duration = 20 }
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
                    play()
                }
            }
            assertTrue("Rejected variants did not reach source recovery", done.await(5, TimeUnit.SECONDS))
            assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, failure.get().errorCode)
            val paths = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)?.path }
            assertEquals("Rejected segments must reach recovery without probing variants: $paths",
                1, paths.count { it == "/segment.ts" })
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            server.shutdown()
        }
    }

    @Test fun productionSourcesRejectErrorDocumentsWithoutRequestRetries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (kind in listOf("hls", "dash", "progressive")) for (type in listOf("text/html", "application/json")) {
            val server = MockWebServer()
            server.enqueue(MockResponse().setHeader("Content-Type", type).setBody("error"))
            server.start()
            var player: ExoPlayer? = null
            val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val http = OkHttpClient()
            try {
                val session = YoutubeWebSession("document-fixture", WebAccount.ANONYMOUS, 0, null,
                    null, null, "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap()), { "" })
                val protocol = when (kind) { "hls" -> MediaProtocol.HLS; "dash" -> MediaProtocol.DASH; else -> MediaProtocol.HTTPS }
                val plan = MediaRequestPlan(session, StreamClient.WEB, protocol, MediaRange.NONE)
                val url = server.url("/media").toString()
                val stream = Stream().apply {
                    when (kind) {
                        "hls" -> { hlsUrl = url; hlsRequestPlan = plan }
                        "dash" -> { dashUrl = url; dashRequestPlan = plan }
                        else -> formats = listOf(Format(url = url, height = 360, mimeType = "video/mp4",
                            codec = "avc1.42001e,mp4a.40.2", itag = 18, requestPlan = plan))
                    }
                }
                val metadata = Metadata().apply { id = "errorDoc001"; duration = 60 }
                val source = MediaSourceResolver(PlayerDataSource.create(instrumentation.targetContext, http, plans))
                    .resolve(stream, metadata).mediaSource
                val done = CountDownLatch(1)
                val failure = AtomicReference<PlaybackException>()
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { failure.set(error); done.countDown() }
                        })
                        setMediaSource(source); prepare()
                    }
                }
                assertTrue("$kind $type kept retrying an error document", done.await(5, TimeUnit.SECONDS))
                assertEquals(PlaybackRecovery.Reason.INVALID_MEDIA_RESPONSE, PlaybackRecovery.classify(failure.get()))
                assertEquals("$kind $type repeated the refused request", 1, server.requestCount)
            } finally {
                instrumentation.runOnMainSync { player?.release() }
                http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
                server.shutdown()
            }
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
            val session = YoutubeWebSession("child-fixture", WebAccount.ANONYMOUS, 0, null,
                "", "", "visitor", "fixture-UA", 1, null, "fixture", JsonObject(emptyMap()), { "" })
            val plan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE, false)
            val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
            val child = server.url("/child.ts").toString()
            val prefetched = MediaRequestPlan(session, StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE, false)
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
