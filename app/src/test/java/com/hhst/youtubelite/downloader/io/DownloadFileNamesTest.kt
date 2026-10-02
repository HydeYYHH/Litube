package com.hhst.youtubelite.downloader.io

import com.hhst.youtubelite.downloader.net.DownloadRangeMode
import com.hhst.youtubelite.downloader.net.DownloadRangeParser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileNamesTest {
    @Test
    fun stripsIllegalKeepsUnicodeAndDisambiguates() {
        val name = DownloadFileNames.sanitize("""你好:*?"<>|clip""", "mp4", existing = emptySet())
        assertEquals("你好_______clip.mp4", name)
        assertTrue(name.contains("你好"))
        val empty = DownloadFileNames.sanitize("   ", "m4a")
        assertEquals("download.m4a", empty)
        val first = DownloadFileNames.sanitize("clip", "mp4", setOf("clip.mp4"))
        assertEquals("clip (1).mp4", first)
    }
}

class DownloadNetworkPolicyTest {
    @Test
    fun wifiOnlyBlocksCellularAndAllowsWifi() {
        assertTrue(DownloadNetworkPolicy.canTransfer(wifiOnly = false, NetworkKind.CELLULAR))
        assertFalse(DownloadNetworkPolicy.canTransfer(wifiOnly = true, NetworkKind.CELLULAR))
        assertTrue(DownloadNetworkPolicy.canTransfer(wifiOnly = true, NetworkKind.WIFI))
        assertTrue(DownloadNetworkPolicy.waitingNetwork(wifiOnly = false, NetworkKind.NONE))
        assertFalse(DownloadNetworkPolicy.waitingNetwork(wifiOnly = false, NetworkKind.WIFI))
    }
}

class DownloadRangeParserTest {
    @Test
    fun twoHundredMatchesOnlyWhenProvenOtherwiseFallsBack() {
        val range = DownloadRangeParser.parse("bytes 100-199/1000")
        assertEquals(100L, range!!.start)
        assertEquals(199L, range.end)
        assertEquals(1000L, range.total)
        assertEquals(
            DownloadRangeParser.Decision.MATCH,
            DownloadRangeParser.decide(206, 100, 199, DownloadRangeMode.HTTP_HEADER, range, 100),
        )
        assertEquals(
            DownloadRangeParser.Decision.MATCH,
            DownloadRangeParser.decide(
                200, 0, 99, DownloadRangeMode.QUERY_PARAM, null, 100,
            ),
        )
        assertEquals(
            DownloadRangeParser.Decision.FULL_FALLBACK,
            DownloadRangeParser.decide(
                200, 100, 199, DownloadRangeMode.HTTP_HEADER, null, 1000,
            ),
        )
    }

    @Test
    fun offsetsAbove4GiB_parseAsLong() {
        val fourGiB = 1L shl 32
        val range = DownloadRangeParser.parse("bytes $fourGiB-${fourGiB + 1023}/8589934592")
        assertEquals(fourGiB, range!!.start)
        assertEquals(fourGiB + 1023L, range.end)
        assertEquals(8589934592L, range.total)
        assertTrue(range.start > Int.MAX_VALUE)
        assertEquals(
            DownloadRangeParser.Decision.MATCH,
            DownloadRangeParser.decide(
                206,
                fourGiB,
                fourGiB + 1023L,
                DownloadRangeMode.HTTP_HEADER,
                range,
                1024L,
            ),
        )
    }
}
