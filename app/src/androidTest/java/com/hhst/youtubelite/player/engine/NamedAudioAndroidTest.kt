@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.datasource.AudioTrackIdentity
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Verifies audio identity across source changes. */
class NamedAudioAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun realMultilingualVideoExposesOriginalAndSwitchesDub() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        val id = args.getString("videoId") ?: "0e3GPea1Tyg"
        val app = instrumentation.targetContext
        val engine = GlobalContext.get().get<PlaybackApi>() as PlaybackEngine
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(Intent.ACTION_VIEW, Uri.parse(VideoId.watchUrl(id)),
                app, MainActivity::class.java))
        val player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
        try {
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = engine.snapshot.value.videoId == id && player.isPlaying && engine.snapshot.value.audioTracks.size > 1 }
                if (!ready) Thread.sleep(100)
            }
            DeviceEvidence.writeJson("issue-337-live-audio.json", Gson().toJson(mapOf(
                "videoId" to id, "ready" to ready, "tracks" to engine.snapshot.value.audioTracks,
                "selected" to engine.snapshot.value.audioTrackKey, "error" to engine.snapshot.value.error)))
            assertTrue("Real multilingual video did not become playable: ${engine.snapshot.value}", ready)
            val original = engine.snapshot.value.audioTrackKey
            assertNotNull("Original identity must be known", original)
            val dub = engine.snapshot.value.audioTracks.first { it.key != original }
            instrumentation.runOnMainSync { engine.setAudioTrack(dub.key) }
            val switchDeadline = SystemClock.elapsedRealtime() + 20_000
            var switched = false
            while (!switched && SystemClock.elapsedRealtime() < switchDeadline) {
                instrumentation.runOnMainSync {
                    player.audioDecoderCounters?.ensureUpdated()
                    val selected = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .flatMap { group -> (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it) } }
                    switched = engine.snapshot.value.audioTrackKey == dub.key && player.isPlaying &&
                        (!dub.key.startsWith("hls:") || selected.any {
                            AudioTrackIdentity.renditionKey(it.language, it.label) == dub.key }) &&
                        (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                }
                if (!switched) Thread.sleep(100)
            }
            assertTrue("Live dub failed", switched)
            instrumentation.runOnMainSync { engine.setAudioTrack(null) }
            DeviceEvidence.writeJson("issue-337-live-switch.json", Gson().toJson(mapOf("videoId" to id,
                "tracks" to engine.snapshot.value.audioTracks.size, "original" to original, "dub" to dub.key, "switched" to switched)))
        } finally {
            instrumentation.runOnMainSync { GlobalContext.get().get<PlayerViewModel>().onMiniClose(); engine.stop() }
            scenario.close()
        }
    }

    @Test fun missingIdsAndLocalesDoNotHideNamesOrPreventSwitching() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        val requests = CopyOnWriteArrayList<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests += session.uri
                return newFixedLengthResponse(Response.Status.OK, "video/mp2t", audio.inputStream(), audio.size.toLong())
            }
        }
        server.start()
        val stream = Stream().apply {
            formats = listOf(
                Format(url = "http://127.0.0.1:${server.listeningPort}/english.ts", audioOnly = true,
                    codec = "mp4a.40.2", audioTrackName = "English", audioTrackOriginal = true),
                Format(url = "http://127.0.0.1:${server.listeningPort}/spanish.ts", audioOnly = true,
                    codec = "mp4a.40.2", audioTrackName = "Spanish", bitrate = 200000))
        }
        val fixtureCache = streamCache(stream, title = "Named audio")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var engine: PlaybackEngine
        lateinit var player: ExoPlayer
        fun awaitTrack(key: String, path: String) {
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    player.audioDecoderCounters?.ensureUpdated()
                    ready = engine.snapshot.value.audioTrackKey == key && player.isPlaying && requests.contains(path) &&
                        (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Track did not decode: ${engine.snapshot.value}", ready)
        }
        try {
            instrumentation.runOnMainSync {
                val prefs = GlobalContext.get().get<ExtensionManager>()
                val http = OkHttpClient()
                engine = PlaybackEngine(app, Extractor(GlobalContext.get().get<HttpDownloader>(), fixtureCache, scope),
                    MediaSourceResolver(PlayerDataSource.create(app, http)), GlobalContext.get().get<JsonCache>(), prefs,
                    SponsorBlockManager(http, prefs, scope))
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                engine.play("namedAud001")
            }
            awaitTrack("name:English", "/english.ts")
            assertEquals(listOf("name:English", "name:Spanish"), engine.snapshot.value.audioTracks.map { it.key })
            instrumentation.runOnMainSync { engine.setAudioTrack("name:Spanish") }
            awaitTrack("name:Spanish", "/spanish.ts")
            instrumentation.runOnMainSync { engine.setAudioTrack(null) }
            awaitTrack("name:English", "/english.ts")
            DeviceEvidence.writeJson("issue-337-named-audio.json", "{\"namedTracksVisible\":2,\"originalDefault\":true,\"dubDecoded\":true,\"defaultRestoresOriginal\":true}")
        } finally {
            instrumentation.runOnMainSync { engine.stop(); player.release() }
            scope.cancel(); server.stop()
        }
    }
}
