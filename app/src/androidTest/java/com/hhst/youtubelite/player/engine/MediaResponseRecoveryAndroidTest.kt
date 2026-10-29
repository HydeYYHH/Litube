@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
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

    private fun exercise(type: String, persistent: Boolean = false, pause: Boolean = false, captions: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
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
                    "/audio.ts" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(audio))
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
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject()) { "" }
        val plan = MediaRequestPlan(session, StreamClient.WEB, MediaProtocol.HTTPS, MediaRange.HEADER)
        val refusedUrl = server.url("/refused.mp4").toString()
        val bad = Stream().apply {
            formats = listOf(Format(url = refusedUrl, height = 360, itag = 18,
                codec = "avc1.42001e,mp4a.40.2", mimeType = "video/mp4", requestPlan = plan))
        }
        val good = Stream().apply {
            hlsUrl = server.url("/master.m3u8").toString()
            hlsRequestPlan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.HLS, MediaRange.NONE)
            if (captions) subtitles = listOf(Subtitle(server.url("/refused.vtt").toString(), "en", mimeType = "text/vtt"))
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
                assertEquals(listOf(refusedUrl to MediaRejection.INVALID_BODY), feedback.toList())
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
        val http = OkHttpClient()
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val previous = listOf(PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO,
            PreferenceKeys.SKIP_POI_HIGHLIGHT).associateWith(prefs::isEnabled)
        var player: ExoPlayer? = null
        try {
            previous.keys.forEach { prefs.setEnabled(it, false) }
            instrumentation.runOnMainSync {
                val cache = MemoryJsonCache().apply {
                    if (captions) put("player:subtitle_language", "en|manual", 60_000)
                }
                engine = PlaybackEngine(app, Extractor(streamCache(if (captions) good else bad), scope, host),
                    MediaSourceResolver(PlayerDataSource.create(app, http, plans)), cache, prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                engine.play("https://www.youtube.com/watch?v=errorDoc001&t=5")
            }
            var settled = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!settled && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val current = requireNotNull(player)
                    current.audioDecoderCounters?.ensureUpdated()
                    settled = if (persistent) engine.snapshot.value.error != null else
                        (if (captions) rejected.get() > 0 && engine.snapshot.value.subtitleKey == null else refreshes.get() > 0) &&
                        current.playbackState == Player.STATE_READY &&
                        (pause || current.isPlaying && (current.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                }
                if (!settled) Thread.sleep(50)
            }
            assertTrue("Recovery did not settle: ${engine.snapshot.value}", settled)
            assertEquals("Only refused media sources need a fresh extraction", if (captions) 0 else 1, refreshes.get())
            if (captions) assertTrue(rejected.get() in 1..4) else assertEquals(if (persistent) 2 else 1, rejected.get())
            assertEquals(if (captions) 0 else if (persistent) 2 else 1, feedback.size)
            instrumentation.runOnMainSync {
                if (persistent) {
                    assertNotNull(engine.snapshot.value.error)
                    val report = requireNotNull(engine.snapshot.value.failureReport)
                    assertEquals(engine.snapshot.value.videoId, report.videoId)
                    assertTrue(report.text.contains("MEDIA_ERROR_BODY"))
                    assertTrue(report.text.contains("full_refreshes: 1"))
                    assertTrue(report.text.contains("http_status: 200"))
                    assertTrue(report.text.contains("content_type: application/json"))
                    assertFalse(engine.snapshot.value.prepared)
                } else {
                    assertNull(engine.snapshot.value.error)
                    assertNull(engine.snapshot.value.failureReport)
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
