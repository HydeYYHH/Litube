package com.hhst.youtubelite.cast

import androidx.media3.extractor.ChunkIndex
import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastManifestTest {

    @Test
    fun usableSegmentIndex_rejectsEmptyAndNull() {
        assertFalse(CastManifest.usableSegmentIndex(null))
        assertFalse(
            CastManifest.usableSegmentIndex(
                ChunkIndex(IntArray(0), LongArray(0), LongArray(0), LongArray(0)),
            ),
        )
    }

    @Test
    fun build_uniformDurations_emitsRunLength() {
        val index = ChunkIndex(
            intArrayOf(100, 100, 100),
            longArrayOf(1000, 1100, 1200),
            longArrayOf(5_000_000, 5_000_000, 5_000_000),
            longArrayOf(0, 5_000_000, 10_000_000),
        )
        val mpd = CastManifest.build(
            video = video(),
            audio = null,
            durationMs = 15_000,
            proxyBase = "http://192.168.1.2:8080/abc",
            videoIndex = index,
        )!!
        assertTrue(mpd.contains("<S d=\"5000000\" r=\"2\"/>"))
        assertEquals(3, "<SegmentURL ".toRegex().findAll(mpd).count())
        assertTrue(mpd.contains("seg=1000-1099"))
        assertTrue(mpd.contains("seg=1200-1299"))
    }

    @Test
    fun build_stampsGenerationOnStreamAndSegmentUrls() {
        val mpd = CastManifest.build(
            video = video(),
            audio = null,
            durationMs = 10_000,
            proxyBase = "http://host/k",
            generation = 7L,
        )!!
        assertTrue(mpd.contains("http://host/k/stream/v?g=7"))
        assertFalse(mpd.contains("/stream/v\""))
    }

    @Test
    fun build_alternatingDurations_noCompression() {
        val index = ChunkIndex(
            intArrayOf(10, 20),
            longArrayOf(0, 10),
            longArrayOf(1_000_000, 2_000_000),
            longArrayOf(0, 1_000_000),
        )
        val mpd = CastManifest.build(
            video = video(),
            audio = null,
            durationMs = 3_000,
            proxyBase = "http://host/k",
            videoIndex = index,
        )!!
        assertTrue(mpd.contains("<S d=\"1000000\"/>"))
        assertTrue(mpd.contains("<S d=\"2000000\"/>"))
        assertFalse(mpd.contains(" r="))
    }

    @Test
    fun build_nonZeroStart_writesTOnFirstGroupOnly() {
        // Non-uniform durations produce two <S> groups; the first segment's
        // presentation time (non-zero tfdt) must land on the FIRST group — a
        // missing t continues from the previous entry, so a t on the last
        // group would corrupt the receiver's timeline mapping.
        val index = ChunkIndex(
            intArrayOf(100, 100, 120),
            longArrayOf(1000, 1100, 1200),
            longArrayOf(2_000_000, 2_000_000, 1_000_000),
            longArrayOf(1_000_000, 3_000_000, 5_000_000),
        )
        val mpd = CastManifest.build(
            video = video(),
            audio = null,
            durationMs = 5_000,
            proxyBase = "http://host/k",
            videoIndex = index,
        )!!
        assertTrue(mpd.contains("presentationTimeOffset=\"1000000\""))
        assertTrue(mpd.contains("<S t=\"1000000\" d=\"2000000\" r=\"1\"/>"))
        assertTrue(mpd.contains("<S d=\"1000000\"/>"))
        assertFalse(mpd.contains("<S d=\"1000000\" t="))
    }

    private fun video() = Format(
        url = "https://example.com/v",
        itag = 137,
        height = 1080,
        width = 1920,
        bitrate = 1_000_000,
        mimeType = "video/mp4",
        codec = "avc1",
        initStart = 0,
        initEnd = 100,
        indexStart = 101,
        indexEnd = 200,
        approxDurationMs = 10_000,
    )
}
