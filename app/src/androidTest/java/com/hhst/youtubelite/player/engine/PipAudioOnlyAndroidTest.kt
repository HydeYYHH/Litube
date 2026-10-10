@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.R
import com.hhst.youtubelite.browser.PageOrigin
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.core.PipAutoEnter
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.QueueRepository
import kotlinx.coroutines.*
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class PipAudioOnlyAndroidTest {
    @Test fun headphoneActionClosesPipKeepsAudioAndNotificationAndRestoresVideo() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val audio = instrumentation.context.assets.open("player/audio-original/audio.ts").use { it.readBytes() }
        val video = instrumentation.context.assets.open("player/quality-recovery/360.ts").use { it.readBytes() }
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/master.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish dubbed",LANGUAGE="es",DEFAULT=YES,AUTOSELECT=YES,URI="spanish.m3u8"
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English original",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="original.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio"
                    video.m3u8
                """.trimIndent())
                "/audio.ts" -> newFixedLengthResponse(Response.Status.OK, "video/mp2t", audio.inputStream(), audio.size.toLong())
                "/video.ts" -> newFixedLengthResponse(Response.Status.OK, "video/mp2t", video.inputStream(), video.size.toLong())
                else -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", """
                    #EXTM3U
                    #EXT-X-TARGETDURATION:21
                    #EXT-X-MEDIA-SEQUENCE:0
                    ${List(6) { "#EXTINF:20.0,\n${if (session.uri == "/video.m3u8") "video.ts" else "audio.ts"}" }.joinToString("\n#EXT-X-DISCONTINUITY\n")}
                    #EXT-X-ENDLIST
                """.trimIndent())
            }
        }
        server.start()
        val stream = Stream().apply { hlsUrl = "http://127.0.0.1:${server.listeningPort}/master.m3u8" }
        val fixtureCache = streamCache(stream, title = "PiP audio regression", durationSeconds = 120)
        val engine = GlobalContext.get().get<PlaybackApi>() as PlaybackEngine
        val model = GlobalContext.get().get<PlayerViewModel>()
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val queue = GlobalContext.get().get<QueueRepository>()
        val previousQueue = queue.state.value
        val keys = listOf(PreferenceKeys.ENABLE_PIP, PreferenceKeys.ENABLE_BACKGROUND_PLAY,
            PreferenceKeys.REMEMBER_LAST_POSITION,
            PreferenceKeys.SKIP_SPONSORS, PreferenceKeys.SKIP_SELF_PROMO,
            PreferenceKeys.SKIP_POI_HIGHLIGHT)
        val previous = keys.associateWith(prefs::isEnabled)
        val extractorField = engine.javaClass.getDeclaredField("extractor").apply { isAccessible = true }
        val oldExtractor = extractorField.get(engine)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
        var scenario: ActivityScenario<MainActivity>? = null
        lateinit var host: MainActivity
        fun await(condition: () -> Boolean) {
            var ready = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) Thread.sleep(50)
            }
            if (!ready) {
                var detail = ""
                instrumentation.runOnMainSync {
                    detail = "PiP condition timed out: destroyed=${host.isDestroyed}, audioOnly=${model.audioOnlyBackground}, inPip=${host.isInPictureInPictureMode}, playerState=${player.playbackState}, wantsPlay=${player.playWhenReady}, suppression=${player.playbackSuppressionReason}, tracks=${player.currentTracks.groups.map { it.type to it.isSelected }}, ui=${model.uiState.value}, auto=${PipAutoEnter.lastRequestedAutoEnter}, paramError=${PipAutoEnter.lastPushError}, ${engine.snapshot.value}"
                }
                fail(detail)
            }
        }
        fun selectedVideo() = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        fun params() = MainActivity::class.java.getDeclaredMethod("pipParams").apply { isAccessible = true }.invoke(host) as PictureInPictureParams
        @Suppress("UNCHECKED_CAST")
        fun actions(): List<RemoteAction> = PictureInPictureParams::class.java.getMethod("getActions").invoke(params()) as List<RemoteAction>
        try {
            keys.forEach { prefs.setEnabled(it, it == PreferenceKeys.ENABLE_PIP || it == PreferenceKeys.ENABLE_BACKGROUND_PLAY) }
            queue.clear()
            queue.setEnabled(true)
            queue.add(QueueItem("audioPip001", VideoId.watchUrl("audioPip001"), "First"))
            extractorField.set(engine, Extractor(GlobalContext.get().get<HttpDownloader>(), fixtureCache, scope))
            scenario = ActivityScenario.launch(Intent(Intent.ACTION_VIEW,
                Uri.parse(VideoId.watchUrl("audioPip001")), app, MainActivity::class.java))
            scenario!!.onActivity { host = it }
            instrumentation.runOnMainSync { model.playVideo(VideoId.watchUrl("audioPip001"), PageOrigin.HOST) }
            await { player.isPlaying && model.uiState.value.isPlaying && selectedVideo() && engine.snapshot.value.audioTracks.size == 2 }
            assertEquals("hls:en:English original", engine.snapshot.value.audioTrackKey)
            await { !model.uiState.value.hasNext }
            assertEquals(3, actions().size)
            assertFalse(actions().single { it.title == app.getString(R.string.action_next) }.isEnabled)
            prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, false)
            assertEquals(listOf(R.string.action_previous, R.string.action_pause, R.string.action_next)
                .map(app::getString), actions().map { it.title.toString() })
            prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
            queue.add(QueueItem("audioPip002", VideoId.watchUrl("audioPip002"), "Second"))
            await { model.uiState.value.hasNext }
            val icons = listOf(R.drawable.ic_headphones, R.drawable.ic_play, R.drawable.ic_pause,
                R.drawable.ic_pip_next, R.drawable.ic_pip_previous).map { app.getDrawable(it)!! }
            assertEquals(1, icons.map { it.intrinsicWidth to it.intrinsicHeight }.distinct().size)
            val inkHeights = icons.map { icon ->
                val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 240, 240)
                icon.draw(Canvas(bitmap))
                val rows = (0 until 240).filter { y ->
                    (0 until 240).any { x -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 127 }
                }
                bitmap.recycle()
                rows.last() - rows.first() + 1
            }
            assertTrue("PiP icons must have comparable visible heights: $inkHeights",
                inkHeights.max() - inkHeights.min() <= 20)
            if (Build.VERSION.SDK_INT >= 31) {
                await { PipAutoEnter.lastRequestedAutoEnter &&
                    PipAutoEnter.lastPushError == null }
                DeviceEvidence.shell("input keyevent KEYCODE_HOME")
            } else instrumentation.runOnMainSync { assertTrue(host.enterPictureInPictureMode(params())) }
            await { host.isInPictureInPictureMode }
            actions().single { it.title == app.getString(R.string.action_next) }.actionIntent.send()
            await { engine.snapshot.value.videoId == "audioPip002" && player.isPlaying && model.uiState.value.isPlaying && selectedVideo() }
            prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, false)
            await { model.uiState.value.hasPrevious }
            actions().single { it.title == app.getString(R.string.action_pause) }.actionIntent.send()
            await { !player.isPlaying && !model.uiState.value.isPlaying }
            instrumentation.runOnMainSync { model.onSeek(0) }
            await { player.currentPosition < 1_000 }
            actions().single { it.title == app.getString(R.string.action_previous) }.actionIntent.send()
            await { engine.snapshot.value.videoId == "audioPip001" && player.isPlaying && model.uiState.value.isPlaying && selectedVideo() }
            prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
            val pipActions = actions()
            assertEquals(listOf(R.string.player_audio_only, R.string.action_pause, R.string.action_next)
                .map(app::getString), pipActions.map { it.title.toString() })
            assertTrue(pipActions.last().isEnabled)
            pipActions.single { it.title == app.getString(R.string.action_pause) }.actionIntent.send()
            await { !player.isPlaying && !model.uiState.value.isPlaying }
            actions().single { it.title == app.getString(R.string.action_play) }.actionIntent.send()
            await { player.isPlaying && model.uiState.value.isPlaying }
            val point = IntArray(2)
            var tapX = 0
            var tapY = 0
            instrumentation.runOnMainSync {
                host.window.decorView.getLocationOnScreen(point)
                tapX = point[0] + host.window.decorView.width / 2
                tapY = point[1] + host.window.decorView.height / 2
            }
            DeviceEvidence.shell("input tap $tapX $tapY")
            Thread.sleep(400)
            DeviceEvidence.captureScene("pip-headphones-pause-next")
            var position = 0L
            instrumentation.runOnMainSync { position = player.currentPosition }
            pipActions.single { it.title == app.getString(R.string.player_audio_only) }.actionIntent.send()
            await { host.isDestroyed && model.audioOnlyBackground && player.isPlaying && !selectedVideo() }
            await { player.currentPosition > position + 500 }
            val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue("Media notification must survive", notifications.isNotEmpty())
            assertEquals("hls:en:English original", engine.snapshot.value.audioTrackKey)
            DeviceEvidence.captureScene("issue-331-audio-without-pip")
            scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            scenario!!.onActivity { host = it }
            await {
                val surface = engine.javaClass.getDeclaredField("videoSurface").apply { isAccessible = true }.get(engine) as? SurfaceView
                player.videoDecoderCounters?.ensureUpdated()
                !model.audioOnlyBackground && !model.uiState.value.mini && player.isPlaying && selectedVideo() && surface?.isShown == true &&
                    surface.holder.surface.isValid && surface.windowToken == host.window.decorView.windowToken &&
                    surface.width >= host.window.decorView.width * 3 / 4 &&
                    (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            Thread.sleep(400)
            instrumentation.runOnMainSync { assertTrue(player.currentPosition >= position) }
            assertSame(player, engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine))
            DeviceEvidence.captureScene("issue-331-video-restored")
            // Closing playback must end the audio-only session, so reopening the
            // app does not revive a video the user explicitly stopped.
            instrumentation.runOnMainSync { model.playAudioOnlyInBackground() }
            instrumentation.runOnMainSync { model.onMiniClose() }
            await { !model.audioOnlyBackground && !model.uiState.value.visible && !player.isPlaying }
            instrumentation.runOnMainSync {
                assertFalse(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
            }
            DeviceEvidence.writeJson("issue-331-audio-only.json", "{\"pipClosed\":true,\"audioContinues\":true,\"videoDisabled\":true,\"notificationSurvives\":true,\"videoRestored\":true,\"samePlayer\":true,\"closingPlaybackEndsAudioSession\":true}")
            DeviceEvidence.writeJson("pip-buttons-followup.json", "{\"threeSlots\":true,\"unavailableNextStillPresent\":true,\"nextWorks\":true,\"previousWorksWithoutBackgroundPlay\":true,\"headphonesOnlyReplacePrevious\":true,\"equalIconDimensions\":true}")
        } finally {
            instrumentation.runOnMainSync { model.returnFromAudioOnlyBackground(); model.onMiniClose(); engine.stop() }
            scenario?.close()
            extractorField.set(engine, oldExtractor)
            previous.forEach { (key, value) -> prefs.setEnabled(key, value) }
            queue.clear()
            previousQueue.items.forEach(queue::add)
            queue.setEnabled(previousQueue.enabled)
            scope.cancel(); server.stop()
        }
    }
}
