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

    @Test fun repeated403ReleasesOnlyTheCurrentVideoPinAndActuallyDecodesTheBackup() = exercise()
    @Test fun sourceStillRefusedAfterPinRescueTerminatesWithoutARefreshLoop() = exercise(persistent = true)

    private fun exercise(persistent: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val low = instrumentation.context.assets.open("player/quality-recovery/360.ts").use { it.readBytes() }
        val high = instrumentation.context.assets.open("player/quality-recovery/720.ts").use { it.readBytes() }
        val refused = AtomicInteger()
        val allowHigh = AtomicBoolean()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when (path) {
                        "/high.ts" -> if (allowHigh.get()) MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(high))
                            else { refused.incrementAndGet(); MockResponse().setResponseCode(403) }
                        "/low.ts" -> if (persistent && refused.get() > 0) MockResponse().setResponseCode(403)
                            else MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(low))
                        "/master.m3u8" -> MockResponse().setBody("""
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS="avc1.42c01e,mp4a.40.2",RESOLUTION=640x360,FRAME-RATE=24
                            low.m3u8
                            #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.42c01f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=24
                            high.m3u8
                        """.trimIndent())
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
        val state = MemoryJsonCache()
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
            previous.keys.forEach { prefs.setEnabled(it, it == PreferenceKeys.REMEMBER_QUALITY) }
            instrumentation.runOnMainSync {
                engine = PlaybackEngine(app, Extractor(GlobalContext.get().get<HttpDownloader>(), cache, scope),
                    MediaSourceResolver(PlayerDataSource.create(app, http)), state, prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                engine.setVideoSurface(surface)
                engine.play("https://www.youtube.com/watch?v=pinRescue01&t=5")
            }
            await("Initial Auto did not render 360p") {
                player!!.videoDecoderCounters?.ensureUpdated()
                player!!.isPlaying && player!!.videoFormat?.height == 360 && (player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            instrumentation.runOnMainSync {
                // Model the already-completed fresh-source attempt; this fixture isolates the
                // subsequent real Media3 403 and pin rescue from the media API and session refresh.
                engine.javaClass.getDeclaredField("reExtractAttempts").apply { isAccessible = true }.setInt(engine, 1)
                engine.setQuality("720p")
            }
            if (persistent) {
                await("A source refused after pin rescue must terminate") { engine.snapshot.value.error != null }
                assertEquals("720p", state.values["player:quality"])
                assertTrue("The retry budget must bound failed media loads", refused.get() in 1..4)
                instrumentation.runOnMainSync {
                    assertFalse(engine.snapshot.value.prepared)
                    assertTrue(engine.javaClass.getDeclaredField("videoPinRescued").apply { isAccessible = true }.getBoolean(engine))
                }
                return
            }
            await("The fresh source's refused pin did not recover") {
                player!!.videoDecoderCounters?.ensureUpdated()
                refused.get() > 0 && engine.snapshot.value.qualityLabel == null && player!!.isPlaying &&
                    player!!.videoFormat?.height == 360 && (player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            assertEquals("Persisted preference must survive the temporary rescue", "720p", state.values["player:quality"])
            instrumentation.runOnMainSync {
                assertNull(engine.snapshot.value.error)
                assertTrue(player!!.currentPosition >= 5_000)
                assertTrue(engine.javaClass.getDeclaredField("videoPinRescued").apply { isAccessible = true }.getBoolean(engine))
                engine.pause()
                allowHigh.set(true)
                engine.play("https://www.youtube.com/watch?v=pinNext0001&t=5")
            }
            await("The next video should honor the remembered manual quality") {
                player!!.videoDecoderCounters?.ensureUpdated()
                engine.snapshot.value.qualityLabel == "720p" && player!!.isPlaying && player!!.videoFormat?.height == 720 &&
                    (player!!.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
        } finally {
            instrumentation.runOnMainSync { player?.let { engine.stop(); it.release() } }
            scope.cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            server.shutdown()
            previous.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
        }
    }
}
