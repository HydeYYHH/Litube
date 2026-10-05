package com.hhst.youtubelite.extractor

import android.os.Bundle
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.player.engine.mediaBufferBudgetBytes
import com.hhst.youtubelite.player.engine.defaultLoadControl
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.core.JsonCache
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/** Device decoder, sustained read and >60s seek acceptance using production request plans. */
@UnstableApi
class YoutubePlaybackAndroidTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun rememberedManualHlsHeightMatchesFirstDecodedFrameAndSwitches() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = GlobalContext.get().get<JsonCache>()
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val key = PreferenceKeys.REMEMBER_QUALITY
        val remembered = prefs.isEnabled(key)
        val oldQuality = cache.get("player:quality", String::class.java)
        lateinit var engine: PlaybackApi
        instrumentation.runOnMainSync { engine = GlobalContext.get().get() }
        val frames = java.util.concurrent.CountDownLatch(1)
        val firstHeight = java.util.concurrent.atomic.AtomicInteger()
        val error = AtomicReference<String>()
        var observedPlayer: ExoPlayer? = null
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstHeight.compareAndSet(0, observedPlayer?.videoFormat?.height ?: -1); frames.countDown() }
            override fun onPlayerError(failure: PlaybackException) { error.set(failure.errorCodeName); frames.countDown() }
        }
        try {
            prefs.setEnabled(key, true)
            cache.put("player:quality", "720p", 60_000)
            activity.scenario.onActivity { host ->
                // Observe the production engine's decoder rather than constructing another selector.
                observedPlayer = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                observedPlayer!!.addListener(listener)
                engine.setVideoSurface(host.surface)
                engine.play("aqz-KE-bpKQ")
            }
            assertTrue("Production first frame timed out", frames.await(45, TimeUnit.SECONDS))
            assertNull(error.get())
            assertEquals("Manual start must not fall to the automatic 480p cap", 720, firstHeight.get())
            // Video can pre-render while the separate HLS audio loader is still preparing.
            val audioReady = java.util.concurrent.atomic.AtomicBoolean()
            val audioDeadline = SystemClock.elapsedRealtime() + 15_000
            while (!audioReady.get() && error.get() == null && SystemClock.elapsedRealtime() < audioDeadline) {
                instrumentation.runOnMainSync { audioReady.set(observedPlayer!!.audioFormat != null && observedPlayer!!.isPlaying) }
                if (!audioReady.get()) Thread.sleep(100)
            }
            assertNull(error.get())
            assertTrue("Production audio did not become ready", audioReady.get())
            instrumentation.runOnMainSync { engine.setQuality("480p") }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (engine.snapshot.value.activeQuality != "480p" && error.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertNull(error.get())
            assertEquals("480p", engine.snapshot.value.activeQuality)
            File(instrumentation.targetContext.filesDir, "followup-manual-start.json").writeText(
                Gson().toJson(mapOf("firstFrameHeight" to firstHeight.get(), "afterSwitch" to engine.snapshot.value.activeQuality,
                    "audioRenditions" to engine.snapshot.value.audioTracks.size)))
        } finally {
            instrumentation.runOnMainSync {
                observedPlayer?.removeListener(listener)
                engine.stop(); engine.setVideoSurface(null)
            }
            prefs.setEnabled(key, remembered)
            if (oldQuality == null) cache.invalidate("player:quality") else cache.put("player:quality", oldQuality, 86_400_000)
        }
    }

    @Test fun vodMediaSourceDecodesAndSeeks() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("videoId") ?: "aqz-KE-bpKQ"
        val mode = args.getString("mode") ?: "hls"
        val seconds = args.getString("playSeconds")?.toInt() ?: 120
        val height = args.getString("height")?.toInt() ?: 720
        val extractor = GlobalContext.get().get<Extractor>()
        val extraction = extractor.extractFresh(id)
        val stream = extraction.stream.await()
        val metadata = extraction.metadata.await()
        val input = Stream().also { it.copyFrom(stream) }
        if (mode == "hls") {
            assertNotNull("No HLS candidate", input.hlsRequestPlan)
            input.formats = emptyList()
            input.dashUrl = null
        } else {
            input.hlsUrl = null
            input.dashUrl = null
        }
        val source = GlobalContext.get().get<MediaSourceResolver>().resolve(input, metadata,
            preferredQuality = "${height}p", forceMuxed = mode == "progressive")
        val failure = AtomicReference<String>()
        val videoTracks = AtomicReference<List<Map<String, Any>>>(emptyList())
        lateinit var player: ExoPlayer
        activity.scenario.onActivity { host ->
            player = ExoPlayer.Builder(host).setLoadControl(defaultLoadControl()).build().apply {
                volume = 0f
                setVideoSurfaceView(host.surface)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setMinVideoSize(0, height).setMaxVideoSize(Int.MAX_VALUE, height).build()
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCodeName) }
                    override fun onTracksChanged(tracks: Tracks) {
                        videoTracks.set(tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                            (0 until group.length).map { index -> mapOf(
                                "height" to group.getTrackFormat(index).height,
                                "codec" to (group.getTrackFormat(index).codecs ?: ""),
                                "support" to group.getTrackSupport(index)) }
                        })
                        if (args.getString("height") == null) return
                        // Match the production manual menu's explicit override, including HLS groups.
                        for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }) {
                            val index = (0 until group.length).firstOrNull {
                                group.isTrackSupported(it) && group.getTrackFormat(it).height == height
                            } ?: continue
                            if (trackSelectionParameters.overrides[group.mediaTrackGroup]?.trackIndices == listOf(index)) return
                            trackSelectionParameters = trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                            return
                        }
                    }
                })
                setMediaSource(source.mediaSource)
                prepare()
                play()
            }
        }
        data class Sample(val position: Long, val playing: Boolean, val ended: Boolean, val videoFrames: Int, val audioBuffers: Int, val height: Int)
        fun sample(): Sample {
            val value = AtomicReference<Sample>()
            instrumentation.runOnMainSync {
                player.videoDecoderCounters?.ensureUpdated()
                player.audioDecoderCounters?.ensureUpdated()
                value.set(Sample(player.currentPosition, player.isPlaying, player.playbackState == Player.STATE_ENDED,
                    player.videoDecoderCounters?.renderedOutputBufferCount ?: 0,
                    player.audioDecoderCounters?.renderedOutputBufferCount ?: 0, player.videoFormat?.height ?: 0))
            }
            return value.get()
        }
        val started = SystemClock.elapsedRealtime()
        val record = linkedMapOf<String, Any>("videoId" to id, "mode" to mode, "requestedPlaySeconds" to seconds)
        val runtime = Runtime.getRuntime()
        var peakHeapUsed = 0L
        record["requestedHeight"] = height
        record["heapLimitBytes"] = runtime.maxMemory()
        record["bufferBudgetBytes"] = mediaBufferBudgetBytes(runtime.maxMemory())
        var played = 0L
        var previous = sample()
        var seeked = false
        var resumedAfterSeek = false
        try {
            while (played < seconds * 1000L && SystemClock.elapsedRealtime() - started < (seconds + 60) * 1000L) {
                failure.get()?.let { fail("Media3 $it; see sanitized youtube-playback-diagnostics.txt") }
                Thread.sleep(500)
                val current = sample()
                peakHeapUsed = maxOf(peakHeapUsed, runtime.totalMemory() - runtime.freeMemory())
                val delta = current.position - previous.position
                if (current.playing && delta in 1..1500) played += delta
                if (seeked && current.position >= 91_000 && current.playing) resumedAfterSeek = true
                if (!seeked && played >= 10_000 && metadata.duration > 120) {
                    instrumentation.runOnMainSync { player.seekTo(90_000) }
                    seeked = true
                }
                previous = current
                if (current.ended) break
            }
            val final = sample()
            record["playedMs"] = played
            record["elapsedMs"] = SystemClock.elapsedRealtime() - started
            record["videoFrames"] = final.videoFrames
            record["audioBuffers"] = final.audioBuffers
            record["seekTo90s"] = seeked
            record["resumedAfterSeek"] = resumedAfterSeek
            record["peakJavaHeapUsedBytes"] = peakHeapUsed
            record["decodedHeight"] = final.height
            record["availableVideoTracks"] = videoTracks.get()
            assertTrue("Decoded no video frames", final.videoFrames > 0)
            assertTrue("Decoded no audio buffers", final.audioBuffers > 0)
            if (args.getString("height") != null) assertEquals("Requested height must reach the decoder", height, final.height)
            assertTrue("Insufficient sustained playback: $record", played >= seconds * 1000L)
            if (metadata.duration > 120) assertTrue("Seek failed: $record", resumedAfterSeek)
            instrumentation.sendStatus(2, Bundle().apply { putString("youtubePlayback", Gson().toJson(record)) })
        } finally {
            failure.get()?.let { record["error"] = it }
            File(app.filesDir, "youtube-playback-$mode.json").writeText(Gson().toJson(record))
            File(app.filesDir, "youtube-playback-diagnostics.txt").writeText(extractor.extractionDiagnostics())
            instrumentation.runOnMainSync { player.release() }
        }
    }
}
