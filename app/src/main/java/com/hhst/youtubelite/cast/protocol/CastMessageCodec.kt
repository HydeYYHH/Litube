// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Wire codec for the one protobuf message CASTV2 uses ([CastMessage]) plus stream framing
 * (4-byte big-endian length prefix per message). Implemented directly: a protobuf runtime
 * is unnecessary for a single seven-field message. Unknown fields are skipped so
 * receiver-side additions cannot break parsing; malformed/truncated input throws
 * [IOException].
 */
object CastMessageCodec {

    /** Frame size sanity cap: the Cast spec caps messages at 64 KB; anything larger means
     *  garbage/desync, and rejecting early avoids allocating a corruption-sized buffer. */
    private const val MAX_MESSAGE_BYTES = 64 * 1024

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    private const val FIELD_PROTOCOL_VERSION = 1
    private const val FIELD_SOURCE_ID = 2
    private const val FIELD_DESTINATION_ID = 3
    private const val FIELD_NAMESPACE = 4
    private const val FIELD_PAYLOAD_TYPE = 5
    private const val FIELD_PAYLOAD_UTF8 = 6
    private const val FIELD_PAYLOAD_BINARY = 7

    /** Encode to protobuf bytes (no length prefix). */
    fun encode(message: CastMessage): ByteArray {
        val out = ByteArrayOutputStream(256)
        writeVarint(out, key(FIELD_PROTOCOL_VERSION, WIRE_VARINT).toLong())
        writeVarint(out, message.protocolVersion.toLong())
        writeStringField(out, FIELD_SOURCE_ID, message.sourceId)
        writeStringField(out, FIELD_DESTINATION_ID, message.destinationId)
        writeStringField(out, FIELD_NAMESPACE, message.namespace)
        writeVarint(out, key(FIELD_PAYLOAD_TYPE, WIRE_VARINT).toLong())
        writeVarint(out, message.payloadType.toLong())
        if (message.payloadType == CastMessage.PAYLOAD_TYPE_STRING) {
            writeStringField(out, FIELD_PAYLOAD_UTF8, message.payloadUtf8 ?: "")
        } else {
            message.payloadBinary?.let { writeBytesField(out, FIELD_PAYLOAD_BINARY, it) }
        }
        return out.toByteArray()
    }

    private fun key(fieldNumber: Int, wireType: Int) = (fieldNumber shl 3) or wireType

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) =
        writeBytesField(out, fieldNumber, value.toByteArray(StandardCharsets.UTF_8))

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
        writeVarint(out, key(fieldNumber, WIRE_LENGTH_DELIMITED).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes, 0, bytes.size)
    }

    /** Standard protobuf base-128 varint: 7 bits per byte, LSB group first, high bit = continue. */
    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var remaining = value
        while ((remaining and 0x7FL.inv()) != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
    }

    /** Decode protobuf bytes (no length prefix). Throws [IOException] on malformed input. */
    fun decode(data: ByteArray): CastMessage {
        val cursor = Cursor(data)
        var protocolVersion = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadType = CastMessage.PAYLOAD_TYPE_STRING
        var payloadUtf8: String? = null
        var payloadBinary: ByteArray? = null

        while (cursor.hasRemaining()) {
            val tag = cursor.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (fieldNumber <= 0) {
                throw IOException("Invalid field number $fieldNumber")
            }
            when (fieldNumber) {
                FIELD_PROTOCOL_VERSION ->
                    if (wireType == WIRE_VARINT) {
                        protocolVersion = cursor.readVarint().toInt()
                    } else {
                        cursor.skipField(wireType)
                    }

                FIELD_SOURCE_ID ->
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        sourceId = cursor.readString()
                    } else {
                        cursor.skipField(wireType)
                    }

                FIELD_DESTINATION_ID ->
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        destinationId = cursor.readString()
                    } else {
                        cursor.skipField(wireType)
                    }

                FIELD_NAMESPACE ->
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        namespace = cursor.readString()
                    } else {
                        cursor.skipField(wireType)
                    }

                FIELD_PAYLOAD_TYPE ->
                    if (wireType == WIRE_VARINT) {
                        payloadType = cursor.readVarint().toInt()
                    } else {
                        cursor.skipField(wireType)
                    }

                FIELD_PAYLOAD_UTF8 ->
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        payloadUtf8 = cursor.readString()
                    } else {
                        cursor.skipField(wireType)
                    }

                FIELD_PAYLOAD_BINARY ->
                    if (wireType == WIRE_LENGTH_DELIMITED) {
                        payloadBinary = cursor.readBytes()
                    } else {
                        cursor.skipField(wireType)
                    }

                else -> cursor.skipField(wireType)
            }
        }
        return CastMessage(
            protocolVersion = protocolVersion,
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadType = payloadType,
            payloadUtf8 = payloadUtf8,
            payloadBinary = payloadBinary,
        )
    }

    /** Bounds-checked read cursor over a decoded frame. Every overrun throws, never wraps. */
    private class Cursor(private val data: ByteArray) {
        private var position = 0

        fun hasRemaining() = position < data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (position >= data.size) {
                    throw EOFException("Truncated varint")
                }
                val value = data[position++].toInt()
                if (shift == 63 && value and 0xFE != 0) {
                    throw IOException("Varint exceeds 64 bits")
                }
                result = result or ((value.toLong() and 0x7F) shl shift)
                if (value and 0x80 == 0) {
                    return result
                }
                shift += 7
            }
            throw IOException("Varint too long (>10 bytes)")
        }

        fun readBytes(): ByteArray {
            val length = readVarint()
            if (length < 0 || length > data.size - position) {
                throw EOFException(
                    "Truncated length-delimited field: need $length" +
                        ", have ${data.size - position}",
                )
            }
            val bytes = data.copyOfRange(position, position + length.toInt())
            position += length.toInt()
            return bytes
        }

        fun readString(): String = String(readBytes(), StandardCharsets.UTF_8)

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> skip(8)
                WIRE_LENGTH_DELIMITED -> readBytes()
                WIRE_FIXED32 -> skip(4)
                // Wire types 3/4 (groups) are pre-proto2 relics no Cast receiver emits.
                else -> throw IOException("Unsupported wire type $wireType")
            }
        }

        private fun skip(count: Int) {
            if (count > data.size - position) {
                throw EOFException("Truncated fixed-width field")
            }
            position += count
        }
    }

    /** Write one framed message. Does NOT flush - the channel owns flushing/batching. */
    fun writeFramed(out: OutputStream, message: CastMessage) {
        val body = encode(message)
        if (body.size > MAX_MESSAGE_BYTES) {
            throw IOException("Message too large to send: ${body.size} bytes")
        }
        out.write((body.size ushr 24) and 0xFF)
        out.write((body.size ushr 16) and 0xFF)
        out.write((body.size ushr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(body)
    }

    /**
     * Read one framed message. Returns `null` on clean EOF at a frame boundary (peer closed
     * the socket); throws [IOException] on mid-frame EOF, oversized frames or garbage.
     */
    fun readFramed(input: InputStream): CastMessage? {
        val first = input.read()
        if (first < 0) {
            return null
        }
        val length = (first shl 24) or
            (readByteOrThrow(input) shl 16) or
            (readByteOrThrow(input) shl 8) or
            readByteOrThrow(input)
        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Bad frame length: $length")
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) {
                throw EOFException("Truncated frame: $offset/$length bytes")
            }
            offset += read
        }
        return decode(body)
    }

    private fun readByteOrThrow(input: InputStream): Int {
        val value = input.read()
        if (value < 0) {
            throw EOFException("Truncated frame header")
        }
        return value
    }
}
