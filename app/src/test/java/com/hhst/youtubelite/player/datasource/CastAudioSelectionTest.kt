package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastAudioSelectionTest {

    @Test
    fun prefersAacOfMatchingIdentityOverOpusOfSameIdentity() {
        val opus = dash(
            url = "opus",
            audioOnly = true,
            codec = "opus",
            mime = "audio/webm",
            bitrate = 160_000,
            locale = "en",
            trackId = "en.0",
        )
        val aac = dash(
            url = "aac",
            audioOnly = true,
            codec = "mp4a.40.2",
            mime = "audio/mp4",
            bitrate = 128_000,
            locale = "en",
            trackId = "en.0",
        )
        val picked = CastAudioSelection.select(listOf(opus, aac), "id:en.0", ::isAac)
        assertEquals("aac", picked?.url)
    }

    @Test
    fun neverPicksOpusWhenAnyAacExists() {
        val opusEn = dash(
            url = "opus-en",
            audioOnly = true,
            codec = "opus",
            mime = "audio/webm",
            bitrate = 160_000,
            locale = "en",
            trackId = "en.0",
        )
        val aacEs = dash(
            url = "aac-es",
            audioOnly = true,
            codec = "mp4a.40.2",
            mime = "audio/mp4",
            bitrate = 128_000,
            locale = "es",
            trackId = "es.0",
        )
        val picked = CastAudioSelection.select(listOf(opusEn, aacEs), "id:en.0", ::isAac)
        assertEquals("aac-es", picked?.url)
        assertTrue(isAac(picked!!))
    }

    @Test
    fun opusOnlyPoolMayReturnOpus() {
        val opus = dash(
            url = "opus",
            audioOnly = true,
            codec = "opus",
            mime = "audio/webm",
            bitrate = 160_000,
            locale = "en",
            trackId = "en.0",
        )
        val picked = CastAudioSelection.select(listOf(opus), "id:en.0", ::isAac)
        assertEquals("opus", picked?.url)
    }

    @Test
    fun emptyDashPoolReturnsNull() {
        val noIndex = Format(url = "x", audioOnly = true, mimeType = "audio/mp4", codec = "mp4a")
        assertNull(CastAudioSelection.select(listOf(noIndex), "en", ::isAac))
    }

    private fun isAac(f: Format): Boolean =
        f.mimeType.contains("mp4a", true) || (f.codec ?: "").startsWith("mp4a")

    private fun dash(
        url: String,
        audioOnly: Boolean = false,
        codec: String,
        mime: String,
        bitrate: Int = 128_000,
        locale: String? = null,
        trackId: String? = null,
    ) = Format(
        url = url,
        audioOnly = audioOnly,
        codec = codec,
        mimeType = mime,
        bitrate = bitrate,
        audioLocale = locale,
        audioTrackId = trackId,
        initStart = 0,
        initEnd = 10,
        indexStart = 11,
        indexEnd = 200,
    )
}
