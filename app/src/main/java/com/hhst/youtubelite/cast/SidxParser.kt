/*
 * sidx parsing logic adapted from media3's FragmentedMp4Extractor.parseSidx
 * (Apache 2.0 licensed). Parses the ISO 14496-12 SegmentIndex (sidx) box to
 * obtain per-segment byte ranges and durations, which are used to build a
 * DASH SegmentList so the Chromecast receiver fetches individual segments
 * instead of downloading the entire stream as a single byte-range request.
 */

package com.hhst.youtubelite.cast

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex

/**
 * Parses an ISO 14496-12 SegmentIndex (sidx) box into a [ChunkIndex].
 *
 * The sidx box lives at the index range the extractor reported for the fmp4
 * stream (see the extractor Format's indexStart/indexEnd).
 * Parsing it on the phone lets the cast proxy emit a DASH `SegmentList`
 * with explicit per-segment byte ranges, so the Chromecast default receiver
 * issues targeted Range GETs for individual fmp4 segments instead of
 * downloading the entire stream with `Range: bytes=0-`.
 */
@UnstableApi
object SidxParser {

    private const val TAG = "SidxParser"
    private const val HEADER_SIZE = 8
    private const val TYPE_SIDX = 0x73696478

    /**
     * Parses a raw sidx box into a [ChunkIndex].
     *
     * @param sidxData        the raw bytes fetched from the stream's index range.
     *                        May contain other boxes (for example a moov tail)
     *                        before the sidx box; this method scans for the
     *                        `sidx` type at four-byte boundaries
     * @param dataStartOffset the absolute byte offset in the stream where
     *                        `sidxData[0]` begins (the itag's indexStart)
     * @return the parsed chunk index, or `null` if no sidx box could be
     *         located or the data is malformed
     */
    fun parse(sidxData: ByteArray, dataStartOffset: Long): ChunkIndex? {
        return try {
            val sidxOffset = findSidxBoxOffset(sidxData)
            if (sidxOffset < 0) {
                Log.w(TAG, "SidxParser: no sidx box found in ${sidxData.size} bytes")
                return null
            }
            // Box header: size (4) then type (4). Size includes the header.
            var sidxSize = readUint32(sidxData, sidxOffset)
            var headerSize = HEADER_SIZE
            if (sidxSize == 1L) {
                // ISO 14496-12: size == 1 means a 64-bit largesize follows the type.
                if (sidxOffset + 16 > sidxData.size) {
                    Log.w(TAG, "SidxParser: truncated largesize header")
                    return null
                }
                sidxSize = 0
                for (i in 0 until 8) {
                    sidxSize = (sidxSize shl 8) or (sidxData[sidxOffset + 8 + i].toLong() and 0xFF)
                }
                headerSize = 16
            } else if (sidxSize == 0L) {
                sidxSize = (sidxData.size - sidxOffset).toLong()
            }
            // First media byte after the sidx box.
            var offset = dataStartOffset + sidxOffset + sidxSize

            val atom = ParsableByteArray(sidxData, sidxData.size)
            atom.setPosition(sidxOffset + headerSize)
            val fullAtom = atom.readInt()
            // Big-endian fullbox word: (version << 24) | flags — same read as
            // media3's Atom.parseFullAtomVersion, which this parser is adapted from.
            val version = fullAtom ushr 24

            atom.skipBytes(4) // reference_ID
            val timescale = atom.readUnsignedInt()
            if (timescale == 0L) {
                Log.w(TAG, "SidxParser: timescale=0")
                return null
            }
            val earliestPresentationTime: Long
            if (version == 0) {
                earliestPresentationTime = atom.readUnsignedInt()
                offset += atom.readUnsignedInt()
            } else {
                // Version 1 uses 64-bit fields. ParsableByteArray.readUnsignedLongToLong
                // throws when the high bit is set. Sidx times and offsets can exceed
                // signed long in rare VP9/AV1 streams; read the bits without that check.
                earliestPresentationTime = readUnsignedLongBitsAsLong(atom)
                offset += readUnsignedLongBitsAsLong(atom)
            }
            val earliestPresentationTimeUs =
                (earliestPresentationTime * C.MICROS_PER_SECOND) / timescale

            atom.skipBytes(2) // reserved

            val referenceCount = atom.readUnsignedShort()
            if (referenceCount == 0) {
                Log.w(TAG, "SidxParser: referenceCount=0 (empty sidx)")
                return null
            }

            val sizes = IntArray(referenceCount)
            val offsets = LongArray(referenceCount)
            val durationsUs = LongArray(referenceCount)
            val timesUs = LongArray(referenceCount)

            var time = earliestPresentationTime
            var timeUs = earliestPresentationTimeUs
            for (i in 0 until referenceCount) {
                val firstInt = atom.readInt()
                // Bit 31 is the reference type (1 = indirect, 0 = media).
                // Indirect references are not supported.
                if ((firstInt and Int.MIN_VALUE) != 0) {
                    Log.w(TAG, "SidxParser: indirect reference at index $i, aborting")
                    return null
                }
                val referenceDuration = atom.readUnsignedInt()

                sizes[i] = 0x7FFFFFFF and firstInt
                offsets[i] = offset

                timesUs[i] = timeUs
                time += referenceDuration
                timeUs = (time * C.MICROS_PER_SECOND) / timescale
                durationsUs[i] = timeUs - timesUs[i]

                atom.skipBytes(4) // startsWithSAP / SAPType / SAPDeltaTime
                offset += sizes[i]
            }

            ChunkIndex(sizes, offsets, durationsUs, timesUs)
        } catch (e: Exception) {
            Log.w(TAG, "SidxParser: parse failed", e)
            null
        }
    }

    /**
     * Scans `data` for an ISO 14496-12 sidx box header (type `sidx`).
     *
     * @return offset of the box size field, or `-1` if not found
     */
    private fun findSidxBoxOffset(data: ByteArray): Int {
        if (data.size < HEADER_SIZE) return -1
        var position = 0
        while (position + HEADER_SIZE <= data.size) {
            var size = readUint32(data, position)
            val type = readInt32(data, position + 4)
            if (type == TYPE_SIDX) {
                return position
            }
            // ISO 14496-12: size == 1 means the real size follows as an
            // 8-byte largesize; skipping only the 4-byte header field would
            // desynchronize the box walk. size == 0 extends to EOF, so
            // nothing can follow it.
            if (size == 1L && position + 16 <= data.size) {
                size = readUint64(data, position + 8)
            }
            if (size <= 0L || position.toLong() + size > data.size.toLong()) {
                break
            }
            position += size.toInt()
        }
        // Last resort: scan for "sidx" at four-byte boundaries. Accept the
        // match only when the preceding four bytes form a plausible box size.
        var index = 4
        while (index + 4 <= data.size) {
            if (data[index] == 0x73.toByte() && data[index + 1] == 0x69.toByte() &&
                data[index + 2] == 0x64.toByte() && data[index + 3] == 0x78.toByte()
            ) {
                val sizeOffset = index - 4
                val size = readUint32(data, sizeOffset)
                if (size >= HEADER_SIZE.toLong() &&
                    sizeOffset.toLong() + size <= data.size.toLong()
                ) {
                    return sizeOffset
                }
            }
            index += 4
        }
        return -1
    }

    /**
     * Reads eight bytes as an unsigned 64-bit integer into a [Long].
     * Unlike [ParsableByteArray.readUnsignedLongToLong], values with
     * the high bit set do not throw; they appear as negative longs, which
     * does not occur for sidx times and offsets in practice.
     */
    private fun readUnsignedLongBitsAsLong(atom: ParsableByteArray): Long {
        val high = atom.readUnsignedInt()
        val low = atom.readUnsignedInt()
        return (high shl 32) or low
    }

    private fun readUint32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    /** 64-bit ISO box largesize; high-bit-set values appear as negative longs. */
    private fun readUint64(data: ByteArray, offset: Int): Long =
        (readUint32(data, offset) shl 32) or readUint32(data, offset + 4)

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}
