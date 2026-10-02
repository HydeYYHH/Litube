package com.hhst.youtubelite.downloader.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBitrateTest {

    @Test
    fun videoBitrate_staysBitsPerSecond() {
        val format = videoFormat(1080, bitrate = 5_000_000)
        assertEquals(5_000_000L, DownloadBitrate.bitsPerSecond(format))
    }

    @Test
    fun audioAverageBitrate_normalizedFromKbps() {
        val kbps = audioFormat(bitrate = 128)
        assertEquals(128_000L, DownloadBitrate.bitsPerSecond(kbps))
        val alreadyBps = audioFormat(bitrate = 128_000)
        assertEquals(128_000L, DownloadBitrate.bitsPerSecond(alreadyBps))
    }

    @Test
    fun expectedBytes_prefersClenThenNormalizedBitrate() {
        val withClen = audioFormat(bitrate = 128, clen = 999)
        assertEquals(999L, DownloadBitrate.expectedBytes(withClen, durationSec = 60))
        val noClen = audioFormat(bitrate = 128, clen = 0).copy(
            url = "https://rr.googlevideo.com/videoplayback?id=mediaid&itag=140&c=VISIONOS",
        )
        assertEquals(128_000L * 60 / 8, DownloadBitrate.expectedBytes(noClen, durationSec = 60))
    }
}
