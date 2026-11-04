package com.hhst.youtubelite.downloader.io

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedStreamIntegrityTest {
    private val bytes = ByteArray(32_769) { (it % 251).toByte() }
    private val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun completeContentMatchesItsLengthAndChecksum() {
        assertTrue(FileIntegrity.verify(ByteArrayInputStream(bytes), bytes.size.toLong(), checksum.uppercase()))
    }

    @Test fun aSameLengthCorruptionFailsVerification() {
        val corrupt = bytes.copyOf().also { it[8_193] = (it[8_193] + 1).toByte() }
        assertFalse(FileIntegrity.verify(ByteArrayInputStream(corrupt), corrupt.size.toLong(), checksum))
    }

    @Test fun truncatedAndOverlongStreamsFailEvenWithoutAChecksum() {
        assertFalse(FileIntegrity.verify(ByteArrayInputStream(bytes), bytes.size + 1L, null))
        assertFalse(FileIntegrity.verify(ByteArrayInputStream(bytes), bytes.size - 1L, null))
        assertTrue(FileIntegrity.verify(ByteArrayInputStream(bytes), bytes.size.toLong(), null))
    }

    @Test fun overlongStreamsStopWithoutReadingTheWholeResource() {
        var reads = 0
        val endless = object : InputStream() {
            override fun read(): Int { reads++; return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                buffer.fill(0, offset, offset + length)
                return length
            }
        }
        assertFalse(FileIntegrity.verify(endless, 16, null))
        assertTrue(reads <= 2)
    }
}
