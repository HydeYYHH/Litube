@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeDashLiveManifestParserTest {

    @Test
    fun advertisedAvailabilityStartTimeIsForcedToZero() {
        val parser = object : YoutubeDashLiveManifestParser() {
            fun expose(advertisedMs: Long) = buildMediaPresentationDescription(
                advertisedMs,
                0L,
                0L,
                true,
                0L,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                null,
                emptyList(),
            )
        }
        val manifest = parser.expose(1_700_000_000_000L)
        assertEquals(0L, manifest.availabilityStartTimeMs)
        val alreadyZero = parser.expose(0L)
        assertEquals(0L, alreadyZero.availabilityStartTimeMs)
    }
}
