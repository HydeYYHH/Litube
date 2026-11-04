@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.app.RemoteAction
import android.os.Build
import com.hhst.youtubelite.core.PipSupport
import androidx.media3.exoplayer.ExoPlayer
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlaybackActivity
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.QueueRepository
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class PlaybackReplayAndroidTest {
    @Test fun exhaustedQueueReplaysOnTheFirstTap() = replay(LoopMode.QUEUE_NEXT, hasNext = false)

    @Test fun pauseAtEndReplaysEvenWithAnotherQueuedVideo() = replay(LoopMode.PAUSE_AT_END, hasNext = true)

    private fun replay(mode: LoopMode, hasNext: Boolean) {
        OfflineWatchFixture().use { fixture ->
            val queue = GlobalContext.get().get<QueueRepository>()
            val player = fixture.engine.javaClass.getDeclaredField("player")
                .apply { isAccessible = true }.get(fixture.engine) as ExoPlayer
            val originalMode = fixture.engine.snapshot.value.loopMode
            try {
                fixture.instrumentation.runOnMainSync {
                    queue.setEnabled(true)
                    queue.add(QueueItem(fixture.id, fixture.url, "Current"))
                    if (hasNext) queue.add(QueueItem("replayNext1", "https://www.youtube.com/watch?v=replayNext1", "Next"))
                    fixture.model.onLoopMode(mode)
                }
                fixture.launch()
                fixture.instrumentation.runOnMainSync {
                    fixture.model.onLoopMode(mode)
                    player.seekTo(player.duration)
                }
                fixture.await { fixture.model.uiState.value.ended }
                fixture.instrumentation.runOnMainSync {
                    val state = fixture.model.uiState.value
                    assertEquals(fixture.id, state.videoId)
                    assertEquals(hasNext, state.hasNext)
                    assertTrue(state.userWantsPlay)
                    assertTrue(state.controlsVisible)
                    if (Build.VERSION.SDK_INT >= 26 && PipSupport.isSupported(fixture.app)) {
                        @Suppress("UNCHECKED_CAST")
                        val actions = PlaybackActivity::class.java.getDeclaredMethod("pipActions")
                            .apply { isAccessible = true }.invoke(fixture.activity) as List<RemoteAction>
                        assertEquals(fixture.app.getString(R.string.action_replay), actions[1].title.toString())
                    }
                    fixture.model.onPlayPause()
                }
                fixture.await { fixture.model.uiState.value.isPlaying && !fixture.model.uiState.value.ended }
                fixture.instrumentation.runOnMainSync {
                    assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                    assertTrue("Replay must rewind on the first tap", player.currentPosition < 5_000)
                    fixture.model.enterMiniPlayer()
                    player.seekTo(player.duration)
                }
                fixture.await { fixture.model.uiState.value.ended }
                fixture.instrumentation.runOnMainSync { fixture.model.onPlayPause() }
                fixture.await { fixture.model.uiState.value.isPlaying && !fixture.model.uiState.value.ended }
                fixture.instrumentation.runOnMainSync {
                    assertTrue(fixture.model.uiState.value.mini)
                    assertTrue(player.currentPosition < 5_000)
                }
            } finally {
                fixture.instrumentation.runOnMainSync { fixture.model.onLoopMode(originalMode) }
            }
        }
    }
}
