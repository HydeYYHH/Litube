@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.watch.WatchActivity
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueRepository
import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext

/** Runs the production player against a local HLS fixture. */
internal class OfflineWatchFixture(val id: String = "menuTest001", refuseMedia: Boolean = false,
    videoAsset: String = "player/quality-recovery/360.ts",
    onStreamRead: () -> Unit = {}) : Closeable {
    var mediaRefused = refuseMedia
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext
    val engine = GlobalContext.get().get<PlaybackApi>() as PlaybackEngine
    val model = GlobalContext.get().get<PlayerViewModel>()
    val prefs = GlobalContext.get().get<ExtensionManager>()
    private val queue = GlobalContext.get().get<QueueRepository>()
    private val oldQueue = queue.state.value
    private val oldPreferences = prefs.allPreferences()
    private val oldHapticStrength = prefs.hapticStrength()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractorField = engine.javaClass.getDeclaredField("extractor").apply { isAccessible = true }
    private val originalExtractor = extractorField.get(engine)
    private val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
    private val video = instrumentation.context.assets.open(videoAsset).use { it.readBytes() }
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = if (mediaRefused) {
            newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Unavailable")
        } else when (session.uri) {
            "/audio.ts", "/video.ts" -> {
                val bytes = if (session.uri == "/audio.ts") audio else video
                newFixedLengthResponse(Response.Status.OK, "video/mp2t", bytes.inputStream(), bytes.size.toLong())
            }
            "/master.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Original",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio"
                video.m3u8
            """.trimIndent())
            else -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", """
                #EXTM3U
                #EXT-X-TARGETDURATION:21
                #EXT-X-MEDIA-SEQUENCE:0
                ${List(30) { "#EXTINF:20.0,\n${if (session.uri == "/audio.m3u8") "audio.ts" else "video.ts"}" }.joinToString("\n#EXT-X-DISCONTINUITY\n")}
                #EXT-X-ENDLIST
            """.trimIndent())
        }
    }
    lateinit var activity: WatchActivity
    private var scenario: ActivityScenario<WatchActivity>? = null
    val url get() = VideoId.watchUrl(id)

    init {
        server.start()
        val stream = Stream().apply { hlsUrl = "http://127.0.0.1:${server.listeningPort}/master.m3u8" }
        instrumentation.runOnMainSync {
            engine.stop()
            queue.clear(); queue.setEnabled(false)
            oldPreferences.keys.filter { it.startsWith("skip_") }.forEach { prefs.setEnabled(it, false) }
            prefs.setEnabled(PreferenceKeys.ENABLE_PIP, true)
            prefs.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, true)
            // This fixture only advertises 360p; an unrelated remembered pin
            // must not turn a surface/navigation regression into a quality failure.
            prefs.setEnabled(PreferenceKeys.REMEMBER_QUALITY, false)
            // UI fixtures reuse video IDs. Resume tests explicitly opt back in;
            // unrelated navigation tests must not inherit another run's position.
            prefs.setEnabled(PreferenceKeys.REMEMBER_LAST_POSITION, false)
            // A short stream ends mid-test on slow renderers and collapses fullscreen first; the finite
            // HLS playlist above carries the same 600 seconds for the swipe/rotate flow.
            extractorField.set(engine, Extractor(streamCache(stream, "Offline watch", 600, onStreamRead = onStreamRead), scope))
        }
    }

    fun launch(expectFailure: Boolean = false, source: android.app.Activity? = null,
        startSeconds: Long? = null, awaitReady: Boolean = true, settleUi: () -> Unit = {}) {
        if (source == null) {
            val intent = WatchActivity.intent(app, id)
                .apply { startSeconds?.let { putExtra(WatchActivity.EXTRA_START_SECONDS, it) } }
            scenario = ActivityScenario.launch(intent)
            scenario!!.onActivity { activity = it }
        } else {
            val monitor = instrumentation.addMonitor(WatchActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { source.startActivity(WatchActivity.intent(source, id)) }
                activity = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) as WatchActivity
            } finally { instrumentation.removeMonitor(monitor) }
        }
        // Fixture mode gates off the activity's own openVideo block (no media runtime), so the fixture
        // drives the same production openVideo entry point itself; the explicit start (0 included) rides along.
        instrumentation.runOnMainSync { model.openVideo(id, startSeconds) }
        settleUi()
        if (awaitReady) await { model.uiState.value.visible &&
            if (expectFailure) engine.snapshot.value.error != null else engine.snapshot.value.isPlaying && surface() != null }
    }

    /** Re-creates the hosted activity to exercise the savedInstanceState path. */
    fun recreate() {
        requireNotNull(scenario).recreate()
        scenario!!.onActivity { activity = it }
    }

    private val remoteResumeField by lazy {
        engine.javaClass.getDeclaredField("remoteResumePosition").apply { isAccessible = true }
    }
    private var originalRemoteResume: Any? = null

    /** Stubs the account resume loader; call [restoreRemoteResume] in finally.
        Enables REMEMBER_LAST_POSITION so the engine consults the stub (close() restores prefs). */
    fun stubRemoteResume(load: suspend (String) -> Long?) {
        originalRemoteResume = remoteResumeField.get(engine)
        instrumentation.runOnMainSync {
            prefs.setEnabled(PreferenceKeys.REMEMBER_LAST_POSITION, true)
            remoteResumeField.set(engine, load)
        }
    }

    fun restoreRemoteResume() {
        instrumentation.runOnMainSync { remoteResumeField.set(engine, originalRemoteResume) }
    }

    fun await(message: String = "Offline condition failed", condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) Thread.sleep(50)
        }
        assertTrue("$message: ${engine.snapshot.value}", ready)
    }

    fun surface(): android.view.SurfaceView? {
        fun find(view: View): android.view.SurfaceView? {
            if (view is android.view.SurfaceView && view.isShown) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(activity.window.decorView)
    }

    override fun close() {
        instrumentation.runOnMainSync {
            model.onMiniClose(); engine.stop()
            if (::activity.isInitialized && !activity.isFinishing) activity.finishAndRemoveTask()
        }
        try { scenario?.close() } catch (_: Exception) {
            // Restore shared engine dependencies even if the host has already closed.
        } finally {
            instrumentation.runOnMainSync {
                extractorField.set(engine, originalExtractor)
                oldPreferences.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
                prefs.setHapticStrength(oldHapticStrength)
                queue.clear(); oldQueue.items.forEach(queue::add); queue.setEnabled(oldQueue.enabled)
            }
            // Instrumentation can end the process after the last fixture; commit() drains async preference writes so settings cannot leak to the next run.
            check(app.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE).edit().commit())
            scope.cancel(); server.stop()
        }
    }
}
