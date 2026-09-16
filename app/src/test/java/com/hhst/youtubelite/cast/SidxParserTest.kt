package com.hhst.youtubelite.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidxParserTest {

    @Test
    fun parse_version0_oneMediaReference() {
        val box = sidxVersion0(
            timescale = 1000,
            earliestPts = 0,
            firstOffset = 0,
            refs = listOf(Ref(size = 1000, duration = 1000)),
        )
        val index = SidxParser.parse(box, 0L)!!
        assertEquals(1, index.length)
        assertEquals(1000, index.sizes[0])
        assertEquals(box.size.toLong(), index.offsets[0])
        assertEquals(1_000_000L, index.durationsUs[0])
        assertEquals(0L, index.timesUs[0])
    }

    @Test
    fun parse_skipsPrecedingBox() {
        val sidx = sidxVersion0(
            timescale = 1000,
            earliestPts = 0,
            firstOffset = 0,
            refs = listOf(Ref(size = 500, duration = 2000)),
        )
        val moof = box("moof", ByteArray(0))
        val data = moof + sidx
        val index = SidxParser.parse(data, 100L)!!
        assertEquals(1, index.length)
        assertEquals(500, index.sizes[0])
        assertEquals(100L + data.size, index.offsets[0])
    }

    @Test
    fun parse_indirectReference_returnsNull() {
        val box = sidxVersion0(
            timescale = 1000,
            earliestPts = 0,
            firstOffset = 0,
            refs = listOf(Ref(size = 1000, duration = 1000, indirect = true)),
        )
        assertNull(SidxParser.parse(box, 0L))
    }

    @Test
    fun parse_emptyReferenceCount_returnsNull() {
        val box = sidxVersion0(
            timescale = 1000,
            earliestPts = 0,
            firstOffset = 0,
            refs = emptyList(),
        )
        assertNull(SidxParser.parse(box, 0L))
    }

    @Test
    fun parse_truncated_returnsNull() {
        assertNull(SidxParser.parse(byteArrayOf(1, 2, 3, 4, 5), 0L))
        assertNull(SidxParser.parse(ByteArray(32), 0L))
    }

    @Test
    fun parse_version1_64bitFields() {
        // The version is stored in the high byte; flag bits do not affect it.
        val box = sidxVersion1(
            timescale = 1000,
            earliestPts = 0,
            firstOffset = 0,
            refs = listOf(Ref(size = 1000, duration = 1000)),
        )
        val index = SidxParser.parse(box, 0L)!!
        assertEquals(1, index.length)
        assertEquals(1000, index.sizes[0])
        assertEquals(box.size.toLong(), index.offsets[0])
        assertEquals(1_000_000L, index.durationsUs[0])
    }

    @Test
    fun parse_version0_nonzeroFlags() {
        // A v0 box whose flags byte is nonzero must not be misread as v1.
        val box = sidxVersion0(
            timescale = 1000,
            earliestPts = 0,
            firstOffset = 0,
            refs = listOf(Ref(size = 1000, duration = 1000)),
            flags = 0x000001,
        )
        val index = SidxParser.parse(box, 0L)!!
        assertEquals(1, index.length)
        assertEquals(1000, index.sizes[0])
    }

    private data class Ref(val size: Int, val duration: Long, val indirect: Boolean = false)

    private fun sidxVersion0(
        timescale: Long,
        earliestPts: Long,
        firstOffset: Long,
        refs: List<Ref>,
        flags: Int = 0,
    ): ByteArray {
        var payload = byteArrayOf()
        payload += u32((0L shl 24 or flags.toLong()) and 0xFFFFFFFFL) // version + flags
        payload += u32(1) // reference_ID
        payload += u32(timescale)
        payload += u32(earliestPts)
        payload += u32(firstOffset)
        payload += u16(0) // reserved
        payload += u16(refs.size)
        for (ref in refs) {
            val firstInt = (if (ref.indirect) 0x80000000.toInt() else 0) or (ref.size and 0x7FFFFFFF)
            payload += u32(firstInt.toLong() and 0xFFFFFFFFL)
            payload += u32(ref.duration)
            payload += u32(0) // SAP
        }
        return box("sidx", payload)
    }

    private fun sidxVersion1(
        timescale: Long,
        earliestPts: Long,
        firstOffset: Long,
        refs: List<Ref>,
    ): ByteArray {
        var payload = byteArrayOf()
        payload += u32(1L shl 24) // version 1 + flags 0
        payload += u32(1) // reference_ID
        payload += u32(timescale)
        payload += u64(earliestPts)
        payload += u64(firstOffset)
        payload += u16(0) // reserved
        payload += u16(refs.size)
        for (ref in refs) {
            val firstInt = (if (ref.indirect) 0x80000000.toInt() else 0) or (ref.size and 0x7FFFFFFF)
            payload += u32(firstInt.toLong() and 0xFFFFFFFFL)
            payload += u32(ref.duration)
            payload += u32(0) // SAP
        }
        return box("sidx", payload)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return u32(size.toLong()) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun u32(v: Long): ByteArray {
        val n = v.toInt()
        return byteArrayOf(
            ((n ushr 24) and 0xFF).toByte(),
            ((n ushr 16) and 0xFF).toByte(),
            ((n ushr 8) and 0xFF).toByte(),
            (n and 0xFF).toByte(),
        )
    }

    private fun u16(v: Int): ByteArray = byteArrayOf(
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun u64(v: Long): ByteArray {
        var n = v
        val out = ByteArray(8)
        for (i in 7 downTo 0) {
            out[i] = (n and 0xFF).toByte()
            n = n ushr 8
        }
        return out
    }
}
