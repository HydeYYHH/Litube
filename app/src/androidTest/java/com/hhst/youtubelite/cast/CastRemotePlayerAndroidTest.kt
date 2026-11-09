@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.cast

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.player.engine.CastSource
import org.junit.Assert.*
import org.junit.Test

class CastRemotePlayerAndroidTest {
    @Test fun stalePlayingStatusCannotUndoPauseDuringAQualityReload() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val session = CastV2Session.forDefaultMediaReceiver("127.0.0.1", 1, object : CastV2Session.Listener {
                override fun onConnected() = Unit
                override fun onMediaStatus(playerState: String?, positionMs: Long, durationMs: Long, idleReason: String?) = Unit
                override fun onLaunchError(reason: String) = Unit
                override fun onChannelError(reason: String) = Unit
                override fun onClosed() = Unit
            })
            val player = CastRemotePlayer(session)
            val source = CastSource("video", "Title", 100, null, null)
            try {
                player.loading("http://receiver/manifest?g=1", source, 0)
                player.status("PLAYING", 1_000, 100_000, null)
                player.pause()
                player.status("PLAYING", 1_000, 100_000, null)
                assertFalse(player.playWhenReady)
                player.loading("http://receiver/manifest?g=2", source, 1_000, autoplay = false)
                player.status("PLAYING", 1_000, 100_000, null)
                assertFalse(player.playWhenReady)
                player.status("PAUSED", 1_000, 100_000, null)
                assertFalse(player.playWhenReady)
                player.play()
                player.status("PAUSED", 1_000, 100_000, null)
                assertTrue(player.playWhenReady)
                player.status("PLAYING", 1_000, 100_000, null)
                assertTrue(player.isPlaying)
            } finally { player.release(); session.close() }
        }
    }
}
