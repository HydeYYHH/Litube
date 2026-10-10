@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.HttpDownloader
import com.hhst.youtubelite.extractor.Stream
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
import org.junit.Test
import org.junit.Rule
import org.koin.core.context.GlobalContext
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class TransportRecoveryAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Test fun sourceRefreshKeepsPositionAndReachesRealAudioDecode() = exerciseRecovery()
    @Test fun pauseDuringRefreshIsPreserved() = exerciseRecovery(pauseDuringRefresh = true)
    @Test fun persistentTransportFailureStopsAfterOneLightRefresh() = exerciseRecovery(persistent = true)

    private fun exerciseRecovery(pauseDuringRefresh: Boolean = false, persistent: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.startsWith("/audio.ts") == true) MockResponse()
                        .setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(audio))
                    else MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("""
                        #EXTM3U
                        #EXT-X-TARGETDURATION:21
                        #EXT-X-MEDIA-SEQUENCE:0
                        #EXTINF:20.0,
                        audio.ts
                        #EXT-X-ENDLIST
                    """.trimIndent())
            }
            start()
        }
        val refreshes = AtomicInteger()
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/master.m3u8" && (persistent || refreshes.get() == 0)) {
                attempts.incrementAndGet()
                throw SocketTimeoutException("fixture transport failure")
            }
            chain.proceed(chain.request())
        }.build()
        val stream = Stream().apply { hlsUrl = server.url("/master.m3u8").toString() }
        lateinit var engine: PlaybackEngine
        var player: ExoPlayer? = null
        val extractionCache = streamCache(stream, onInvalidate = { _ ->
            refreshes.incrementAndGet()
            if (pauseDuringRefresh) instrumentation.runOnMainSync { engine.pause() }
        })
        val stateCache = MemoryJsonCache()
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val keys = listOf(PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO, PreferenceKeys.SKIP_POI_HIGHLIGHT)
        val previous = keys.associateWith(prefs::isEnabled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            keys.forEach { prefs.setEnabled(it, false) }
            instrumentation.runOnMainSync {
                engine = PlaybackEngine(app, Extractor(GlobalContext.get().get<HttpDownloader>(), extractionCache, scope),
                    MediaSourceResolver(PlayerDataSource.create(app, http)), stateCache, prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                engine.play("https://www.youtube.com/watch?v=transpOne01&t=5")
            }
            var completed = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!completed && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val p = player!!
                    p.audioDecoderCounters?.ensureUpdated()
                    completed = if (persistent) engine.snapshot.value.error != null
                        else refreshes.get() > 0 && p.playbackState == Player.STATE_READY &&
                            (pauseDuringRefresh || p.isPlaying && (p.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                }
                if (!completed) Thread.sleep(50)
            }
            assertTrue("Transport recovery did not settle: refreshes=${refreshes.get()}, state=${engine.snapshot.value}", completed)
            assertEquals("The source refresh budget must survive its own reload", 1, refreshes.get())
            assertTrue("Fixture did not exercise an actual Media3 transport failure", attempts.get() > 0)
            instrumentation.runOnMainSync {
                if (persistent) {
                    assertFalse(engine.snapshot.value.prepared)
                    assertNotNull(engine.snapshot.value.error)
                } else {
                    assertNull(engine.snapshot.value.error)
                    assertTrue("Refresh lost the seek position", player!!.currentPosition >= 5_000)
                    assertEquals(!pauseDuringRefresh, engine.snapshot.value.userWantsPlay)
                    assertEquals(!pauseDuringRefresh, player!!.isPlaying)
                }
            }
        } finally {
            instrumentation.runOnMainSync { player?.let { engine.stop(); it.release() } }
            scope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
            previous.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
        }
    }
}
