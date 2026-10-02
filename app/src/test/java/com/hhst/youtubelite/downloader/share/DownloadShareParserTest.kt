package com.hhst.youtubelite.downloader.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadShareParserTest {

    @Before
    fun reset() {
        DownloadShareOnce.reset()
    }

    @Test
    fun videoUrl_opensSingleConfirm() {
        val target = DownloadShareParser.parse(
            Intent.ACTION_SEND,
            "Watch https://www.youtube.com/watch?v=dQw4w9wgGcQ now",
            null,
        )
        assertTrue(target is DownloadShareTarget.Video)
        assertEquals("dQw4w9wgGcQ", (target as DownloadShareTarget.Video).videoId)
    }

    @Test
    fun watchUrlWithList_isStillSingleVideo() {
        val target = DownloadShareParser.parse(
            Intent.ACTION_VIEW,
            null,
            "https://m.youtube.com/watch?v=dQw4w9wgGcQ&list=PLabcdef",
        )
        assertTrue(target is DownloadShareTarget.Video)
    }

    @Test
    fun playlistOnlyUrl_isPlaylist() {
        val url = "https://www.youtube.com/playlist?list=PLrAXtmRdnEQy6nuLMOVlXg"
        val target = DownloadShareParser.parse(Intent.ACTION_VIEW, null, url)
        assertTrue(target is DownloadShareTarget.Playlist)
        assertEquals("PLrAXtmRdnEQy6nuLMOVlXg", (target as DownloadShareTarget.Playlist).listId)
        assertTrue(DownloadShareParser.isPlaylistOnly(url))
    }

    @Test
    fun coldStart_readsSendExtraAndViewData() {
        val fromSend = DownloadShareParser.parse(
            Intent.ACTION_SEND,
            "https://youtu.be/dQw4w9wgGcQ",
            null,
        )
        val fromView = DownloadShareParser.parse(
            Intent.ACTION_VIEW,
            null,
            "https://youtu.be/dQw4w9wgGcQ",
        )
        assertEquals((fromSend as DownloadShareTarget.Video).videoId, (fromView as DownloadShareTarget.Video).videoId)
    }

    @Test
    fun duplicateIntent_consumedOnce() {
        val key = "v:dQw4w9wgGcQ"
        assertTrue(DownloadShareOnce.consume(key, now = 1_000L))
        assertFalse(DownloadShareOnce.consume(key, now = 1_500L))
        assertTrue(DownloadShareOnce.consume(key, now = 1_000L + DownloadShareOnce.WINDOW_MS + 1))
    }

    @Test
    fun samePlaylist_matchesListIdAcrossHosts() {
        assertTrue(
            DownloadShareParser.samePlaylist(
                "https://m.youtube.com/playlist?list=PLabc",
                "https://www.youtube.com/playlist?list=PLabc",
            ),
        )
    }
}
