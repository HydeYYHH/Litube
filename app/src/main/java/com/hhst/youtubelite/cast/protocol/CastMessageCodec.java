// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wire codec for the one protobuf message CASTV2 uses ({@link CastMessage}), plus the stream
 * framing (a 4-byte big-endian length prefix before each encoded message). Hand-rolled because a
 * protobuf runtime is not worth a Gradle dependency for a single seven-field message; the decoder
 * skips unknown fields so receiver-side additions can't break parsing. Malformed/truncated input
 * throws {@link IOException} - callers treat that as a dead channel, never as a crash.
 */
public final class CastMessageCodec {

    /**
     * Frame size sanity cap. The Cast spec caps messages at 64 KB; anything bigger in the length
     * prefix means garbage/desync on the socket, and rejecting it early avoids allocating an
     * attacker-or-corruption-sized buffer (OOM guard).
     */
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    private static final int FIELD_PROTOCOL_VERSION = 1;
    private static final int FIELD_SOURCE_ID = 2;
    private static final int FIELD_DESTINATION_ID = 3;
    private static final int FIELD_NAMESPACE = 4;
    private static final int FIELD_PAYLOAD_TYPE = 5;
    private static final int FIELD_PAYLOAD_UTF8 = 6;
    private static final int FIELD_PAYLOAD_BINARY = 7;

    private CastMessageCodec() {
    }

    // ---------------------------------------------------------------------------------
    // Protobuf encode
    // ---------------------------------------------------------------------------------

    /** Encode to protobuf bytes (no length prefix). */
    public static byte[] encode(CastMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeVarint(out, key(FIELD_PROTOCOL_VERSION, WIRE_VARINT));
        writeVarint(out, message.getProtocolVersion());
        writeStringField(out, FIELD_SOURCE_ID, message.getSourceId());
        writeStringField(out, FIELD_DESTINATION_ID, message.getDestinationId());
        writeStringField(out, FIELD_NAMESPACE, message.getNamespace());
        writeVarint(out, key(FIELD_PAYLOAD_TYPE, WIRE_VARINT));
        writeVarint(out, message.getPayloadType());
        if (message.getPayloadType() == CastMessage.PAYLOAD_TYPE_STRING) {
            writeStringField(out, FIELD_PAYLOAD_UTF8,
                    message.getPayloadUtf8() != null ? message.getPayloadUtf8() : "");
        } else if (message.getPayloadBinary() != null) {
            writeBytesField(out, FIELD_PAYLOAD_BINARY, message.getPayloadBinary());
        }
        return out.toByteArray();
    }

    private static int key(int fieldNumber, int wireType) {
        return (fieldNumber << 3) | wireType;
    }

    private static void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        writeBytesField(out, fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytesField(ByteArrayOutputStream out, int fieldNumber, byte[] bytes) {
        writeVarint(out, key(fieldNumber, WIRE_LENGTH_DELIMITED));
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Standard protobuf base-128 varint: 7 bits per byte, LSB group first, high bit = continue. */
    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    // ---------------------------------------------------------------------------------
    // Protobuf decode
    // ---------------------------------------------------------------------------------

    /** Decode protobuf bytes (no length prefix). Throws {@link IOException} on malformed input. */
    public static CastMessage decode(byte[] data) throws IOException {
        Cursor cursor = new Cursor(data);
        int protocolVersion = 0;
        String sourceId = "";
        String destinationId = "";
        String namespace = "";
        int payloadType = CastMessage.PAYLOAD_TYPE_STRING;
        String payloadUtf8 = null;
        byte[] payloadBinary = null;

        while (cursor.hasRemaining()) {
            long tag = cursor.readVarint();
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            if (fieldNumber <= 0) {
                throw new IOException("Invalid field number " + fieldNumber);
            }
            switch (fieldNumber) {
                case FIELD_PROTOCOL_VERSION:
                    if (wireType == WIRE_VARINT) {
                        protocolVersion = (int) cursor.readVarint();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                case FIELD_SOURCE_ID:
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        sourceId = cursor.readString();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                case FIELD_DESTINATION_ID:
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        destinationId = cursor.readString();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                case FIELD_NAMESPACE:
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        namespace = cursor.readString();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                case FIELD_PAYLOAD_TYPE:
                    if (wireType == WIRE_VARINT) {
                        payloadType = (int) cursor.readVarint();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                case FIELD_PAYLOAD_UTF8:
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        payloadUtf8 = cursor.readString();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                case FIELD_PAYLOAD_BINARY:
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        payloadBinary = cursor.readBytes();
                    } else {
                        cursor.skipField(wireType);
                    }
                    break;
                default:
                    cursor.skipField(wireType);
                    break;
            }
        }
        return new CastMessage(protocolVersion, sourceId, destinationId, namespace,
                payloadType, payloadUtf8, payloadBinary);
    }

    /** Bounds-checked read cursor over a decoded frame. Every overrun throws, never wraps. */
    private static final class Cursor {
        private final byte[] mData;
        private int mPos;

        Cursor(byte[] data) {
            mData = data;
        }

        boolean hasRemaining() {
            return mPos < mData.length;
        }

        long readVarint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (mPos >= mData.length) {
                    throw new EOFException("Truncated varint");
                }
                byte b = mData[mPos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IOException("Varint too long (>10 bytes)");
        }

        byte[] readBytes() throws IOException {
            long length = readVarint();
            if (length < 0 || length > mData.length - mPos) {
                throw new EOFException("Truncated length-delimited field: need " + length
                        + ", have " + (mData.length - mPos));
            }
            byte[] bytes = new byte[(int) length];
            System.arraycopy(mData, mPos, bytes, 0, (int) length);
            mPos += (int) length;
            return bytes;
        }

        String readString() throws IOException {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        void skipField(int wireType) throws IOException {
            switch (wireType) {
                case WIRE_VARINT:
                    readVarint();
                    break;
                case WIRE_FIXED64:
                    skip(8);
                    break;
                case WIRE_LENGTH_DELIMITED:
                    readBytes();
                    break;
                case WIRE_FIXED32:
                    skip(4);
                    break;
                default:
                    // Wire types 3/4 (groups) are pre-proto2 relics no Cast receiver emits.
                    throw new IOException("Unsupported wire type " + wireType);
            }
        }

        private void skip(int count) throws IOException {
            if (count > mData.length - mPos) {
                throw new EOFException("Truncated fixed-width field");
            }
            mPos += count;
        }
    }

    // ---------------------------------------------------------------------------------
    // Stream framing (4-byte big-endian length prefix)
    // ---------------------------------------------------------------------------------

    /** Write one framed message. Does NOT flush - the channel owns flushing/batching. */
    public static void writeFramed(OutputStream out, CastMessage message) throws IOException {
        byte[] body = encode(message);
        if (body.length > MAX_MESSAGE_BYTES) {
            throw new IOException("Message too large to send: " + body.length + " bytes");
        }
        out.write((body.length >>> 24) & 0xFF);
        out.write((body.length >>> 16) & 0xFF);
        out.write((body.length >>> 8) & 0xFF);
        out.write(body.length & 0xFF);
        out.write(body);
    }

    /**
     * Read one framed message. Returns {@code null} on clean EOF at a frame boundary (peer closed
     * the socket); throws {@link IOException} on mid-frame EOF, oversized frames or garbage.
     */
    @Nullable
    public static CastMessage readFramed(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean close between frames
        }
        int length = (first << 24)
                | (readByteOrThrow(in) << 16)
                | (readByteOrThrow(in) << 8)
                | readByteOrThrow(in);
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Bad frame length: " + length);
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(body, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Truncated frame: " + offset + "/" + length + " bytes");
            }
            offset += read;
        }
        return decode(body);
    }

    private static int readByteOrThrow(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Truncated frame header");
        }
        return b;
    }
}
