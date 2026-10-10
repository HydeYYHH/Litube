@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.koin.core.context.GlobalContext

/** The production engine decodes a three-rendition HLS playlist whose DEFAULT is a dub. */
class OriginalAudioAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Test fun originalBeatsPlaylistDefaultAndManualDubDoesNotCarryToTheNextVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/master.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish dubbed",LANGUAGE="es",DEFAULT=YES,AUTOSELECT=YES,URI="spanish.m3u8"
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English original",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="original.m3u8"
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English descriptive",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="description.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2",AUDIO="audio"
                    spanish.m3u8
                """.trimIndent())
                "/audio.ts" -> newFixedLengthResponse(Response.Status.OK, "video/mp2t", audio.inputStream(), audio.size.toLong())
                else -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:21
                    #EXT-X-MEDIA-SEQUENCE:0
                    #EXTINF:20.0,
                    audio.ts
                    #EXT-X-ENDLIST
                """.trimIndent())
            }
        }
        server.start()
        val stream = Stream().apply { hlsUrl = "http://127.0.0.1:${server.listeningPort}/master.m3u8" }
        val cache = streamCache(stream, title = "Original audio")
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val keys = listOf(PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO, PreferenceKeys.SKIP_POI_HIGHLIGHT)
        val previous = keys.associateWith(prefs::isEnabled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var engine: PlaybackEngine
        lateinit var player: ExoPlayer
        var initialized = false
        val observations = mutableListOf<Map<String, Any?>>()
        fun awaitSelected(id: String, label: String) {
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    assertNull("Decoder/source failure", engine.snapshot.value.error)
                    val selected = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                        (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it).label }
                    }
                    player.audioDecoderCounters?.ensureUpdated()
                    ready = engine.snapshot.value.videoId == id && selected.singleOrNull() == label && player.isPlaying &&
                        engine.snapshot.value.audioTrackKey?.endsWith(label) == true &&
                        (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Expected $label; state=${engine.snapshot.value}", ready)
            instrumentation.runOnMainSync {
                player.audioDecoderCounters?.ensureUpdated()
                assertTrue((player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                observations += mapOf("videoId" to id, "selected" to label, "key" to engine.snapshot.value.audioTrackKey,
                    "renditions" to engine.snapshot.value.audioTracks.size, "decodedBuffers" to player.audioDecoderCounters?.renderedOutputBufferCount)
            }
        }
        try {
            keys.forEach { prefs.setEnabled(it, false) }
            instrumentation.runOnMainSync {
                val http = OkHttpClient()
                engine = PlaybackEngine(app, Extractor(GlobalContext.get().get<HttpDownloader>(), cache, scope),
                    MediaSourceResolver(PlayerDataSource.create(app, http)), GlobalContext.get().get<JsonCache>(), prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                initialized = true
                engine.play("audioOne001")
            }
            awaitSelected("audioOne001", "English original")
            assertEquals(3, engine.snapshot.value.audioTracks.size)
            instrumentation.runOnMainSync { engine.setAudioTrack("hls:es:Spanish dubbed") }
            awaitSelected("audioOne001", "Spanish dubbed")
            instrumentation.runOnMainSync { engine.setAudioTrack(null) }
            awaitSelected("audioOne001", "English original")
            instrumentation.runOnMainSync { engine.setAudioTrack("hls:es:Spanish dubbed") }
            awaitSelected("audioOne001", "Spanish dubbed")
            instrumentation.runOnMainSync { engine.play("audioTwo002") }
            awaitSelected("audioTwo002", "English original")
            DeviceEvidence.writeJson("youtube-original-audio.json", Gson().toJson(observations))
        } finally {
            instrumentation.runOnMainSync { if (initialized) { engine.stop(); player.release() } }
            scope.cancel()
            server.stop()
            previous.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
        }
    }
}
