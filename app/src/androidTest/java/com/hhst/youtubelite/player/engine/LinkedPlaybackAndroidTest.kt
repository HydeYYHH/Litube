@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class LinkedPlaybackAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun selectedVideosRenderAndAdvanceWithProductionRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val ids = args.getString("videoIds").orEmpty().split(',').filter { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        assumeTrue("Supply -e network 1 and -e videoIds", args.getString("network") == "1" && ids.isNotEmpty())
        val engine = GlobalContext.get().get<PlaybackApi>()
        val diagnostics = GlobalContext.get().get<ExtractionDiagnostics>()
        try {
            activity.scenario.onActivity { engine.setVideoSurface(it.surface); engine.setVideoVisible(true) }
            ids.forEach { id ->
                val mark = diagnostics.mark()
                val started = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync { engine.play(id) }
                while (SystemClock.elapsedRealtime() - started < 90_000) {
                    val state = engine.snapshot.value
                    if (state.videoId == id && (state.error != null || state.firstFrameRendered && state.isPlaying)) break
                    Thread.sleep(100)
                }
                val state = engine.snapshot.value
                assertNull(state.failureReport?.text ?: "Playback failed for $id", state.error)
                assertEquals(id, state.videoId)
                assertTrue("No first frame for $id", state.firstFrameRendered && state.isPlaying)
                val firstFrameMs = SystemClock.elapsedRealtime() - started
                val position = state.positionMs
                val deadline = SystemClock.elapsedRealtime() + 8_000
                while (engine.snapshot.value.positionMs <= position + 1000 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertTrue("No advancing playback for $id", engine.snapshot.value.positionMs > position + 1000)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("playback", "video=$id first_frame_ms=$firstFrameMs position_advanced=true elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                    putString("playerResponses", diagnostics.since(mark).filter { it.stage == "player-response" }
                        .joinToString(";") { "${it.profile}:${it.detail}:${it.elapsedMillis}ms" })
                })
            }
        } finally {
            instrumentation.runOnMainSync { engine.stop(); engine.setVideoSurface(null); engine.setVideoVisible(false) }
        }
    }
}
