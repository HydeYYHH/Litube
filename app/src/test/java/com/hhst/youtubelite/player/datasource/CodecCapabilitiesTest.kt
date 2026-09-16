package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecCapabilitiesTest {

    @After
    fun tearDown() {
        CodecCapabilities.resetForTest()
    }

    private fun format(codec: String?, height: Int = 1080): Format = Format(
        url = "https://example.com/v.mp4",
        itag = 137,
        height = height,
        width = height * 16 / 9,
        bitrate = 1_000_000,
        codec = codec,
        container = "MP4",
        videoOnly = true,
        mimeType = "video/mp4",
        initStart = 0,
        initEnd = 100,
        indexStart = 101,
        indexEnd = 200,
        approxDurationMs = 120_000,
    )

    @Test
    fun av1_droppedWithoutHardwareDecoder() {
        CodecCapabilities.setHardwareMimesForTest(emptyMap())
        assertFalse(CodecCapabilities.isDecodable(format("av01.0.09M.08")))
    }

    @Test
    fun av1_keptWithHardwareDecoderUpToCeiling() {
        CodecCapabilities.setHardwareMimesForTest(mapOf("video/av01" to true))
        assertTrue(CodecCapabilities.isDecodable(format("av01.0.09M.08", height = 1080)))
        assertFalse(CodecCapabilities.isDecodable(format("av01.0.12M.08", height = 1440)))
    }

    @Test
    fun avc_vp9_hevc_alwaysDecodable() {
        CodecCapabilities.setHardwareMimesForTest(emptyMap())
        assertTrue(CodecCapabilities.isDecodable(format("avc1.640028")))
        assertTrue(CodecCapabilities.isDecodable(format("vp9")))
        assertTrue(CodecCapabilities.isDecodable(format("h265")))
        assertTrue(CodecCapabilities.isDecodable(format(null)))
    }

    @Test
    fun preferPlayable_filtersUndecodableAv1() {
        CodecCapabilities.setHardwareMimesForTest(emptyMap())
        val av1 = format("av01.0.09M.08", height = 1080)
        val vp9 = format("vp9", height = 1080)
        val pool = StreamSelection.preferPlayable(listOf(av1, vp9))
        assertEquals(listOf(vp9), pool)
    }

    @Test
    fun preferPlayable_failsFastWhenNothingIsDecodable() {
        CodecCapabilities.setHardwareMimesForTest(emptyMap())
        val av1 = format("av01.0.09M.08", height = 1080)
        // Nothing hardware-decodable: empty pool, so the caller fails fast
        // into re-extract / client-exclusion recovery instead of handing
        // ExoPlayer a rep it cannot decode.
        assertTrue(StreamSelection.preferPlayable(listOf(av1)).isEmpty())
    }

    @Test
    fun preferPlayable_decodableBeatsPotBearingUndecodable() {
        CodecCapabilities.setHardwareMimesForTest(emptyMap())
        // A pot URL does not rescue an undecodable codec — playback would
        // still stutter through software decode. The decodable vp9 wins.
        val av1Pot = format("av01.0.09M.08")
            .let { it.copy(url = it.url + "&pot=abc") }
        val vp9 = format("vp9")
        val pool = StreamSelection.preferPlayable(listOf(av1Pot, vp9))
        assertEquals(listOf(vp9), pool)
    }
}
