@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.TestJson
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in public-network acceptance: actual production engine, current quality and byte cache. */
class StartupSeekAcceptanceAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun productionStartupAndSeekRenderAndAdvance() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("videoId").orEmpty()
        assumeTrue(args.getString("network") == "1" && args.getString("latency") == "1" && id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var engine: PlaybackApi
        lateinit var player: ExoPlayer
        val frames = AtomicInteger()
        val frameAt = AtomicLong()
        val decoderStarts = AtomicInteger()
        val decoderMs = AtomicLong()
        val rows = mutableListOf<Map<String, Any?>>()
        var originalVolume = 1f
        var failure: String? = null
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { frameAt.set(SystemClock.elapsedRealtime()); frames.incrementAndGet() }
        }
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoderStarts.incrementAndGet(); decoderMs.addAndGet(initializationDurationMs)
            }
        }
        suspend fun measure(name: String, target: Long, action: () -> Unit) {
            val before = frames.get()
            val decodersBefore = decoderStarts.get()
            val began = AtomicLong()
            activity.scenario.onActivity {
                began.set(SystemClock.elapsedRealtime())
                action()
            }
            var frameMs: Long? = null
            var playingMs: Long? = null
            val deadline = began.get() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = engine.snapshot.value
                if (state.error != null) throw AssertionError("$name: ${state.error}")
                if (frames.get() > before && frameMs == null) frameMs = frameAt.get() - began.get()
                if (frameMs != null && state.isPlaying && state.positionMs >= target) {
                    playingMs = SystemClock.elapsedRealtime() - began.get()
                    break
                }
                delay(20)
            }
            val state = engine.snapshot.value
            rows += mapOf("phase" to name, "targetMs" to target, "frameMs" to frameMs,
                "playingMs" to playingMs, "positionMs" to state.positionMs,
                "quality" to state.activeQuality, "decoderInitializations" to decoderStarts.get() - decodersBefore)
            assertNotNull("$name did not render a new frame", frameMs)
            assertNotNull("$name did not start playing", playingMs)
            val advanceDeadline = SystemClock.elapsedRealtime() + 15_000
            while (engine.snapshot.value.positionMs < target + 750 && SystemClock.elapsedRealtime() < advanceDeadline) delay(50)
            assertTrue("$name did not sustain playback", engine.snapshot.value.positionMs >= target + 750)
        }
        try {
            instrumentation.runOnMainSync {
                engine = GlobalContext.get().get()
                engine.stop()
                player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
                originalVolume = player.volume; player.volume = 0f
                player.addListener(listener); player.addAnalyticsListener(analytics)
            }
            activity.scenario.onActivity { host -> engine.setVideoSurface(host.surface); engine.setVideoVisible(true) }
            measure("startup", 0) { engine.play(id) }
            for (target in listOf(60_000L, 300_000L, 60_000L, 0L)) {
                assertTrue("Probe requires a video longer than five minutes", engine.snapshot.value.durationMs > 310_000)
                measure("seek_$target", target) { engine.seekTo(target) }
            }
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName
            throw error
        } finally {
            File(instrumentation.targetContext.filesDir, "startup-seek-acceptance.json").writeText(TestJson.any(
                mapOf("videoId" to id, "cache" to "production_retained", "phases" to rows,
                    "videoDecoderTotalMs" to decoderMs.get(), "failure" to failure)))
            instrumentation.runOnMainSync {
                player.removeListener(listener); player.removeAnalyticsListener(analytics); player.volume = originalVolume
                engine.stop(); engine.setVideoSurface(null); engine.setVideoVisible(false)
            }
        }
    }
}
