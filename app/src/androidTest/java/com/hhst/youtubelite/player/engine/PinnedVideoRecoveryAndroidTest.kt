@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.activity.ComponentActivity
import android.view.SurfaceView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class PinnedVideoRecoveryAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun refreshedSourceDecodesAtTheManualHeight() = exercise()
    @Test fun repeated403KeepsManualQualityAndStopsAtTheRecoveryBudget() = exercise(persistent = true)
    @Test fun retryKeepsManualQualityWithRememberQualityDisabled() = exercise(persistent = true, remember = false)
    @Test fun missingManualHeightAutomaticallyAlignsAndKeepsPlaying() = exercise(missingHeight = true)
    @Test fun shortsMissingManualHeightAutomaticallyAlignsAndKeepsPlaying() = exercise(missingHeight = true, shorts = true)
    @Test fun rememberedMissingHeightAutomaticallyAlignsAtStartup() = exercise(missingHeight = true, rememberedMissing = true)
    @Test fun shortsRememberedMissingHeightAutomaticallyAlignsAtStartup() =
        exercise(missingHeight = true, rememberedMissing = true, shorts = true)

    private fun exercise(persistent: Boolean = false, remember: Boolean = true, missingHeight: Boolean = false,
                         rememberedMissing: Boolean = false, shorts: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val low = instrumentation.context.assets.open("player/quality-recovery/360.ts").use { it.readBytes() }
        val high = instrumentation.context.assets.open("player/quality-recovery/720.ts").use { it.readBytes() }
        val refused = AtomicInteger()
        val allowHigh = AtomicBoolean()
        val refreshes = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when (path) {
                        "/high.ts" -> if (allowHigh.get()) MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(high))
                            else { refused.incrementAndGet(); MockResponse().setResponseCode(403) }
                        "/low.ts" -> if (persistent && refused.get() > 0 && !allowHigh.get()) MockResponse().setResponseCode(403)
                            else MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(low))
                        "/master.m3u8" -> MockResponse().setBody("""
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS="avc1.42c01e,mp4a.40.2",RESOLUTION=640x360,FRAME-RATE=24
                            low.m3u8
                        """.trimIndent() + if (missingHeight) "" else """

                            #EXT-X-STREAM-INF:BANDWIDTH=900000000,CODECS="avc1.42c01f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=24
                            high.m3u8
                        """.trimIndent().let { "\n$it" })
                        else -> MockResponse().setBody("""
                            #EXTM3U
                            #EXT-X-TARGETDURATION:21
                            #EXTINF:20.0,
                            ${if (path == "/high.m3u8") "high.ts?itag=136" else "low.ts?itag=134"}
                            #EXT-X-ENDLIST
                        """.trimIndent())
                    }
                }
            }
            start()
        }
        val stream = Stream().apply {
            hlsUrl = server.url("/master.m3u8").toString()
            formats = listOf(Format(itag = 134, height = 360, videoOnly = true),
                Format(itag = 136, height = 720, videoOnly = true))
        }
        val cache = streamCache(stream)
        val session = YoutubeWebSession("pin-rescue", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", kotlinx.serialization.json.JsonObject(emptyMap()), { "" })
        val host = object : PlaybackHost {
            override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?,
                                 taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean,
                                 diagnosticContext: com.hhst.youtubelite.diagnostics.DiagnosticContext) =
                ExtractionContext(session, object : ExtractionTransport {
                    override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse =
                        error("Unexpected extraction HTTP")
                    override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse =
                        error("Unexpected extraction HTTP")
                }, false, catalog, playbackPriority, "", deadlineNanos, taskCurrent, ExtractionDiagnostics())
            override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved {
                assertTrue(fresh)
                refreshes.incrementAndGet()
                if (!persistent) allowHigh.set(true)
                return MscPlayback.Resolved(Metadata().apply { id = videoId; duration = 20 }, stream)
            }
            override fun chapters(videoId: String, context: ExtractionContext): List<Chapter> = emptyList()
            override fun deferredClients(videoId: String): Set<StreamClient> = emptySet()
            override fun cache(inner: Cache, context: () -> ExtractionContext): Cache = inner
            override fun invalidateStream(videoId: String) = Unit
            override fun scopeHint() = "pin-rescue"
            override fun recoveryScope() = "pin-rescue"
        }
        val state = MemoryJsonCache()
        if (rememberedMissing) state.put("player:quality", "1080p60", 60_000)
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val previous = listOf(PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO,
            PreferenceKeys.SKIP_POI_HIGHLIGHT, PreferenceKeys.REMEMBER_QUALITY).associateWith(prefs::isEnabled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        lateinit var surface: SurfaceView
        activity.scenario.onActivity { screen ->
            surface = SurfaceView(screen)
            screen.setContentView(surface)
        }
        var player: ExoPlayer? = null
        lateinit var engine: PlaybackEngine
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var completed = false
            while (!completed && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { completed = predicate() }
                if (!completed) Thread.sleep(50)
            }
            assertTrue("$message: ${engine.snapshot.value}", completed)
        }
        try {
            previous.keys.forEach { prefs.setEnabled(it, remember && it == PreferenceKeys.REMEMBER_QUALITY) }
            instrumentation.runOnMainSync {
                engine = PlaybackEngine(app, Extractor(cache, scope, host),
                    MediaSourceResolver(PlayerDataSource.create(app, http)), state, prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                engine.setVideoSurface(surface)
                engine.setShortsMode(shorts)
                engine.play("https://www.youtube.com/watch?v=pinRescue01&t=5")
            }
            if (rememberedMissing) {
                await("Remembered missing quality must align without failing playback") {
                    engine.snapshot.value.qualityLabel == "360p" && engine.snapshot.value.error == null
                }
                assertEquals("1080p60", state.values["player:quality"])
            }
            await("Initial Auto did not render 360p") {
                player!!.videoDecoderCounters?.ensureUpdated()
                player!!.isPlaying && player!!.videoFormat?.height == 360 && (player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            instrumentation.runOnMainSync {
                engine.setQuality("720p")
            }
            if (missingHeight) {
                for (label in listOf("720p", "144p", "360p60")) {
                    instrumentation.runOnMainSync { engine.setQuality(label) }
                    await("Missing quality $label must align to the supported track") {
                        engine.snapshot.value.prepared && engine.snapshot.value.error == null &&
                            engine.snapshot.value.qualityLabel == "360p" && player!!.isPlaying && player!!.videoFormat?.height == 360
                    }
                }
                instrumentation.runOnMainSync {
                    assertNull(engine.snapshot.value.failureReport)
                    assertTrue(player!!.currentPosition >= 5_000)
                    assertEquals(0, refused.get())
                    assertEquals(0, refreshes.get())
                    engine.play("https://www.youtube.com/watch?v=pinNext0001&t=5")
                }
                await("The next video must also align the saved quality") {
                    engine.snapshot.value.videoId == "pinNext0001" && engine.snapshot.value.prepared &&
                        engine.snapshot.value.error == null && engine.snapshot.value.qualityLabel == "360p" && player!!.isPlaying
                }
                instrumentation.runOnMainSync { engine.setQuality(null) }
                await("Auto must still release the aligned quality") {
                    engine.snapshot.value.prepared && engine.snapshot.value.error == null &&
                        engine.snapshot.value.qualityLabel == null && player!!.isPlaying && player!!.videoFormat?.height == 360
                }
                return
            }
            if (persistent) {
                await("Repeated refusal must stop without releasing the manual height") { engine.snapshot.value.error != null }
                assertTrue("The retry budget must bound failed media loads", refused.get() in 1..4)
                instrumentation.runOnMainSync {
                    assertFalse(engine.snapshot.value.prepared)
                    assertEquals("720p", engine.snapshot.value.qualityLabel)
                    assertEquals(720, engine.prefetchDemand("pinRescue01").height)
                    allowHigh.set(true)
                    engine.retry()
                }
            }
            await("Recovery must decode the requested height") {
                player!!.videoDecoderCounters?.ensureUpdated()
                refused.get() > 0 && engine.snapshot.value.qualityLabel == "720p" && player!!.isPlaying &&
                    player!!.videoFormat?.height == 720 && (player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            assertEquals(if (persistent) 2 else 1, refreshes.get())
            assertEquals(if (remember) "720p" else null, state.values["player:quality"])
            instrumentation.runOnMainSync {
                assertNull(engine.snapshot.value.error)
                assertTrue(player!!.currentPosition >= 5_000)
                engine.play("https://www.youtube.com/watch?v=pinNext0001&t=5")
            }
            await("New video must follow the remember preference") {
                player!!.isPlaying && player!!.videoFormat?.height == (if (remember) 720 else 360) &&
                    engine.snapshot.value.qualityLabel == (if (remember) "720p" else null)
            }
            instrumentation.runOnMainSync { engine.setQuality(null) }
            await("Only an explicit Auto selection releases the manual height") {
                engine.snapshot.value.qualityLabel == null && player!!.isPlaying && player!!.videoFormat?.height == 360
            }
        } finally {
            instrumentation.runOnMainSync { player?.let { engine.stop(); it.release() } }
            scope.cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            server.shutdown()
            previous.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
        }
    }
}
