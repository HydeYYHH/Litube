package com.hhst.youtubelite.player.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {

    @Test fun remoteProgressOverridesLocalButExplicitLinksRemainAuthoritative() {
        assertEquals(60_000L, PlaybackProgress.resolveStartMs(null, 30_000, 120_000, 60_000))
        assertEquals(0L, PlaybackProgress.resolveStartMs(0, 30_000, 120_000, 60_000))
        assertEquals(0L, PlaybackProgress.resolveStartMs(null, 30_000, 120_000, 118_000))
        assertEquals(0L, PlaybackProgress.resolveStartMs(null, 30_000, 120_000, 0))
        assertEquals(30_000L, PlaybackProgress.resolveStartMs(null, 30_000, 120_000, -1))
    }

    @Test fun localProgressKeysAreIsolatedByAccountAndDelegatedChannel() {
        val owners = listOf("device", "a:first", "a:second", "a:first:c:channel")
        val keys = owners.map { PlaybackProgress.cacheKey("abcdefghijk", it) }
        assertEquals(owners.size, keys.toSet().size)
        assertEquals(keys[1], PlaybackProgress.cacheKey("abcdefghijk", owners[1]))
    }

    @Test
    fun write_clearsOnEndedOrNearEnd_savesMidRoll() {
        val duration = 120_000L
        assertEquals(
            PlaybackProgress.Write.CLEAR,
            PlaybackProgress.write(duration, duration, playbackEnded = true),
        )
        assertEquals(
            PlaybackProgress.Write.CLEAR,
            PlaybackProgress.write(duration - 1_000L, duration, playbackEnded = false),
        )
        assertEquals(
            PlaybackProgress.Write.SAVE,
            PlaybackProgress.write(60_000L, duration, playbackEnded = false),
        )
        assertEquals(
            PlaybackProgress.Write.SKIP,
            PlaybackProgress.write(1_000L, duration, playbackEnded = false),
        )
    }

    @Test
    fun resolveStartMs_explicitZeroBeatsRemembered_andClampsPastEnd() {
        assertEquals(
            0L,
            PlaybackProgress.resolveStartMs(0L, rememberedMs = 45_000L, durationMs = 120_000L),
        )
        assertEquals(
            45_000L,
            PlaybackProgress.resolveStartMs(null, rememberedMs = 45_000L, durationMs = 120_000L),
        )
        assertEquals(
            119_999L,
            PlaybackProgress.resolveStartMs(999_000L, rememberedMs = 0L, durationMs = 120_000L),
        )
    }
}
