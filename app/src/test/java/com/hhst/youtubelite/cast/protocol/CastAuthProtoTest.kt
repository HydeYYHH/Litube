package com.hhst.youtubelite.cast.protocol

import org.junit.Assert.*
import org.junit.Test

class CastAuthProtoTest {
    @Test fun omittedOptionalFieldsUseDefaultsButMissingRequiredFieldsNameTheField() {
        val fields = Proto.parse(byteArrayOf(0x0a, 0x02, 0x01, 0x02))
        assertArrayEquals(byteArrayOf(1, 2), Proto.bytes(fields, 1))
        assertArrayEquals(byteArrayOf(), Proto.bytes(fields, 5, byteArrayOf()))
        assertEquals(1L, Proto.number(fields, 4, 1L))
        assertEquals(0L, Proto.number(fields, 6, 0L))
        val error = assertThrows(IllegalArgumentException::class.java) { Proto.bytes(fields, 2) }
        assertTrue(error.message.orEmpty().contains("field 2"))
    }

    @Test fun malformedAndDuplicateOptionalFieldsCannotSilentlySelectDefaults() {
        val wrongType = Proto.parse(Proto.encode(5 to 1L, 6 to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { Proto.bytes(wrongType, 5, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { Proto.number(wrongType, 6, 0L) }
        val duplicate = Proto.parse(Proto.encode(5 to byteArrayOf(1), 5 to byteArrayOf(2), 6 to 0L, 6 to 1L))
        assertThrows(IllegalArgumentException::class.java) { Proto.bytes(duplicate, 5, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { Proto.number(duplicate, 6, 0L) }
    }
}
