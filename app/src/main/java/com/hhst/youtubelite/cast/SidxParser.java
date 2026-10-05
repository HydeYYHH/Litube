/*
 * sidx parsing logic adapted from media3's FragmentedMp4Extractor.parseSidx
 * (Apache 2.0 licensed). Parses the ISO 14496-12 SegmentIndex (sidx) box to
 * obtain per-segment byte ranges and durations, which are used to build a
 * DASH SegmentList so the Chromecast receiver fetches individual segments
 * instead of downloading the entire stream as a single byte-range request.
 */

package com.hhst.youtubelite.cast;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ChunkIndex;

/**
 * Parses an ISO 14496-12 SegmentIndex (sidx) box into a {@link ChunkIndex}.
 * <p>
 * The sidx box lives at the index range the extractor reported for the fmp4
 * stream (see {@link com.hhst.youtubelite.extractor.Format#getIndexStart()}).
 * Parsing it on the phone lets the cast proxy emit a DASH {@code SegmentList}
 * with explicit per-segment byte ranges, so the Chromecast default receiver
 * issues targeted Range GETs for individual fmp4 segments instead of
 * downloading the entire stream with {@code Range: bytes=0-}.
 */
@UnstableApi
public final class SidxParser {

    private static final String TAG = "SidxParser";
    private static final int HEADER_SIZE = 8;
    private static final int TYPE_SIDX = 0x73696478;

    private SidxParser() {
    }

    /**
     * Parses a raw sidx box into a {@link ChunkIndex}.
     *
     * @param sidxData        the raw bytes fetched from the stream's index range.
     *                        May contain other boxes (for example a moov tail)
     *                        before the sidx box; this method scans for the
     *                        {@code sidx} type at four-byte boundaries
     * @param dataStartOffset the absolute byte offset in the stream where
     *                        {@code sidxData[0]} begins (the itag's indexStart)
     * @return the parsed chunk index, or {@code null} if no sidx box could be
     *         located or the data is malformed
     */
    @Nullable
    public static ChunkIndex parse(@NonNull byte[] sidxData, long dataStartOffset) {
        try {
            int sidxOffset = findSidxBoxOffset(sidxData);
            if (sidxOffset < 0) {
                Log.w(TAG, "SidxParser: no sidx box found in " + sidxData.length + " bytes");
                return null;
            }
            // Box header: size (4) then type (4). Size includes the header.
            long sidxSize = readUint32(sidxData, sidxOffset);
            int headerSize = HEADER_SIZE;
            if (sidxSize == 1) {
                // ISO 14496-12: size == 1 means a 64-bit largesize follows the type.
                if (sidxOffset + 16 > sidxData.length) {
                    Log.w(TAG, "SidxParser: truncated largesize header");
                    return null;
                }
                sidxSize = 0;
                for (int i = 0; i < 8; i++) {
                    sidxSize = (sidxSize << 8) | (sidxData[sidxOffset + 8 + i] & 0xFF);
                }
                headerSize = 16;
            } else if (sidxSize == 0) {
                sidxSize = sidxData.length - sidxOffset;
            }
            long inputPosition = dataStartOffset + sidxOffset + sidxSize; // first media byte after the sidx box

            ParsableByteArray atom = new ParsableByteArray(sidxData, sidxData.length);
            atom.setPosition(sidxOffset + headerSize);
            int fullAtom = atom.readInt();
            // Big-endian fullbox word: (version << 24) | flags — same read as
            // media3's Atom.parseFullAtomVersion, which this parser is adapted from.
            int version = fullAtom >>> 24;

            atom.skipBytes(4); // reference_ID
            long timescale = atom.readUnsignedInt();
            if (timescale == 0) {
                Log.w(TAG, "SidxParser: timescale=0");
                return null;
            }
            long earliestPresentationTime;
            long offset = inputPosition;
            if (version == 0) {
                earliestPresentationTime = atom.readUnsignedInt();
                offset += atom.readUnsignedInt();
            } else {
                // Version 1 uses 64-bit fields. ParsableByteArray.readUnsignedLongToLong
                // throws when the high bit is set. Sidx times and offsets can exceed
                // signed long in rare VP9/AV1 streams; read the bits without that check.
                earliestPresentationTime = readUnsignedLongBitsAsLong(atom);
                offset += readUnsignedLongBitsAsLong(atom);
            }
            long earliestPresentationTimeUs =
                    (earliestPresentationTime * C.MICROS_PER_SECOND) / timescale;

            atom.skipBytes(2); // reserved

            int referenceCount = atom.readUnsignedShort();
            if (referenceCount == 0) {
                Log.w(TAG, "SidxParser: referenceCount=0 (empty sidx)");
                return null;
            }

            int[] sizes = new int[referenceCount];
            long[] offsets = new long[referenceCount];
            long[] durationsUs = new long[referenceCount];
            long[] timesUs = new long[referenceCount];

            long time = earliestPresentationTime;
            long timeUs = earliestPresentationTimeUs;
            for (int i = 0; i < referenceCount; i++) {
                int firstInt = atom.readInt();
                // Bit 31 is the reference type (1 = indirect, 0 = media).
                // Indirect references are not supported.
                if ((firstInt & 0x80000000) != 0) {
                    Log.w(TAG, "SidxParser: indirect reference at index " + i + ", aborting");
                    return null;
                }
                long referenceDuration = atom.readUnsignedInt();

                sizes[i] = 0x7FFFFFFF & firstInt;
                offsets[i] = offset;

                timesUs[i] = timeUs;
                time += referenceDuration;
                timeUs = (time * C.MICROS_PER_SECOND) / timescale;
                durationsUs[i] = timeUs - timesUs[i];

                atom.skipBytes(4); // startsWithSAP / SAPType / SAPDeltaTime
                offset += sizes[i];
            }

            return new ChunkIndex(sizes, offsets, durationsUs, timesUs);
        } catch (Exception e) {
            Log.w(TAG, "SidxParser: parse failed", e);
            return null;
        }
    }

    /**
     * Scans {@code data} for an ISO 14496-12 sidx box header (type {@code sidx}).
     *
     * @return offset of the box size field, or {@code -1} if not found
     */
    private static int findSidxBoxOffset(@NonNull byte[] data) {
        if (data.length < HEADER_SIZE) return -1;
        int pos = 0;
        while (pos + HEADER_SIZE <= data.length) {
            long size = readUint32(data, pos);
            int type = readInt32(data, pos + 4);
            if (type == TYPE_SIDX) {
                return pos;
            }
            // ISO 14496-12: size == 1 means the real size follows as an
            // 8-byte largesize; skipping only the 4-byte header field would
            // desynchronize the box walk. size == 0 extends to EOF, so
            // nothing can follow it.
            if (size == 1 && pos + 16 <= data.length) {
                size = readUint64(data, pos + 8);
            }
            if (size <= 0 || pos + size > data.length) {
                break;
            }
            pos += (int) size;
        }
        // Last resort: scan for "sidx" at four-byte boundaries. Accept the
        // match only when the preceding four bytes form a plausible box size.
        for (int i = 4; i + 4 <= data.length; i += 4) {
            if (data[i] == 0x73 && data[i + 1] == 0x69
                    && data[i + 2] == 0x64 && data[i + 3] == 0x78) {
                int sizeOffset = i - 4;
                long size = readUint32(data, sizeOffset);
                if (size >= HEADER_SIZE && sizeOffset + size <= data.length) {
                    return sizeOffset;
                }
            }
        }
        return -1;
    }

    /**
     * Reads eight bytes as an unsigned 64-bit integer into a {@code long}.
     * Unlike {@link ParsableByteArray#readUnsignedLongToLong()}, values with
     * the high bit set do not throw; they appear as negative longs, which
     * does not occur for sidx times and offsets in practice.
     */
    private static long readUnsignedLongBitsAsLong(@NonNull ParsableByteArray atom) {
        long high = atom.readUnsignedInt();
        long low = atom.readUnsignedInt();
        return (high << 32) | low;
    }

    private static long readUint32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (long) (data[offset + 3] & 0xFF);
    }

    /** 64-bit ISO box largesize; high-bit-set values appear as negative longs. */
    private static long readUint64(byte[] data, int offset) {
        return (readUint32(data, offset) << 32) | readUint32(data, offset + 4);
    }

    private static int readInt32(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24
                | (data[offset + 1] & 0xFF) << 16
                | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }
}
