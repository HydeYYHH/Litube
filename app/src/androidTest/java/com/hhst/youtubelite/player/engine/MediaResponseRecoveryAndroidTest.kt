@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonObject
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
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
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class MediaResponseRecoveryAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun jsonResponseRefreshesTheRefusedSourceAndDecodes() = exercise("application/json")
    @Test fun htmlResponseRefreshesTheRefusedSourceAndDecodes() = exercise("text/html")
    @Test fun persistentErrorDocumentsStopAfterOneFreshExtraction() = exercise("application/json", persistent = true)
    @Test fun pauseDuringFreshExtractionPreservesPositionAndIntent() = exercise("text/html", pause = true)
    @Test fun refusedCaptionsDoNotPreventAudioPlayback() = exercise("text/html", captions = true)
    @Test fun refusedCaptionsPreserveThePausedPosition() = exercise("text/html", captions = true, pause = true)
    @Test fun missingHlsManifestChangesSourceAndDecodes() = exercise("text/html", missingManifest = true)
    @Test fun persistentlyMissingHlsManifestStopsAfterOneFreshExtraction() = exercise("text/html", missingManifest = true, persistent = true)
    @Test fun closedTlsConnectionUsesExistingManifestWithoutRefreshingTheClient() = exercise("", transport = true)

    private fun exercise(type: String, persistent: Boolean = false, pause: Boolean = false, captions: Boolean = false,
                         missingManifest: Boolean = false, transport: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = instrumentation.context.assets.open(if (transport) "player/quality-recovery/720.ts" else "player/audio-original/audio.ts").use { it.readBytes() }
        val dashAudio = if (transport) instrumentation.context.assets.open("player/prefetch/audio.mp4").use { it.readBytes() } else null
        val rejected = AtomicInteger()
        lateinit var engine: PlaybackEngine
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/refused.vtt" -> {
                        rejected.incrementAndGet()
                        if (pause) instrumentation.runOnMainSync { engine.pause() }
                        MockResponse().setResponseCode(429).setHeader("Content-Type", type).setBody("rate limited")
                    }
                    "/refused.mp4" -> {
                        rejected.incrementAndGet()
                        MockResponse().setHeader("Content-Type", type).setBody("error")
                    }
                    "/refused.m3u8" -> {
                        rejected.incrementAndGet()
                        MockResponse().setResponseCode(404).setHeader("Content-Type", type).setBody("Not found")
                    }
                    "/audio.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(audio))
                    "/dash-audio.mp4" -> {
                        val bytes = requireNotNull(dashAudio)
                        val range = request.requestUrl?.queryParameter("range")
                            ?: request.getHeader("Range")?.removePrefix("bytes=") ?: "0-"
                        val from = range.substringBefore('-').toInt()
                        val to = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                        if (from > to) MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                        else MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mp4")
                            .setHeader("Content-Range", "bytes $from-$to/${bytes.size}")
                            .setBody(Buffer().write(bytes, from, to - from + 1))
                            // Let the video TLS failure select HLS before this independent audio load completes.
                            .setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS)
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
        val session = YoutubeWebSession("error-doc", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap())) { "" }
        val plan = MediaRequestPlan(session, StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER)
        val refusedUrl = server.url(if (missingManifest) "/refused.m3u8" else "/refused.mp4").toString()
        val expectedRejection = if (missingManifest) MediaRejection.UNAVAILABLE else MediaRejection.INVALID_BODY
        val bad = Stream().apply {
            if (missingManifest) {
                hlsUrl = refusedUrl
                hlsRequestPlan = MediaRequestPlan(session, StreamClient.WEB_SAFARI, MediaProtocol.HLS, MediaRange.NONE)
            } else {
                formats = listOf(Format(url = refusedUrl, height = 360, itag = 18,
                    codec = "avc1.42001e,mp4a.40.2", mimeType = "video/mp4", requestPlan = plan))
            }
        }
        val good = Stream().apply {
            hlsUrl = server.url("/master.m3u8").toString()
            hlsRequestPlan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE)
            if (captions) subtitles = listOf(Subtitle(server.url("/refused.vtt").toString(), "en", mimeType = "text/vtt"))
        }
        if (transport) {
            bad.hlsUrl = good.hlsUrl; bad.hlsRequestPlan = good.hlsRequestPlan
            // Adaptive DASH requires separate video and audio formats; a ranged muxed itag 18 would be bypassed in favor of HLS and never exercise the TLS failure.
            bad.formats = listOf(
                Format(url = refusedUrl, height = 360, width = 640, itag = 134, videoOnly = true,
                    codec = "avc1.42001e", mimeType = "video/mp4", requestPlan = plan,
                    initStart = 0, initEnd = 31, indexStart = 32, indexEnd = 63),
                Format(url = server.url("/dash-audio.mp4").toString(), itag = 140, audioOnly = true,
                    codec = "mp4a.40.2", mimeType = "audio/mp4", bitrate = 64_000,
                    sampleRate = 48_000, audioChannels = 1, requestPlan = plan,
                    initStart = 0, initEnd = 731, indexStart = 732, indexEnd = 783, approxDurationMs = 4_000),
            )
        }
        val plans = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val refreshes = AtomicInteger()
        val feedback = ConcurrentLinkedQueue<Pair<String, MediaRejection>>()
        val host = object : PlaybackHost {
            override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
                                 taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean,
                                 diagnosticContext: DiagnosticContext): ExtractionContext =
                ExtractionContext(session, object : ExtractionTransport {
                    override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse =
                        error("Unexpected extraction HTTP")
                    override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse =
                        error("Unexpected extraction HTTP")
                }, false, catalog, playbackPriority, "", deadlineNanos, taskCurrent, ExtractionDiagnostics())

            override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved {
                assertTrue("Recovery must bypass the refused extraction", fresh)
                assertEquals(listOf(refusedUrl to expectedRejection), feedback.toList())
                refreshes.incrementAndGet()
                if (pause) instrumentation.runOnMainSync { engine.pause() }
                return MscPlayback.Resolved(Metadata().apply { id = videoId; duration = 20 }, if (persistent) bad else good)
            }
            override fun noteMediaRejected(videoId: String, url: String, reason: MediaRejection, diagnosticContext: DiagnosticContext?): StreamClient? {
                assertEquals("errorDoc001", videoId)
                feedback.add(url to reason)
                return plans.plan(url)?.client
            }
            override fun chapters(videoId: String, context: ExtractionContext): List<Chapter> = emptyList()
            override fun deferredClients(videoId: String): Set<StreamClient> = emptySet()
            override fun cache(inner: Cache, context: () -> ExtractionContext): Cache = inner
            override fun invalidateStream(videoId: String) = Unit
            override fun scopeHint(): String = "error-doc"
            override fun recoveryScope(): String = "error-doc"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (transport && chain.request().url.encodedPath == "/refused.mp4") {
                rejected.incrementAndGet()
                throw javax.net.ssl.SSLHandshakeException("connection closed").apply { initCause(java.io.EOFException("connection closed")) }
            }
            chain.proceed(chain.request())
        }.build()
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val previous = listOf(PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO,
            PreferenceKeys.SKIP_POI_HIGHLIGHT).associateWith(prefs::isEnabled)
        var player: ExoPlayer? = null
        var surface: android.view.SurfaceView? = null
        try {
            previous.keys.forEach { prefs.setEnabled(it, false) }
            if (transport) activity.scenario.onActivity { host ->
                surface = android.view.SurfaceView(host).also(host::setContentView)
            }
            instrumentation.runOnMainSync {
                val cache = MemoryJsonCache().apply {
                    if (captions) put("player:subtitle_language", "en|manual", 60_000)
                }
                engine = PlaybackEngine(app, Extractor(streamCache(if (captions) good else bad), scope, host),
                    MediaSourceResolver(PlayerDataSource.create(app, http, plans)), cache, prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                if (transport) { engine.setVideoSurface(surface); engine.setVideoVisible(true) }
                engine.play("https://www.youtube.com/watch?v=errorDoc001&t=5")
            }
            var settled = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!settled && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val current = requireNotNull(player)
                    current.audioDecoderCounters?.ensureUpdated()
                    settled = if (persistent) engine.snapshot.value.error != null else
                        (if (transport) engine.snapshot.value.firstFrameRendered else if (captions) rejected.get() > 0 && engine.snapshot.value.subtitleKey == null else refreshes.get() > 0) &&
                        current.playbackState == Player.STATE_READY &&
                        (pause || current.isPlaying && (current.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                }
                if (!settled) Thread.sleep(50)
            }
            assertTrue("Recovery did not settle: ${engine.snapshot.value}", settled)
            assertEquals("Transport fallback must reuse the issued manifest", if (captions || transport) 0 else 1, refreshes.get())
            assertEquals(if (persistent) 2 else 1, rejected.get())
            assertEquals(if (captions || transport) 0 else if (persistent) 2 else 1, feedback.size)
            instrumentation.runOnMainSync {
                if (persistent) {
                    assertNotNull(engine.snapshot.value.error)
                    val report = requireNotNull(engine.snapshot.value.failureReport)
                    assertEquals(engine.snapshot.value.videoId, report.videoId)
                    assertTrue(report.text.contains(if (missingManifest) "MANIFEST_UNAVAILABLE" else "MEDIA_ERROR_BODY"))
                    assertTrue(report.text.contains("full_refreshes: 1"))
                    assertTrue(report.text.contains("http_status: ${if (missingManifest) 404 else 200}"))
                    if (!missingManifest) assertTrue(report.text.contains("content_type: application/json"))
                    assertFalse(engine.snapshot.value.prepared)
                } else {
                    assertNull(engine.snapshot.value.error)
                    assertNull(engine.snapshot.value.failureReport)
                    if (transport) assertEquals(720, requireNotNull(player).videoFormat?.height)
                    assertTrue("Recovery lost the requested position", requireNotNull(player).currentPosition >= 5_000)
                    assertEquals(!pause, engine.snapshot.value.userWantsPlay)
                    assertEquals(!pause, requireNotNull(player).isPlaying)
                }
            }
        } finally {
            instrumentation.runOnMainSync { player?.let { engine.stop(); it.release() } }
            scope.cancel()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            server.shutdown()
            previous.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
        }
    }
}
