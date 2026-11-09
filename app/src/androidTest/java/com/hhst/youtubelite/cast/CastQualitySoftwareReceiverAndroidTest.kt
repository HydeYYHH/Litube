@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.cast

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueRepository
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.engine.MemoryJsonCache
import com.hhst.youtubelite.player.engine.PlaybackEngine
import com.hhst.youtubelite.player.engine.streamCache
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.yinnho.upnpcast.DLNACast
import java.net.Proxy
import java.nio.ByteBuffer
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Opt-in: the production engine, view model, proxy and controllers drive independent receivers. */
class CastQualitySoftwareReceiverAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var engine: PlaybackEngine
    private lateinit var cast: CastController
    private lateinit var dlna: DlnaController
    private lateinit var model: PlayerViewModel
    private lateinit var localPlayer: ExoPlayer

    @Test fun manualQualityAndAutoOnKodi() = exercise("dlna")
    @Test fun manualQualityAndAutoOnCastReceiver() = exercise("chromecast")

    private fun exercise(route: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("qualityRoute") == route)
        val base = requireNotNull(args.getString("qualityMediaBase")).trimEnd('/')
        val proxyPort = args.getString("qualityProxyPort")?.toInt() ?: 48082
        val holdMs = args.getString("qualityHoldMs")?.toLong()?.coerceIn(0, 15_000) ?: 0
        val app = instrumentation.targetContext
        val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun adaptive(name: String, height: Int, itag: Int): Format {
            val url = "$base/$name.mp4"
            val bytes = http.newCall(Request.Builder().url(url).header("Range", "bytes=0-32767").build())
                .execute().use { check(it.isSuccessful); requireNotNull(it.body).bytes() }
            var offset = 0
            var indexStart = -1
            var indexEnd = -1
            while (offset + 8 <= bytes.size) {
                val size = ByteBuffer.wrap(bytes, offset, 4).int
                if (size < 8) break
                if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "sidx") {
                    indexStart = offset
                    indexEnd = offset + size - 1
                    break
                }
                offset += size
            }
            check(indexStart > 0 && indexEnd < bytes.size) { "Missing global sidx in $url" }
            return Format(url = url, itag = itag, width = if (height == 360) 640 else if (height == 720) 1280 else 0,
                height = height, fps = if (height > 0) 24 else 0, bitrate = if (height == 720) 1_500_000 else 500_000,
                codec = if (height > 0) "avc1.42c01f" else "mp4a.40.2", mimeType = if (height > 0) "video/mp4" else "audio/mp4",
                videoOnly = height > 0, audioOnly = height == 0, initStart = 0, initEnd = indexStart - 1,
                indexStart = indexStart, indexEnd = indexEnd, sampleRate = if (height == 0) 48000 else -1,
                audioChannels = if (height == 0) 1 else -1)
        }
        val formats = listOf(adaptive("video-360", 360, 136), adaptive("video-720", 720, 137),
            adaptive("audio", 0, 140).copy(audioTrackId = "en", audioTrackName = "Original", audioTrackOriginal = true),
            adaptive("audio-es", 0, 141).copy(audioTrackId = "es", audioTrackName = "Spanish")) +
            listOf(360, 720).map { height -> Format(url = "$base/muxed-$height.mp4", itag = height,
                width = height * 16 / 9, height = height, fps = 24, bitrate = height * 2_000,
                codec = "avc1.42c01f", mimeType = "video/mp4") }
        val stream = Stream().apply { this.formats = formats }
        val store = ViewModelStore()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun remote(): Player? = if (route == "dlna") dlna.remotePlayer() else cast.remotePlayer()
        fun await(label: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                var ready = false
                main { ready = condition() }
                if (ready) return
                if (SystemClock.elapsedRealtime() >= deadline) {
                    main {
                        val proxy = cast.javaClass.getDeclaredField("proxy").apply { isAccessible = true }.get(cast)
                        val manifest = proxy?.javaClass?.getDeclaredField("manifest")?.apply { isAccessible = true }?.get(proxy)
                        val xml = manifest?.javaClass?.getDeclaredField("xml")?.apply { isAccessible = true }?.get(manifest) as? String
                        xml?.let { File(app.filesDir, "device-evidence/cast-quality-failed.mpd").apply { parentFile?.mkdirs() }.writeText(it) }
                    }
                    var receiver = "unavailable"
                    main { remote()?.let { p -> receiver = "state=${p.playbackState} wanted=${p.playWhenReady} position=${p.currentPosition} rawPlaying=${p.javaClass.getDeclaredField("playing").apply { isAccessible = true }.getBoolean(p)}" } }
                    error("Timed out $route $label; receiver=$receiver; ${engine.snapshot.value}; ${cast.state.value}; ${dlna.state.value}")
                }
                Thread.sleep(150)
            }
        }
        fun report(stage: String) {
            main {
                val snapshot = engine.snapshot.value
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "\nQUALITY $route $stage: pin=${snapshot.qualityLabel} active=${snapshot.activeQuality} " +
                        "height=${snapshot.videoFormat?.height} position=${remote()?.currentPosition} playing=${remote()?.isPlaying}\n") })
            }
            Thread.sleep(holdMs)
        }
        try {
            main {
                val prefs = GlobalContext.get().get<ExtensionManager>()
                val cache = MemoryJsonCache()
                engine = PlaybackEngine(app, Extractor(streamCache(stream, "Litube quality test", 240), scope),
                    MediaSourceResolver(PlayerDataSource.create(app, http)), cache, prefs, SponsorBlockManager(http, prefs, scope))
                localPlayer = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                // Emulator NAT cannot receive LAN connections. The same-port desktop adb forward
                // exposes the real Android proxy; only the advertised network endpoint is replaced.
                cast = CastController(app, http, proxyFactory = {
                    LocalStreamProxy(app, http, port = proxyPort, advertisedHost = "127.0.0.1")
                })
                dlna = DlnaController(app)
                model = PlayerViewModel(engine, QueueRepository(cache), prefs, cache, cast, dlna, app)
                store.put("quality-test", model)
                model.initializeCast()
                model.openVideo("quality0001")
            }
            await("local preparation") { engine.snapshot.value.prepared && engine.currentCastSource() != null }
            main {
                if (route == "dlna") {
                    val location = requireNotNull(args.getString("dlnaDescriptionUrl"))
                    val device = DLNACast.Device(location, "Kodi quality test", requireNotNull(DlnaDescription.location(location)).host, true)
                    val id = DlnaController.ID_PREFIX + location
                    @Suppress("UNCHECKED_CAST")
                    val endpoints = dlna.javaClass.getDeclaredField("endpoints").apply { isAccessible = true }.get(dlna) as MutableMap<String, DLNACast.Device>
                    endpoints[id] = device
                    model.onCastDevice(id)
                } else {
                    val endpointType = cast.javaClass.declaredClasses.single { it.simpleName == "Endpoint" }
                    val endpoint = endpointType.declaredConstructors.single().apply { isAccessible = true }
                        .newInstance("Cast Receiver quality test", requireNotNull(args.getString("castReceiverHost")),
                            args.getString("castReceiverPort")?.toInt() ?: 8009)
                    @Suppress("UNCHECKED_CAST")
                    val endpoints = cast.javaClass.getDeclaredField("endpoints").apply { isAccessible = true }.get(cast) as MutableMap<String, Any>
                    endpoints["quality-receiver"] = endpoint
                    model.onCastDevice("quality-receiver")
                }
            }
            await("initial auto playback", 45_000) { remote()?.isPlaying == true && (remote()?.currentPosition ?: 0) >= 1_000 }
            report("initial auto 720p")

            fun change(label: String?, height: Int, stage: String, paused: Boolean = false) {
                var position = 0L
                var oldManifest: String? = null
                main {
                    position = remote()!!.currentPosition
                    oldManifest = remote()!!.currentMediaItem?.localConfiguration?.uri?.toString()
                    model.onQuality(label)
                }
                await(stage) {
                    val p = remote()
                    engine.snapshot.value.videoFormat?.height == height && engine.snapshot.value.qualityLabel == label &&
                        p?.playbackState == Player.STATE_READY && p.playWhenReady == !paused &&
                        p.javaClass.getDeclaredField("playing").apply { isAccessible = true }.getBoolean(p) == !paused &&
                        abs(p.currentPosition - position) < 4_000 &&
                        (route == "dlna" || p.currentMediaItem?.localConfiguration?.uri?.toString() != oldManifest)
                }
                main {
                    val p = cast.javaClass.getDeclaredField("proxy").apply { isAccessible = true }.get(cast) as LocalStreamProxy
                    val source = if (route == "dlna") engine.currentMuxedCastSource() else engine.currentCastSource()
                    assertEquals(height, source?.video?.height)
                    assertTrue(p.currentConfigId() > 0)
                    assertNull(engine.snapshot.value.error)
                }
                if (!paused) {
                    val readyAt = SystemClock.elapsedRealtime()
                    await("$stage receiver position advances") {
                        val p = remote() ?: return@await false
                        val sample = p.javaClass.getDeclaredField("sampledAt").apply { isAccessible = true }.getLong(p)
                        val actual = p.javaClass.getDeclaredField("positionMs").apply { isAccessible = true }.getLong(p)
                        sample > readyAt + 1_000 && actual > position + 500 && p.isPlaying
                    }
                }
                report(stage)
            }
            val seekRequestedAt = SystemClock.elapsedRealtime()
            main { remote()!!.seekTo(45_000) }
            await("receiver-confirmed seek before quality changes") {
                val p = remote() ?: return@await false
                val sample = p.javaClass.getDeclaredField("sampledAt").apply { isAccessible = true }.getLong(p)
                val position = p.javaClass.getDeclaredField("positionMs").apply { isAccessible = true }.getLong(p)
                sample > seekRequestedAt + 1_500 && abs(position - 45_000) < 3_500
            }
            report("seek to 45 seconds")
            change("360p", 360, "manual 360p")
            change("720p", 720, "manual 720p")
            change("360p", 360, "manual 360p again")
            change(null, 720, "auto restored 720p")
            main { remote()!!.pause() }
            await("paused") { remote()?.playWhenReady == false && remote()?.isPlaying == false }
            change("360p", 360, "paused manual 360p", paused = true)
            change(null, 720, "paused auto restored 720p", paused = true)
            var pausedPosition = 0L
            main { pausedPosition = remote()!!.currentPosition }
            Thread.sleep(1_500)
            main { assertTrue(abs(remote()!!.currentPosition - pausedPosition) < 1_500); remote()!!.play() }
            await("resumed") { remote()?.isPlaying == true && (remote()?.currentPosition ?: 0) > pausedPosition + 500 }
            report("resumed after auto")
            if (args.getString("qualityControls") == "1" && route == "chromecast") {
                for ((key, file) in listOf("id:es" to "audio-es.mp4", "id:en" to "audio.mp4")) {
                    var oldManifest: String? = null
                    main {
                        oldManifest = remote()!!.currentMediaItem?.localConfiguration?.uri?.toString()
                        model.onAudioTrack(key)
                    }
                    await("audio $key") {
                        engine.snapshot.value.audioTrackKey == key &&
                            engine.currentCastSource()?.audio?.url?.endsWith(file) == true &&
                            remote()?.currentMediaItem?.localConfiguration?.uri?.toString() != oldManifest &&
                            remote()?.playbackState == Player.STATE_READY && remote()?.isPlaying == true
                    }
                    report("audio $key ($file)")
                }
                for (speed in listOf(2f, 1f)) {
                    main { model.onSpeed(speed) }
                    Thread.sleep(4_000)
                    var from = 0L
                    var fromTime = 0L
                    val sampledPosition = remote()!!.javaClass.getDeclaredField("positionMs").apply { isAccessible = true }
                    val sampledTime = remote()!!.javaClass.getDeclaredField("sampledAt").apply { isAccessible = true }
                    main { from = sampledPosition.getLong(remote()); fromTime = sampledTime.getLong(remote()) }
                    var to = 0L
                    var toTime = 0L
                    await("speed $speed receiver samples", timeoutMs = 30_000) {
                        to = sampledPosition.getLong(remote()); toTime = sampledTime.getLong(remote())
                        toTime - fromTime >= 10_000
                    }
                    val elapsed = toTime - fromTime
                    val ratio = (to - from).toDouble() / elapsed
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                        "\nCONTROL chromecast requestedSpeed=$speed receiverDelta=${to-from} elapsed=$elapsed ratio=$ratio\n") })
                    assertTrue("Receiver did not apply $speed x speed: ratio=$ratio", abs(ratio - speed) < 0.4)
                }
            }
        } finally {
            main {
                if (::model.isInitialized) model.onCastStop()
                if (::engine.isInitialized) { engine.attachCastPlayer(null); engine.stop() }
                if (::cast.isInitialized) { cast.endSession(); cast.stopLink(); cast.setDiscoveryEnabled(false) }
                if (::dlna.isInitialized) { dlna.stopCasting(); dlna.endSession(); dlna.setDiscoveryEnabled(false) }
                store.clear()
                if (::localPlayer.isInitialized) localPlayer.release()
            }
            scope.cancel()
        }
    }
}
