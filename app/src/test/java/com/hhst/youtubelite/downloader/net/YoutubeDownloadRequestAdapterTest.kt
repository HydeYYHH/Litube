package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.resolve.TEST_VIDEO_ID
import com.hhst.youtubelite.downloader.resolve.playbackUrl
import com.hhst.youtubelite.player.datasource.YoutubePlaybackCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeDownloadRequestAdapterTest {

    @Test
    fun webAdaptive_startsWithGet() {
        val url = playbackUrl(137, client = "WEB")
        val plan = YoutubeDownloadRequestAdapter.adaptUrl(TEST_VIDEO_ID, url, 137)
        assertEquals(DownloadHttpMethod.GET, plan.method)
        assertFalse(plan.postPulse)
        assertEquals(DownloadRangeMode.HTTP_HEADER, plan.rangeMode)
        assertEquals(DownloadCookiePolicy.NONE, plan.cookiePolicy)
        assertEquals(null, plan.client)
        assertEquals(null, plan.headers["Origin"])
        assertEquals(DownloadHttpClients.WORK_DIR, plan.workingDirectory)
        assertNotEquals("player", plan.workingDirectory)
    }

    @Test
    fun androidMuxed18_usesRangeHeaderNotQuery() {
        val url = playbackUrl(18, client = "ANDROID")
        val plan = YoutubeDownloadRequestAdapter.adaptUrl(TEST_VIDEO_ID, url, 18)
        assertEquals(DownloadHttpMethod.GET, plan.method)
        assertEquals(DownloadRangeMode.HTTP_HEADER, plan.rangeMode)
        assertEquals(DownloadCookiePolicy.NONE, plan.cookiePolicy)
        assertEquals(null, plan.client)
    }

    @Test
    fun identity_ignoresExpiredUrlAndPlayerCacheKey() {
        val url = playbackUrl(137, clen = 42)
        val plan = YoutubeDownloadRequestAdapter.adaptUrl(TEST_VIDEO_ID, url, 137)
        assertFalse(plan.resourceIdentity.contains("expire"))
        assertFalse(plan.resourceIdentity.contains("pot="))
        assertFalse(plan.resourceIdentity.contains(url))
        assertTrue(plan.resourceIdentity.startsWith(DownloadResourceIdentity.PREFIX))
        val playerKey = YoutubePlaybackCacheKey.ofQuery(url.substringAfter('?'))
        assertNotEquals(playerKey, plan.resourceIdentity)
        val expired = url.replace("expire=999999", "expire=1")
        val refreshed = YoutubeDownloadRequestAdapter.adaptUrl(TEST_VIDEO_ID, expired, 137)
        assertEquals(plan.resourceIdentity, refreshed.resourceIdentity)
    }

}
