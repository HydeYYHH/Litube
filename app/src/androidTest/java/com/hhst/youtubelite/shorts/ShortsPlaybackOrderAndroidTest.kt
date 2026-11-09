@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.domain.*
import com.hhst.youtubelite.shorts.presentation.ShortsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class ShortsPlaybackOrderAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun automaticSelectionMovesThePagerAndFollowingSelectionStillWorks() {
        val state = mutableStateOf(ShortsUiState(
            content = ShortsContent.Ready(ShortsPage(FixtureShortsRepository.sample())),
            currentItemId = "short-1",
        ))
        compose.setContent {
            ShortsScreen(state.value, { action ->
                if (action is ShortsAction.SelectItem) state.value = state.value.copy(currentItemId = action.itemId)
            })
        }
        compose.onNodeWithTag("short-title-short-1").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(currentItemId = "short-2") }
        compose.onNodeWithTag("short-title-short-2").assertIsDisplayed()
        compose.runOnIdle { assertEquals("short-2", state.value.currentItemId) }
        compose.runOnIdle { state.value = state.value.copy(currentItemId = "short-3") }
        compose.onNodeWithTag("short-title-short-3").assertIsDisplayed()
    }

    @Test fun shortsModesApplyRepeatAndPersistIndependentlyOfWatchMode() {
        val engine = GlobalContext.get().get<PlaybackApi>()
        val cache = GlobalContext.get().get<JsonCache>()
        val keys = listOf("player:loop_mode", "player:shorts_loop_mode")
        val saved = keys.associateWith { cache.get(it, String::class.java) }
        compose.runOnIdle {
            val player = engine.javaClass.getDeclaredField("player").apply { isAccessible = true }.get(engine) as ExoPlayer
            try {
                engine.setShortsMode(false)
                engine.setLoopMode(LoopMode.QUEUE_RANDOM)
                engine.setShortsMode(true)
                for (mode in listOf(LoopMode.PAUSE_AT_END, LoopMode.LOOP_ONE, LoopMode.QUEUE_NEXT)) {
                    engine.setLoopMode(mode)
                    assertEquals(mode, engine.snapshot.value.loopMode)
                    assertEquals(if (mode == LoopMode.LOOP_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF, player.repeatMode)
                    assertEquals(mode.persistedValue.toString(), cache.get(keys[1], String::class.java))
                    engine.setShortsMode(false)
                    assertEquals(LoopMode.QUEUE_RANDOM, engine.snapshot.value.loopMode)
                    engine.setShortsMode(true)
                    assertEquals(mode, engine.snapshot.value.loopMode)
                }
            } finally {
                saved.forEach { (key, value) ->
                    if (value == null) cache.invalidate(key) else cache.put(key, value, 365L * 24 * 60 * 60 * 1000)
                }
                engine.setShortsMode(false)
            }
        }
    }

    @Test fun pauseAtEndCanReplayAndLoopOneRestartsRealShortMedia() {
        val cache = GlobalContext.get().get<JsonCache>()
        val key = "player:shorts_loop_mode"
        val saved = cache.get(key, String::class.java)
        OfflineWatchFixture().use { fixture ->
            val player = fixture.engine.javaClass.getDeclaredField("player")
                .apply { isAccessible = true }.get(fixture.engine) as ExoPlayer
            try {
                fixture.instrumentation.runOnMainSync {
                    fixture.model.onShortsOpened()
                    fixture.model.openShort(fixture.id)
                    fixture.model.onLoopMode(LoopMode.PAUSE_AT_END)
                }
                // Exercise the real media timeline without depending on the watch host's surface.
                fixture.await { fixture.engine.snapshot.value.prepared && fixture.engine.snapshot.value.isPlaying }
                fixture.instrumentation.runOnMainSync {
                    player.seekTo(player.duration)
                }
                fixture.await { fixture.model.uiState.value.ended }
                fixture.instrumentation.runOnMainSync {
                    assertFalse(fixture.model.uiState.value.isPlaying)
                    fixture.model.onPlayPause()
                }
                fixture.await { fixture.model.uiState.value.isPlaying && !fixture.model.uiState.value.ended }
                fixture.instrumentation.runOnMainSync {
                    assertFalse(fixture.model.uiState.value.shortPausedByUser)
                    assertTrue(player.currentPosition < 5_000)
                    fixture.model.onLoopMode(LoopMode.LOOP_ONE)
                    player.seekTo((player.duration - 100).coerceAtLeast(0))
                }
                fixture.await { fixture.engine.snapshot.value.isPlaying && fixture.engine.snapshot.value.positionMs < 5_000 }
                fixture.instrumentation.runOnMainSync { assertFalse(fixture.engine.snapshot.value.ended) }
            } finally {
                fixture.instrumentation.runOnMainSync {
                    if (saved == null) cache.invalidate(key) else cache.put(key, saved, 365L * 24 * 60 * 60 * 1000)
                    fixture.engine.setShortsMode(false)
                }
            }
        }
    }
}
