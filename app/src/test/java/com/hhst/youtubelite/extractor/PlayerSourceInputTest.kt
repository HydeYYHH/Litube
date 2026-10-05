package com.hhst.youtubelite.extractor

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PlayerSourceInputTest {
    @Test fun sessionHashKeepsItsStableEncoding() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", YoutubeSessionProvider.digest("abc"))
    }
    @Test fun timestampAcrossReadBoundary() {
        val marker = "signatureTimestamp=19395;"
        for (offset in 0..marker.length) {
            val text = "x".repeat(8192 - offset) + marker
            assertEquals(19395, readSignatureTimestamp(StringReader(text)) {})
        }
    }
    @Test fun timestampAtEofAndMissing() {
        assertEquals(19395, readSignatureTimestamp(StringReader("signatureTimestamp:19395")) {})
        assertEquals(0, readSignatureTimestamp(StringReader("no timestamp")) {})
    }
}
