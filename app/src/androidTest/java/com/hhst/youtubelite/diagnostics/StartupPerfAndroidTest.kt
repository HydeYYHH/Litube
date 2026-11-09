@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.diagnostics

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.watch.WatchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Startup-performance driver: adds only intent markers; phase numbers come from existing
 * telemetry. Orchestration (force-stop per rep, process reuse) lives in the shell.
 * `startupScenario` labels the cache condition (cold/warm/replay/next); `startupSecond`
 * pins a different video for the second launch — without it the second launch replays the
 * same video, because engine.play short-circuits an already prepared item.
 */
class StartupPerfAndroidTest {
    @Test fun startupPhasesUnderExplicitCacheConditions() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("startupPerf") == "1")
        val videoId = arguments.getString("videoId")?.trim().orEmpty()
        assumeTrue(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val scenario = arguments.getString("startupScenario", "cold")!!.trim()
        val rep = arguments.getString("startupRep", "1")!!.trim()
        val launches = (arguments.getString("startupLaunches", "1")?.toIntOrNull() ?: 1).coerceIn(1, 2)
        val second = arguments.getString("startupSecond")?.trim().orEmpty()
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var engine: PlaybackApi
        instrumentation.runOnMainSync { engine = GlobalContext.get().get() }

        repeat(launches) { index ->
            val launch = index + 1
            val target = if (launch == 2 && second != null) second else videoId
            if (launch == 2) {
                instrumentation.runOnMainSync { engine.stop() }
                delay(500)
            }
            val run = "$scenario-$rep-$launch"
            AppLog.event(AppLog.Category.APP, "startup_perf.begin", mapOf(
                "run" to run, "scenario" to scenario, "rep" to rep, "launch" to launch,
                "video_id" to target, "condition" to when {
                    scenario == "next" && launch == 1 -> "next_setup_playback"
                    scenario == "next" -> "related_id_resolved"
                    launch == 1 && scenario == "warm" -> "fresh_process"
                    launch == 1 -> "fresh_process_first_playback"
                    second != null -> "warm_process_new_video"
                    else -> "warm_process_replay"
                }))
            val started = SystemClock.elapsedRealtime()
            var finished = false
            val deadline = started + 90_000
            ActivityScenario.launch<WatchActivity>(WatchActivity.intent(
                instrumentation.targetContext, target)).use {
                while (SystemClock.elapsedRealtime() < deadline) {
                    val state = engine.snapshot.value
                    if (state.error != null) break
                    if (state.videoId == target && state.firstFrameRendered) { finished = true; break }
                    delay(50)
                }
                if (scenario == "next" && finished) {
                    // Measure the skip while the watch surface is still attached, the
                    // production Next condition; after close() the surface is gone and the
                    // first-frame gate never opens.
                    delay(300)
                    val state = engine.snapshot.value
                    AppLog.event(AppLog.Category.APP, "startup_perf.end", mapOf(
                        "run" to run, "scenario" to scenario, "rep" to rep, "launch" to launch,
                        "video_id" to target,
                        "elapsed_ms" to (SystemClock.elapsedRealtime() - started),
                        "first_frame" to finished, "playing" to state.isPlaying,
                        "failed" to (state.error != null), "position_ms" to state.positionMs))
                    nextVideoStartup(instrumentation, engine, run, rep)
                    return@repeat
                }
                // Keep the playback surface and foreground alive through the end marker and
                // log flush; closing first can trip the device runner's foreground
                // protection during cleanup.
                delay(300)
                val state = engine.snapshot.value
                val elapsed = SystemClock.elapsedRealtime() - started
                AppLog.event(AppLog.Category.APP, "startup_perf.end", mapOf(
                    "run" to run, "scenario" to scenario, "rep" to rep, "launch" to launch,
                    "video_id" to target, "elapsed_ms" to elapsed, "first_frame" to finished,
                    "playing" to state.isPlaying, "failed" to (state.error != null),
                    "position_ms" to state.positionMs))
                try {
                    AppLog.flush(3_000)
                    assertTrue("Startup did not reach first frame in ${elapsed}ms (run $run)", finished)
                } finally {
                    // This task belongs to the test. Avoid the interactive mini-player
                    // handoff in finish(), including when the startup assertion fails.
                    it.onActivity { host -> host.finishAndRemoveTask() }
                }
            }
            if (scenario == "next") {
                nextVideoStartup(instrumentation, engine, run, rep)
                return@repeat
            }
        }
    }

    private suspend fun nextVideoStartup(
        instrumentation: android.app.Instrumentation,
        engine: PlaybackApi, run: String, rep: String) {
        val viewModel = GlobalContext.get().get<PlayerViewModel>()
        val settleDeadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < settleDeadline && !viewModel.uiState.value.hasNext) delay(200)
        val current = engine.snapshot.value.videoId ?: return
        val nextRun = "$run-next"
        AppLog.event(AppLog.Category.APP, "startup_perf.begin", mapOf(
            "run" to nextRun, "scenario" to "next", "rep" to rep, "launch" to 2,
            "video_id" to "", "condition" to "related_id_resolved"))
        val started = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync { viewModel.skipToNext() }
        var nextId: String? = null
        var finished = false
        val deadline = started + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = engine.snapshot.value
            if (state.error != null) break
            if (state.videoId != null && state.videoId != current && state.firstFrameRendered) {
                nextId = state.videoId; finished = true; break
            }
            delay(50)
        }
        delay(300)
        val elapsed = SystemClock.elapsedRealtime() - started
        AppLog.event(AppLog.Category.APP, "startup_perf.end", mapOf(
            "run" to nextRun, "scenario" to "next", "rep" to rep, "launch" to 2,
            "video_id" to nextId.orEmpty(), "elapsed_ms" to elapsed, "first_frame" to finished,
            "playing" to engine.snapshot.value.isPlaying,
            "failed" to (engine.snapshot.value.error != null)))
        assertTrue("Next-video startup did not reach first frame in ${elapsed}ms (run $nextRun)", finished)
        AppLog.flush(3_000)
    }
}
