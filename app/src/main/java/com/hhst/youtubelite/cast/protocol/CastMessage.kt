// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

/**
 * One CASTV2 protocol message - the single protobuf message type the whole Cast v2 wire protocol
 * uses (protocol reference: thibauts/node-castv2). Immutable value object; the byte-level
 * encoding lives in [CastMessageCodec].
 *
 * Proto shape (field numbers matter - they are the wire contract):
 * ```
 *   1: protocol_version (enum, always 0 = CASTV2_1_0)
 *   2: source_id        (string)
 *   3: destination_id   (string)
 *   4: namespace        (string)
 *   5: payload_type     (enum: 0=STRING, 1=BINARY)
 *   6: payload_utf8     (string, when STRING)
 *   7: payload_binary   (bytes,  when BINARY - decode-tolerated, never sent by us)
 * ```
 */
class CastMessage(
    val protocolVersion: Int,
    sourceId: String?,
    destinationId: String?,
    namespace: String?,
    val payloadType: Int,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null,
) {

    /** Cast v2 addresses are never null on the wire; a missing id collapses to the empty string. */
    val sourceId: String = sourceId ?: ""
    val destinationId: String = destinationId ?: ""
    val namespace: String = namespace ?: ""

    override fun toString(): String {
        val payload = if (payloadType == PAYLOAD_TYPE_STRING) {
            payloadUtf8
        } else {
            "<" + (payloadBinary?.size ?: 0) + " bytes>"
        }
        return "CastMessage{$sourceId -> $destinationId [$namespace] $payload}"
    }

    companion object {
        /** The only protocol version that exists. */
        const val PROTOCOL_VERSION_CASTV2_1_0 = 0

        const val PAYLOAD_TYPE_STRING = 0
        const val PAYLOAD_TYPE_BINARY = 1

        /** The message shape actually sent: a UTF-8 JSON payload. */
        fun utf8(sourceId: String, destinationId: String, namespace: String, payloadUtf8: String) =
            CastMessage(
                protocolVersion = PROTOCOL_VERSION_CASTV2_1_0,
                sourceId = sourceId,
                destinationId = destinationId,
                namespace = namespace,
                payloadType = PAYLOAD_TYPE_STRING,
                payloadUtf8 = payloadUtf8,
            )
    }
}
