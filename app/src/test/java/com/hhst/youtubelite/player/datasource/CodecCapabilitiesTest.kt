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
        fps = 60,
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
        CodecCapabilities.setDecodersForTest(mapOf("video/av01" to listOf(CodecCapabilities.Decoder(false) { true })))
        assertFalse(CodecCapabilities.isDecodable(format("av01.0.09M.08")))
        assertFalse(CodecCapabilities.isDecodable(format("av1")))
    }

    @Test
    fun av1UsesDecoderDimensionsAndFrameRate() {
        CodecCapabilities.setDecodersForTest(mapOf("video/av01" to listOf(CodecCapabilities.Decoder(true) {
            it.width <= 3840 && it.height <= 2160 && it.fps <= 60
        })))
        assertTrue(CodecCapabilities.isDecodable(format("av01.0.12M.08", height = 2160)))
        assertFalse(CodecCapabilities.isDecodable(format("av01.0.16M.08", height = 4320)))
        assertFalse(CodecCapabilities.isDecodable(format("av01.0.12M.08", height = 2160).copy(fps = 120)))
        CodecCapabilities.setDecodersForTest(mapOf("video/av01" to listOf(CodecCapabilities.Decoder(true) { true })))
        assertTrue(CodecCapabilities.isDecodable(format("av01.0.16M.08", height = 4320)))
    }

    @Test
    fun unknownCapabilitiesUseConservativeStandardResolution() {
        CodecCapabilities.setDecodersForTest(emptyMap())
        assertTrue(CodecCapabilities.isDecodable(format("avc1.640028")))
        assertTrue(CodecCapabilities.isDecodable(format("vp9")))
        assertTrue(CodecCapabilities.isDecodable(format("h265")))
        assertTrue(CodecCapabilities.isDecodable(format(null)))
        assertFalse(CodecCapabilities.isDecodable(format("vp9", height = 2160)))
    }

    @Test
    fun preferPlayable_filtersUndecodableAv1() {
        CodecCapabilities.setDecodersForTest(emptyMap())
        val av1 = format("av01.0.09M.08", height = 1080)
        val vp9 = format("vp9", height = 1080)
        val pool = StreamSelection.preferPlayable(listOf(av1, vp9))
        assertEquals(listOf(vp9), pool)
    }

    @Test
    fun preferPlayable_failsFastWhenNothingIsDecodable() {
        CodecCapabilities.setDecodersForTest(emptyMap())
        val av1 = format("av01.0.09M.08", height = 1080)
        assertTrue(StreamSelection.preferPlayable(listOf(av1)).isEmpty())
    }

    @Test
    fun preferPlayable_decodableBeatsPotBearingUndecodable() {
        CodecCapabilities.setDecodersForTest(emptyMap())
        val av1Pot = format("av01.0.09M.08")
            .let { it.copy(url = it.url + "&pot=abc") }
        val vp9 = format("vp9")
        val pool = StreamSelection.preferPlayable(listOf(av1Pot, vp9))
        assertEquals(listOf(vp9), pool)
    }
}
