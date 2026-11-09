package com.hhst.youtubelite.shorts

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.core.TestJson
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticEvent
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.domain.HomeContent
import com.hhst.youtubelite.player.engine.PlaybackApi
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Real Home navigation, with an optional earlier call to the existing shared preparation. */
class HomeQuickEntryAndroidTest {
    @Test fun measureQuickEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        val early = args.getString("early") == "1"
        val browse = args.getString("browseMs")?.toLong() ?: 1000L
        val output = File(instrumentation.targetContext.filesDir, "home-quick-entry.json")
        output.delete()
        val entry = GlobalContext.get().get<ContentRepositories>().shortsEntry
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = SystemClock.elapsedRealtime()
        val recorder = AppLog::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(AppLog)!!
        val recent = recorder.javaClass.getDeclaredMethod("recent").apply { isAccessible = true }
        val captured = linkedMapOf<Long, Map<String, Any?>>()
        fun sample() {
            @Suppress("UNCHECKED_CAST")
            val events = recent.invoke(recorder) as List<DiagnosticEvent>
            events.filter { it.elapsedMs >= started }.forEach { event ->
                captured.putIfAbsent(event.sequence, mapOf("ms" to event.elapsedMs-started,
                    "event" to event.event, "video" to event.context?.videoId, "fields" to event.fields))
            }
        }
        try {
            ActivityScenario.launch(HomeActivity::class.java).use { home ->
                var feedAt: Long? = null
                val feedDeadline = started + 25_000
                while (SystemClock.elapsedRealtime() < feedDeadline && feedAt == null) {
                    home.onActivity {
                        if (it.homeViewModel.uiState.value.content is HomeContent.Feed) feedAt = SystemClock.elapsedRealtime()
                    }
                    if (feedAt == null) Thread.sleep(20)
                }
                assertNotNull("Home did not load", feedAt)
                val preparation = if (early) scope.async { entry.prepare() } else null
                val clickAt = feedAt!! + browse
                while (SystemClock.elapsedRealtime() < clickAt) Thread.sleep(10)
                val preparedAtClick = preparation?.let { it.isCompleted && !it.isCancelled }
                val clicked = SystemClock.elapsedRealtime()
                home.onActivity { it.startActivity(ShortsActivity.intent(it)) }
                val engine = GlobalContext.get().get<PlaybackApi>()
                var pageAt: Long? = null
                var frameAt: Long? = null
                var playingAt: Long? = null
                var id: String? = null
                val frameDeadline = clicked + 25_000
                while (SystemClock.elapsedRealtime() < frameDeadline) {
                    sample()
                    instrumentation.runOnMainSync {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ShortsActivity>().firstOrNull()
                        val item = activity?.shortsViewModel?.uiState?.value?.currentItemId
                        if (item != null && pageAt == null) { id = item; pageAt = SystemClock.elapsedRealtime() }
                    }
                    val state = engine.snapshot.value
                    if (state.videoId == id && id != null) {
                        if (state.firstFrameRendered && frameAt == null) frameAt = SystemClock.elapsedRealtime()
                        if (state.isPlaying && playingAt == null) playingAt = SystemClock.elapsedRealtime()
                        if (state.error != null || (frameAt != null && playingAt != null)) break
                    }
                    Thread.sleep(10)
                }
                val state = engine.snapshot.value
                sample()
                output.writeText(TestJson.any(mapOf(
                    "early" to early, "browseMs" to browse, "homeFeedMs" to feedAt!!-started,
                    "clickMs" to clicked-started, "events" to captured.values.toList(),
                    "preparedAtClick" to preparedAtClick, "pageMs" to pageAt?.minus(clicked),
                    "frameMs" to frameAt?.minus(clicked), "playingMs" to playingAt?.minus(clicked),
                    "video" to id, "height" to state.videoHeight, "error" to state.error)))
                assertNotNull("Shorts did not load", pageAt)
                assertNull(state.error)
                assertNotNull("No real first frame", frameAt)
                assertNotNull("Playback did not start", playingAt)
            }
        } finally { scope.cancel() }
    }
}
