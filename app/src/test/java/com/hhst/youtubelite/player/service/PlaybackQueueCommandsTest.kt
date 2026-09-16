@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.service

import androidx.media3.common.Player
import com.hhst.youtubelite.player.engine.PlaybackTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueCommandsTest {

    @Test
    fun nextAndPreviousAreMedia3SkipCommands() {
        assertTrue(PlaybackQueueCommands.NEXT.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(PlaybackQueueCommands.NEXT.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(PlaybackQueueCommands.PREVIOUS.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(PlaybackQueueCommands.PREVIOUS.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test
    fun applyDoesNotThrowOnEmptyBase() {
        val base = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build()
        PlaybackQueueCommands.apply(base, hasNext = true, hasPrevious = true)
        PlaybackQueueCommands.apply(base, hasNext = false, hasPrevious = false)
    }
}

class PlaybackTransportTest {

    @Test
    fun bufferingWhileUserWantsPlayLooksPlaying() {
        assertTrue(PlaybackTransport.showAsPlaying(userWantsPlay = true, isPlaying = false))
        assertTrue(PlaybackTransport.showAsPlaying(userWantsPlay = true, isPlaying = true))
        assertTrue(PlaybackTransport.showAsPlaying(userWantsPlay = false, isPlaying = true))
        assertFalse(PlaybackTransport.showAsPlaying(userWantsPlay = false, isPlaying = false))
    }
}
