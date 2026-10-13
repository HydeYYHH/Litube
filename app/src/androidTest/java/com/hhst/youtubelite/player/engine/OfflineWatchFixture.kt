@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.browser.PageOrigin
import com.hhst.youtubelite.diagnostics.BrowserDiagnostics
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.HttpDownloader
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueRepository
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext

/** Runs the production player against a local HLS fixture. */
internal class OfflineWatchFixture(val id: String = "menuTest001") : Closeable {
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
    private val video = instrumentation.context.assets.open("player/quality-recovery/360.ts").use { it.readBytes() }
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
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
                ${List(6) { "#EXTINF:20.0,\n${if (session.uri == "/audio.m3u8") "audio.ts" else "video.ts"}" }.joinToString("\n#EXT-X-DISCONTINUITY\n")}
                #EXT-X-ENDLIST
            """.trimIndent())
        }
    }
    lateinit var activity: MainActivity
    private var scenario: ActivityScenario<MainActivity>? = null
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
            extractorField.set(engine, Extractor(GlobalContext.get().get<HttpDownloader>(), streamCache(stream, "Offline watch", 120), scope))
        }
    }

    fun launch(settleUi: () -> Unit = {}) {
        scenario = ActivityScenario.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url), app, MainActivity::class.java))
        scenario!!.onActivity { activity = it }
        instrumentation.runOnMainSync { model.playVideo(url, PageOrigin.HOST) }
        settleUi()
        await { model.uiState.value.visible && engine.snapshot.value.isPlaying && findWebView() != null }
    }

    fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) Thread.sleep(50)
        }
        assertTrue("Offline condition failed: ${engine.snapshot.value}", ready)
    }

    fun webView(): WebView = checkNotNull(findWebView())

    private fun findWebView(): WebView? {
        val active = ViewModelProvider(activity)[BrowserViewModel::class.java].uiState.value.activeId
        fun find(view: View): WebView? {
            if (view is WebView && view.isShown && VideoId.parse(view.url) == id &&
                BrowserDiagnostics.context(view)?.tabId == active) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(activity.window.decorView)
    }

    override fun close() {
        instrumentation.runOnMainSync {
            model.onMiniClose(); engine.stop()
            if (::activity.isInitialized && !activity.isFinishing) activity.finish()
        }
        try { scenario?.close() } finally {
            instrumentation.runOnMainSync {
                extractorField.set(engine, originalExtractor)
                oldPreferences.forEach { (key, enabled) -> prefs.setEnabled(key, enabled) }
                prefs.setHapticStrength(oldHapticStrength)
                queue.clear(); oldQueue.items.forEach(queue::add); queue.setEnabled(oldQueue.enabled)
            }
            scope.cancel(); server.stop()
        }
    }
}
