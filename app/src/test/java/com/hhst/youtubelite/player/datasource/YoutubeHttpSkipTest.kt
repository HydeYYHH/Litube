@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeHttpSkipTest {

    @Test
    fun rangeHeader200_skipsToPosition() {
        assertEquals(
            100L,
            YoutubeHttpDataSource.skipBytesOnOpen(200, 100L, rangeQueryApplied = false),
        )
    }

    @Test
    fun rangeQuery200_doesNotSkip() {
        assertEquals(
            0L,
            YoutubeHttpDataSource.skipBytesOnOpen(200, 100L, rangeQueryApplied = true),
        )
    }

    @Test
    fun partial206_doesNotSkip() {
        assertEquals(
            0L,
            YoutubeHttpDataSource.skipBytesOnOpen(206, 100L, rangeQueryApplied = false),
        )
    }

    @Test
    fun positionZero_doesNotSkip() {
        assertEquals(
            0L,
            YoutubeHttpDataSource.skipBytesOnOpen(200, 0L, rangeQueryApplied = false),
        )
    }
}
