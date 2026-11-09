@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.cast

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.engine.CastSource
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DlnaRemotePlayerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val source = CastSource("video", "Title", 100, Format(), null)

    private class Engine : DlnaEngine {
        var pauseAction: suspend () -> Boolean = { true }
        override suspend fun search(timeoutMs: Long) = emptyList<DLNACast.Device>()
        override suspend fun cast(device: DLNACast.Device, url: String, title: String, mimeType: String) = true
        override suspend fun play() = true
        override suspend fun pause() = pauseAction()
        override suspend fun stop() = true
        override suspend fun seek(positionMs: Long) = true
        override suspend fun progress(): Pair<Long, Long>? = null
        override suspend fun transportState() = DLNACast.PlaybackState.PLAYING
        override fun clearProgressCache() = Unit
    }

    private fun withPlayer(block: (DlnaRemotePlayer, Engine) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = Engine()
        lateinit var player: DlnaRemotePlayer
        instrumentation.runOnMainSync {
            player = DlnaRemotePlayer(scope, engine) {}
            player.loading(source, 0)
            player.status(DLNACast.PlaybackState.PLAYING, 0, 100_000)
        }
        try { block(player, engine) } finally {
            instrumentation.runOnMainSync { scope.cancel(); player.release() }
        }
    }

    @Test fun commandTimeoutBecomesAVisiblePlayerError() = withPlayer { player, engine ->
        engine.pauseAction = { withTimeout(20) { delay(10_000); true } }
        instrumentation.runOnMainSync { player.pause() }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var failed = false
        while (!failed && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { failed = player.playerError != null }
            SystemClock.sleep(20)
        }
        assertTrue("Timed-out transport commands must report an error", failed)
    }

    @Test fun aLateRejectedCommandCannotFailTheNextItem() = withPlayer { player, engine ->
        val result = CompletableDeferred<Boolean>()
        engine.pauseAction = { result.await() }
        instrumentation.runOnMainSync {
            player.pause()
            player.reset()
            player.loading(source.copy(videoId = "next"), 0)
            result.complete(false)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("next", player.currentMediaItem?.mediaId)
            assertNull(player.playerError)
        }
    }

    @Test fun stalePlayingStatusCannotUndoPauseDuringAQualityReload() = withPlayer { player, _ ->
        instrumentation.runOnMainSync {
            player.pause()
            player.status(DLNACast.PlaybackState.PLAYING, 1_000, 100_000)
            assertFalse(player.playWhenReady)
            player.loading(source, 1_000, autoplay = false)
            player.status(DLNACast.PlaybackState.PLAYING, 1_000, 100_000)
            assertFalse(player.playWhenReady)
            player.status(DLNACast.PlaybackState.PAUSED, 1_000, 100_000)
            assertFalse(player.playWhenReady)
            player.play()
            player.status(DLNACast.PlaybackState.PAUSED, 1_000, 100_000)
            assertTrue(player.playWhenReady)
            player.status(DLNACast.PlaybackState.PLAYING, 1_000, 100_000)
            assertTrue(player.isPlaying)
        }
    }

    @Test fun aTransportErrorStaysIdleUntilReloaded() = withPlayer { player, _ ->
        instrumentation.runOnMainSync {
            player.status(DLNACast.PlaybackState.ERROR, 0, 100_000)
            assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
            assertNotNull(player.playerError)
            player.status(DLNACast.PlaybackState.PLAYING, 1_000, 100_000)
            assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
            player.loading(source, 0)
            assertNull(player.playerError)
        }
    }
}
