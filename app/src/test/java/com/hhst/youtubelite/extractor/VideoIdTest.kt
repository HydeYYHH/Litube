package com.hhst.youtubelite.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoIdTest {

    @Test
    fun parse_watchShortsLiveAndBare() {
        assertEquals("dQw4w9WgXcQ", VideoId.parse("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoId.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoId.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoId.parse("https://m.youtube.com/live/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoId.parse("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            VideoId.watchUrl("dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            VideoId.thumbnailUrl("dQw4w9WgXcQ"),
        )
        assertNull(VideoId.parse("https://m.youtube.com/"))
        assertNull(VideoId.parse(null))
    }

    @Test
    fun startPositionMs_queryHashAndInvalid() {
        assertEquals(
            90_000L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=90"),
        )
        assertEquals(
            90_000L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=90s"),
        )
        assertEquals(
            90_000L,
            VideoId.startPositionMs("https://youtu.be/dQw4w9WgXcQ?t=1m30s"),
        )
        assertEquals(
            3_723_000L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s"),
        )
        assertEquals(
            90_000L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ#t=90"),
        )
        assertEquals(
            45_000L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&start=45"),
        )
        assertEquals(
            0L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=0"),
        )
        assertNull(VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=abc"))
        assertNull(VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=-5"))
        // Query t= beats a leftover hash timestamp.
        assertEquals(
            10_000L,
            VideoId.startPositionMs("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10#t=90"),
        )
    }
}
