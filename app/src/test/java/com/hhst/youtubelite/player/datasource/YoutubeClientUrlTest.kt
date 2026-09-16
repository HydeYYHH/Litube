package com.hhst.youtubelite.player.datasource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

class YoutubeClientUrlTest {

    private fun playback(c: String) =
        "https://rr1---sn-test.googlevideo.com/videoplayback?id=abc&c=$c&cpn=xyz"

    @Test
    fun androidVr_isNotAndroid() {
        val vr = playback("ANDROID_VR")
        assertTrue(YoutubeParsingHelper.isAndroidVrStreamingUrl(vr))
        assertFalse(YoutubeParsingHelper.isAndroidStreamingUrl(vr))
    }

    @Test
    fun android_isNotVr() {
        val android = playback("ANDROID")
        assertTrue(YoutubeParsingHelper.isAndroidStreamingUrl(android))
        assertFalse(YoutubeParsingHelper.isAndroidVrStreamingUrl(android))
    }

    @Test
    fun tvHtml5_matches() {
        assertTrue(YoutubeParsingHelper.isTvHtml5StreamingUrl(playback("TVHTML5")))
        assertFalse(YoutubeParsingHelper.isTvHtml5StreamingUrl(playback("WEB")))
    }
}
