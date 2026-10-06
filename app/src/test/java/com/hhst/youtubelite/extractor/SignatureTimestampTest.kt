package com.hhst.youtubelite.extractor

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class SignatureTimestampTest {
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
