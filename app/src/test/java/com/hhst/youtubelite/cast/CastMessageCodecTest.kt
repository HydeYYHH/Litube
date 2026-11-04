package com.hhst.youtubelite.cast

import com.hhst.youtubelite.cast.protocol.CastMessage
import com.hhst.youtubelite.cast.protocol.CastMessageCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class CastMessageCodecTest {
    @Test
    fun encodingMatchesTheWireFields() {
        val message = CastMessage.utf8("s", "d", "n", "{}")
        val expected = byteArrayOf(8, 0, 18, 1, 115, 26, 1, 100, 34, 1, 110, 40, 0, 50, 2, 123, 125)
        assertArrayEquals(expected, CastMessageCodec.encode(message))
        assertMessage(message, CastMessageCodec.decode(expected))
    }

    @Test
    fun unicodeAndBinaryPayloadsSurviveFraming() {
        val messages = listOf(
            CastMessage.utf8("sender-1", "receiver-0", "urn:x-cast:test", "{\"title\":\"Café 日本語\"}"),
            CastMessage(0, "receiver-0", "sender-1", "urn:x-cast:test", 1,
                payloadBinary = byteArrayOf(0, -1, -128, 1, 127)),
        )
        val out = ByteArrayOutputStream()
        messages.forEach { CastMessageCodec.writeFramed(out, it) }
        val input = ByteArrayInputStream(out.toByteArray())
        messages.forEach { assertMessage(it, requireNotNull(CastMessageCodec.readFramed(input))) }
        assertNull(CastMessageCodec.readFramed(input))
    }

    @Test
    fun unknownFieldsAreSkippedForEverySupportedWireType() {
        val message = CastMessage.utf8("s", "d", "n", "{}")
        val unknown = byteArrayOf(
            64, -106, 1,
            73, 0, 1, 2, 3, 4, 5, 6, 7,
            82, 2, 10, 11,
            93, 0, 1, 2, 3,
        )
        assertMessage(message, CastMessageCodec.decode(unknown + CastMessageCodec.encode(message)))
    }

    @Test
    fun malformedFramesAndFieldsAreRejected() {
        val frames = listOf(
            byteArrayOf(0), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0, 1, 0, 1), byteArrayOf(-1, -1, -1, -1),
            byteArrayOf(0, 0, 0, 5, 8, 0),
        )
        frames.forEach { bytes ->
            assertThrows(IOException::class.java) { CastMessageCodec.readFramed(ByteArrayInputStream(bytes)) }
        }
        val fields = listOf(
            byteArrayOf(0), byteArrayOf(18, 3, 1), byteArrayOf(64, -128),
            byteArrayOf(73, 0), byteArrayOf(91), ByteArray(11) { -128 },
            byteArrayOf(64) + ByteArray(9) { -128 } + byteArrayOf(2),
        )
        fields.forEach { bytes ->
            assertThrows(IOException::class.java) { CastMessageCodec.decode(bytes) }
        }
    }

    @Test
    fun oversizedOutgoingMessagesDoNotWriteAPartialFrame() {
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            CastMessageCodec.writeFramed(out, CastMessage.utf8("s", "d", "n", "x".repeat(65_536)))
        }
        assertEquals(0, out.size())
    }

    private fun assertMessage(expected: CastMessage, actual: CastMessage) {
        assertEquals(expected.protocolVersion, actual.protocolVersion)
        assertEquals(expected.sourceId, actual.sourceId)
        assertEquals(expected.destinationId, actual.destinationId)
        assertEquals(expected.namespace, actual.namespace)
        assertEquals(expected.payloadType, actual.payloadType)
        assertEquals(expected.payloadUtf8, actual.payloadUtf8)
        assertArrayEquals(expected.payloadBinary, actual.payloadBinary)
    }
}
