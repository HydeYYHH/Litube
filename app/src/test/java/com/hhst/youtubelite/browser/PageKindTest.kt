package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageKindTest {

    @Test
    fun mapsKnownSurfaces() {
        for ((url, kind) in known) {
            assertEquals(url, kind, PageKind.of(url))
        }
    }

    @Test
    fun playerSurface_watchOnly() {
        assertTrue(PageKind.isPlayerSurface(Constants.PAGE_WATCH))
        assertFalse(PageKind.isPlayerSurface(Constants.PAGE_SHORTS))
        assertFalse(PageKind.isPlayerSurface(Constants.PAGE_HOME))
        assertFalse(PageKind.isPlayerSurface(null))
    }

    @Test
    fun rejectsUnknown() {
        assertEquals("unknown", PageKind.of("https://example.com/watch?v=1"))
        assertEquals("unknown", PageKind.of(null))
        assertEquals("unknown", PageKind.of(""))
    }

    private companion object {
        val known = listOf(
            "https://m.youtube.com/" to Constants.PAGE_HOME,
            "https://www.youtube.com/" to Constants.PAGE_HOME,
            "https://m.youtube.com/shorts/abc" to Constants.PAGE_SHORTS,
            "https://m.youtube.com/watch?v=1" to Constants.PAGE_WATCH,
            "https://www.youtube.com/watch?v=1" to Constants.PAGE_WATCH,
            "https://youtu.be/xyz" to Constants.PAGE_WATCH,
            "https://m.youtube.com/live/abc" to Constants.PAGE_WATCH,
            "https://www.youtube.com/embed/xyz" to Constants.PAGE_WATCH,
            "https://m.youtube.com/feed/subscriptions" to Constants.PAGE_SUBSCRIPTIONS,
            "https://m.youtube.com/feed/library" to Constants.PAGE_LIBRARY,
        )
    }
}
