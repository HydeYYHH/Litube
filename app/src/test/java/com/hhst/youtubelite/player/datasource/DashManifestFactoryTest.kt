package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestFactoryTest {

    @Test
    fun build_validFormat_containsRequiredElements() {
        val format = Format(
            url = "https://example.com/video.mp4",
            itag = 137,
            height = 1080,
            width = 1920,
            bitrate = 5_000_000,
            fps = 30,
            codec = "avc1.640028",
            container = "MP4",
            mimeType = "video/mp4",
            initStart = 0, initEnd = 740,
            indexStart = 741, indexEnd = 1256,
            approxDurationMs = 123_456,
        )

        val mpd = DashManifestFactory.build(format)

        assertTrue(mpd.contains("<MPD"))
        assertTrue(mpd.contains("mediaPresentationDuration=\"PT123.456S\""))
        assertTrue(mpd.contains("<AdaptationSet id=\"0\" mimeType=\"video/mp4\""))
        assertTrue(mpd.contains("indexRange=\"741-1256\""))
        assertTrue(mpd.contains("<Initialization range=\"0-740\"/>"))
        assertTrue(mpd.contains("<BaseURL>https://example.com/video.mp4</BaseURL>"))
    }

    @Test
    fun build_unknownVideoBitrate_usesHeightEstimate() {
        val format = Format(
            url = "https://example.com/video.mp4",
            itag = 137,
            height = 1080,
            width = 1920,
            bitrate = 0,
            mimeType = "video/mp4",
            initStart = 0, initEnd = 740,
            indexStart = 741, indexEnd = 1256,
            approxDurationMs = 1_000,
        )
        val mpd = DashManifestFactory.build(format)
        assertTrue(mpd.contains("bandwidth=\"4000000\""))
    }

    @Test
    fun build_escapesUrlAmpersands() {
        val format = Format(
            url = "https://example.com/video.mp4?a=1&b=2",
            itag = 137,
            height = 1080,
            bitrate = 1_000,
            mimeType = "video/mp4",
            initStart = 0, initEnd = 740,
            indexStart = 741, indexEnd = 1256,
            approxDurationMs = 1_000,
        )
        val mpd = DashManifestFactory.build(format)
        assertTrue(mpd.contains("<BaseURL>https://example.com/video.mp4?a=1&amp;b=2</BaseURL>"))
        assertTrue(!mpd.contains("?a=1&b=2"))
    }

    @Test
    fun build_audioFormat_includesAudioConfig() {
        val format = Format(
            url = "https://example.com/audio.m4a",
            itag = 140,
            bitrate = 128_000,
            codec = "mp4a.40.2",
            container = "M4A",
            mimeType = "audio/mp4",
            initStart = 0, initEnd = 600,
            indexStart = 601, indexEnd = 1200,
            approxDurationMs = 60_000,
            audioOnly = true,
            audioChannels = 2,
            sampleRate = 44100,
        )

        val mpd = DashManifestFactory.build(format)

        assertTrue(mpd.contains("mimeType=\"audio/mp4\""))
        assertTrue(mpd.contains("audioSamplingRate=\"44100\""))
        assertTrue(mpd.contains("AudioChannelConfiguration"))
        assertTrue(mpd.indexOf("AudioChannelConfiguration") < mpd.indexOf("SegmentBase"))
    }

    @Test
    fun hasDashRanges_rejectsZeroDefaults() {
        assertTrue(!Format(url = "https://example.com/v.mp4").hasDashRanges)
        assertTrue(
            !Format(
                url = "https://example.com/v.mp4",
                initStart = 0, initEnd = 0, indexStart = 0, indexEnd = 0,
            ).hasDashRanges,
        )
        assertTrue(
            Format(
                url = "https://example.com/v.mp4",
                initStart = 0, initEnd = 740, indexStart = 741, indexEnd = 1256,
            ).hasDashRanges,
        )
    }

    @Test
    fun build_missingRanges_throws() {
        val format = Format(url = "https://example.com/video.mp4")
        val error = assertThrows(IllegalArgumentException::class.java) {
            DashManifestFactory.build(format)
        }
        assertTrue(error.message!!.contains("DASH ranges"))
    }

    @Test
    fun build_fallbackDuration_usedWhenApproxUnknown() {
        val format = Format(
            url = "https://example.com/video.mp4",
            itag = 137,
            initStart = 0, initEnd = 740,
            indexStart = 741, indexEnd = 1256,
            approxDurationMs = -1,
        )
        val mpd = DashManifestFactory.build(format, durationMsFallback = 30_000)
        assertTrue(mpd.contains("mediaPresentationDuration=\"PT30S\""))
    }

    private fun videoFormat(itag: Int, height: Int, mime: String) = Format(
        url = "https://example.com/v$itag.mp4",
        itag = itag,
        height = height,
        width = height * 16 / 9,
        bitrate = height * 4_000,
        fps = 30,
        codec = "avc1.640028",
        container = "MP4",
        mimeType = mime,
        initStart = 0, initEnd = 740,
        indexStart = 741, indexEnd = 1256,
        approxDurationMs = 123_456,
    )

    @Test
    fun buildVideoPool_groupsRepresentationsPerMime() {
        val mpd = DashManifestFactory.buildVideoPool(
            listOf(
                videoFormat(137, 1080, "video/mp4"),
                videoFormat(136, 720, "video/mp4"),
                videoFormat(247, 720, "video/webm"),
            ),
        )
        // Two AdaptationSets (mp4, webm); the mp4 set carries both its formats.
        assertTrue(mpd.contains("<AdaptationSet id=\"0\" mimeType=\"video/mp4\""))
        assertTrue(mpd.contains("<AdaptationSet id=\"1\" mimeType=\"video/webm\""))
        assertTrue(mpd.contains("<Representation id=\"137\""))
        assertTrue(mpd.contains("<Representation id=\"136\""))
        assertTrue(mpd.contains("<Representation id=\"247\""))
        assertTrue(mpd.contains("height=\"1080\""))
        assertTrue(mpd.contains("height=\"720\""))
    }

    @Test
    fun buildVideoPool_emptyPool_throws() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DashManifestFactory.buildVideoPool(emptyList())
        }
        assertTrue(error.message!!.contains("Empty"))
    }

    @Test
    fun buildVideoPool_nullItag_fallsBackToUniqueIds() {
        val mpd = DashManifestFactory.buildVideoPool(
            listOf(
                videoFormat(137, 1080, "video/mp4").copy(itag = null),
                videoFormat(136, 720, "video/mp4").copy(itag = null),
            ),
        )
        // Without an itag the Representation id falls back to a per-format
        // index so ids stay unique (MPD requires distinct ids).
        assertTrue(mpd.contains("<Representation id=\"0\""))
        assertTrue(mpd.contains("<Representation id=\"1\""))
    }

    @Test
    fun buildVideoPool_rangelessFormat_throws() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DashManifestFactory.buildVideoPool(listOf(Format(url = "https://example.com/v.mp4")))
        }
        assertTrue(error.message!!.contains("DASH ranges"))
    }
}
