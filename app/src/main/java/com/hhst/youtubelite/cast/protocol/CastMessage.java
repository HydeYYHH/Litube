// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One CASTV2 protocol message - the single protobuf message type the whole Cast v2 wire protocol
 * uses (protocol reference: thibauts/node-castv2). Immutable value object; the byte-level
 * encoding lives in {@link CastMessageCodec}.
 *
 * <p>Proto shape (field numbers matter - they are the wire contract):</p>
 * <pre>
 *   1: protocol_version (enum, always 0 = CASTV2_1_0)
 *   2: source_id        (string)
 *   3: destination_id   (string)
 *   4: namespace        (string)
 *   5: payload_type     (enum: 0=STRING, 1=BINARY)
 *   6: payload_utf8     (string, when STRING)
 *   7: payload_binary   (bytes,  when BINARY - decode-tolerated, never sent by us)
 * </pre>
 */
public final class CastMessage {

    /** The only protocol version that exists. */
    public static final int PROTOCOL_VERSION_CASTV2_1_0 = 0;

    public static final int PAYLOAD_TYPE_STRING = 0;
    public static final int PAYLOAD_TYPE_BINARY = 1;

    private final int mProtocolVersion;
    private final String mSourceId;
    private final String mDestinationId;
    private final String mNamespace;
    private final int mPayloadType;
    @Nullable
    private final String mPayloadUtf8;
    @Nullable
    private final byte[] mPayloadBinary;

    CastMessage(int protocolVersion, String sourceId, String destinationId, String namespace,
                int payloadType, @Nullable String payloadUtf8, @Nullable byte[] payloadBinary) {
        mProtocolVersion = protocolVersion;
        mSourceId = sourceId != null ? sourceId : "";
        mDestinationId = destinationId != null ? destinationId : "";
        mNamespace = namespace != null ? namespace : "";
        mPayloadType = payloadType;
        mPayloadUtf8 = payloadUtf8;
        mPayloadBinary = payloadBinary;
    }

    /** The message shape we actually send: a UTF-8 JSON payload. */
    public static CastMessage utf8(String sourceId, String destinationId, String namespace, String payloadUtf8) {
        return new CastMessage(PROTOCOL_VERSION_CASTV2_1_0, sourceId, destinationId, namespace,
                PAYLOAD_TYPE_STRING, payloadUtf8, null);
    }

    public int getProtocolVersion() {
        return mProtocolVersion;
    }

    public String getSourceId() {
        return mSourceId;
    }

    public String getDestinationId() {
        return mDestinationId;
    }

    public String getNamespace() {
        return mNamespace;
    }

    public int getPayloadType() {
        return mPayloadType;
    }

    @Nullable
    public String getPayloadUtf8() {
        return mPayloadUtf8;
    }

    @Nullable
    public byte[] getPayloadBinary() {
        return mPayloadBinary;
    }

    @NonNull
    @Override
    public String toString() {
        return "CastMessage{" + mSourceId + " -> " + mDestinationId + " [" + mNamespace + "] "
                + (mPayloadType == PAYLOAD_TYPE_STRING ? mPayloadUtf8
                        : ("<" + (mPayloadBinary != null ? mPayloadBinary.length : 0) + " bytes>")) + "}";
    }
}
