package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSelectionTest {

    @After
    fun tearDown() {
        CodecCapabilities.resetForTest()
    }

    @Test
    fun codecPriority() {
        assertEquals(4, StreamSelection.codecPriority("avc1"))
        assertEquals(4, StreamSelection.codecPriority("H264"))
        assertEquals(3, StreamSelection.codecPriority("vp9"))
        // RFC 6381 form from the /player mimeType ("vp09.00.10.08") carries
        // no "vp9" substring — it must still rank as VP9.
        assertEquals(3, StreamSelection.codecPriority("vp09.00.10.08"))
        assertEquals(3, StreamSelection.codecPriority("vp8"))
        assertEquals(2, StreamSelection.codecPriority("h265"))
        assertEquals(2, StreamSelection.codecPriority("hvc1"))
        assertEquals(1, StreamSelection.codecPriority("av01"))
        assertEquals(0, StreamSelection.codecPriority(null))
    }

    @Test
    fun parseHeight() {
        assertEquals(1080, StreamSelection.parseHeight("1080p"))
        assertEquals(720, StreamSelection.parseHeight("720p60"))
        assertEquals(2160, StreamSelection.parseHeight("4K"))
        assertEquals(0, StreamSelection.parseHeight("unknown"))
    }

    @Test
    fun parseFps() {
        assertEquals(60, StreamSelection.parseFps("720p60"))
        assertEquals(0, StreamSelection.parseFps("1080p"))
        assertEquals(0, StreamSelection.parseFps("unknown"))
    }

    private fun format(height: Int, fps: Int = 30, bitrate: Int = 1000, codec: String = "avc1"): Format =
        Format(
            url = "https://example.com/v$height.mp4",
            itag = height,
            height = height,
            width = height * 16 / 9,
            bitrate = bitrate,
            fps = fps,
            codec = codec,
            container = "MP4",
            videoOnly = true,
            audioOnly = false,
            mimeType = "video/mp4",
            initStart = 0,
            initEnd = 100,
            indexStart = 101,
            indexEnd = 200,
            approxDurationMs = 120_000,
        )

    @Test
    fun filterBest_dedupHeightFps_codecWins() {
        val highBitrate = format(720, bitrate = 5_000_000, codec = "av01")
        val lowBitrate = format(720, bitrate = 1_000_000, codec = "avc1")
        val deduped = StreamSelection.filterBest(listOf(highBitrate, lowBitrate))
        assertEquals(1, deduped.size)
        // avc1 (priority 4) beats av01 (priority 1) despite lower bitrate
        assertEquals("avc1", deduped[0].codec)
    }

    @Test
    fun filterBest_sortsByHeightThenFps() {
        val f720p30 = format(720, fps = 30)
        val f720p60 = format(720, fps = 60)
        val f1080p30 = format(1080, fps = 30)
        val sorted = StreamSelection.filterBest(listOf(f720p30, f720p60, f1080p30))
        assertEquals(1080, sorted[0].height)
        assertEquals(720, sorted[1].height)
        assertEquals(60, sorted[1].fps)
        assertEquals(30, sorted[2].fps)
    }

    @Test
    fun selectVideo_exactMatch() {
        val formats = listOf(format(720), format(1080), format(480))
        assertEquals(1080, StreamSelection.selectVideo(formats, "1080p")?.height)
    }

    @Test
    fun selectVideo_fallbackToLower() {
        val formats = listOf(format(720), format(480))
        assertEquals(720, StreamSelection.selectVideo(formats, "1080p")?.height)
    }

    @Test
    fun selectVideo_noMatch_returnsHighest() {
        val formats = listOf(format(720), format(1080))
        assertEquals(1080, StreamSelection.selectVideo(formats, null)?.height)
    }

    @Test
    fun preferPlayable_dropsAndroidVrWhenStableExists() {
        val vr = format(2160).copy(url = "https://gv/videoplayback?id=a&c=ANDROID_VR")
        val web = format(720).copy(url = "https://gv/videoplayback?id=a&c=WEB&pot=xyz")
        val tv = format(1080).copy(url = "https://gv/videoplayback?id=a&c=TVHTML5")
        val pot = StreamSelection.preferPlayable(listOf(vr, web, tv))
        assertEquals(1, pot.size)
        assertEquals("WEB", StreamSelection.streamingClient(pot[0].url))

        val noPot = StreamSelection.preferPlayable(listOf(vr, tv))
        assertEquals(1, noPot.size)
        assertEquals("TVHTML5", StreamSelection.streamingClient(noPot[0].url))

        val vrOnly = StreamSelection.preferPlayable(listOf(vr))
        assertTrue(vrOnly.isEmpty())
        assertEquals(1, StreamSelection.preferPlayable(listOf(vr), allowAndroidVr = true).size)
    }

    @Test
    fun preferPlayable_allowAndroidVrDoesNotReintroduceUndecodable() {
        CodecCapabilities.setHardwareMimesForTest(emptyMap())
        val vr = format(2160).copy(url = "https://gv/videoplayback?id=a&c=ANDROID_VR")
        val av1 = format(1080, codec = "av01.0.09M.08")
            .copy(url = "https://gv/videoplayback?id=a&c=WEB")
        val pool = StreamSelection.preferPlayable(listOf(vr, av1), allowAndroidVr = true)
        assertEquals(1, pool.size)
        assertEquals("ANDROID_VR", StreamSelection.streamingClient(pool[0].url))
    }

    @Test
    fun selectAudio_preferredKeyBeatsOriginal() {
        val original = format(0).copy(
            videoOnly = false,
            audioOnly = true,
            audioTrackOriginal = true,
            audioLocale = "en",
            bitrate = 128_000,
        )
        val dubbed = format(0).copy(
            videoOnly = false,
            audioOnly = true,
            audioTrackOriginal = false,
            audioLocale = "es",
            bitrate = 96_000,
        )
        assertEquals("es", StreamSelection.selectAudio(listOf(original, dubbed), "es")?.audioLocale)
        assertEquals("en", StreamSelection.selectAudio(listOf(original, dubbed), null)?.audioLocale)
        val us = original.copy(audioLocale = "en-US", audioTrackId = "en.0", audioTrackType = "original")
        val descriptive = us.copy(
            audioTrackOriginal = false,
            audioTrackId = "en.d",
            audioTrackType = "descriptive",
            bitrate = 64_000,
        )
        assertEquals(
            "en.d",
            StreamSelection.selectAudio(listOf(us, descriptive), "id:en.d")?.audioTrackId,
        )
        assertEquals(
            "en.0",
            StreamSelection.selectAudio(listOf(us, descriptive), "en")?.audioTrackId,
        )
    }

    @Test
    fun streamingClient_readsCParam() {
        assertEquals("ANDROID_VR", StreamSelection.streamingClient("https://gv/p?c=ANDROID_VR&rn=1"))
        assertEquals("WEB", StreamSelection.streamingClient("https://gv/p?id=1&c=WEB&pot=x"))
        assertEquals(null, StreamSelection.streamingClient("https://example.com/v"))
    }

    @Test
    fun hasPoToken_acceptsQueryOrAmp() {
        assertTrue(StreamSelection.hasPoToken(format(720).copy(url = "https://gv/p?id=1&pot=abc")))
        assertTrue(StreamSelection.hasPoToken(format(720).copy(url = "https://gv/p?pot=abc")))
        assertTrue(!StreamSelection.hasPoToken(format(720).copy(url = "https://gv/p?c=WEB")))
    }

    @Test
    fun isBetter_poTokenBeatsCodec() {
        val plainAvc = format(720, codec = "avc1").copy(url = "https://gv/v?c=ANDROID_VR")
        val potAv1 = format(720, codec = "av01").copy(url = "https://gv/v?c=WEB&pot=abc")
        assertTrue(StreamSelection.isBetter(potAv1, plainAvc))
        assertTrue(!StreamSelection.isBetter(plainAvc, potAv1))
    }

    @Test
    fun selectVideo_exactMatchWithFps() {
        val f720p30 = format(720, fps = 30)
        val f720p60 = format(720, fps = 60)
        val formats = listOf(f720p30, f720p60)
        assertEquals(60, StreamSelection.selectVideo(formats, "720p60")?.fps)
    }

    @Test
    fun selectVideo_allAboveTarget_nearestUp() {
        // "360p" with only 720p/1080p in the pool: take the smallest step up,
        // not the pool's top (bandwidth overshoot).
        val formats = listOf(format(1080), format(720))
        assertEquals(720, StreamSelection.selectVideo(formats, "360p")?.height)
    }

    @Test
    fun codecPriority_hev1() {
        // RFC 6381 HEVC signaling, same rank as h265/hevc.
        assertEquals(2, StreamSelection.codecPriority("hev1.1.6.L93"))
    }

    @Test
    fun qualityLabels_fromPlayablePool_only() {
        // Mirrors PlaybackEngine.trackQualityLabels: raw pool has VR 4K, but the
        // menu must only offer what preferPlayable would actually select from.
        // When a poToken format exists, only poToken formats are playable.
        val vr = format(2160).copy(url = "https://gv/v?c=ANDROID_VR")
        val web = format(1080).copy(url = "https://gv/v?c=WEB&pot=xyz")
        val tv = format(720).copy(url = "https://gv/v?c=TVHTML5")
        val potPool = StreamSelection.preferPlayable(listOf(vr, web, tv))
            .map { "${it.height}p" + if (it.fps > 30) "${it.fps}" else "" }
        assertEquals(listOf("1080p"), potPool)

        // Without any poToken, all non-VR decodable formats stay selectable.
        val plain = StreamSelection.preferPlayable(listOf(vr, tv))
            .map { "${it.height}p" + if (it.fps > 30) "${it.fps}" else "" }
            .sortedByDescending { StreamSelection.parseHeight(it) }
        assertEquals(listOf("720p"), plain)
    }

    // -- isWindowExempt (VISIONOS full-quality path) --

    @Test
    fun isWindowExempt_visionosDirectUrl() {
        val visionos = format(1080).copy(url = "https://gv/v?c=VISIONOS&id=a")
        assertTrue(StreamSelection.isWindowExempt(visionos))
    }

    @Test
    fun isWindowExempt_poTokenUrl() {
        val web = format(720).copy(url = "https://gv/v?c=WEB&pot=abc")
        assertTrue(StreamSelection.isWindowExempt(web))
    }

    @Test
    fun isWindowExempt_windowBoundClients() {
        // Pot-less IOS/ANDROID/WEB/TVHTML5 adaptive URLs carry the 64 s window.
        for (client in listOf("IOS", "ANDROID", "WEB", "TVHTML5", "ANDROID_VR")) {
            val f = format(720).copy(url = "https://gv/v?c=$client")
            assertEquals(
                "client $client should be window-bound",
                false,
                StreamSelection.isWindowExempt(f),
            )
        }
    }
}
