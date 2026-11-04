@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.engine

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test

class CastStateIsolationAndroidTest {
    @Test
    fun localFailureDoesNotReplaceTheReceiversPlayingState() {
        OfflineWatchFixture("caststate01").use { fixture ->
            fixture.launch()
            fixture.instrumentation.runOnMainSync {
                val delegate = ExoPlayer.Builder(fixture.app).build()
                val receiver = object : ForwardingPlayer(delegate) {
                    override fun getPlaybackState() = Player.STATE_READY
                    override fun isPlaying() = true
                    override fun getCurrentPosition() = 15_000L
                    override fun getDuration() = 120_000L
                }
                try {
                    fixture.engine.attachCastPlayer(receiver)
                    failLocal(fixture.engine)
                    with(fixture.engine.snapshot.value) {
                        assertTrue(prepared)
                        assertTrue(isPlaying)
                        assertFalse(isBuffering)
                        assertNull(error)
                        assertNull(failureReport)
                        assertEquals(15_000L, positionMs)
                    }
                } finally {
                    fixture.engine.attachCastPlayer(null)
                    delegate.release()
                }
            }
        }
    }

    @Test
    fun localFailureStillAppearsWhenThePhoneHasADifferentVideo() {
        OfflineWatchFixture("caststate02").use { fixture ->
            fixture.launch()
            fixture.instrumentation.runOnMainSync {
                val receiver = ExoPlayer.Builder(fixture.app).build()
                try {
                    fixture.engine.attachCastPlayer(receiver)
                    fixture.engine.javaClass.getDeclaredField("receiverVideoId").apply {
                        isAccessible = true
                        set(fixture.engine, "other-video")
                    }
                    failLocal(fixture.engine)
                    assertNotNull(fixture.engine.snapshot.value.error)
                    assertFalse(fixture.engine.snapshot.value.prepared)
                } finally {
                    fixture.engine.attachCastPlayer(null)
                    receiver.release()
                }
            }
        }
    }

    private fun failLocal(engine: PlaybackEngine) {
        engine.javaClass.getDeclaredMethod("handleError", PlaybackException::class.java).apply {
            isAccessible = true
            invoke(engine, PlaybackException("Decoder failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
    }
}
