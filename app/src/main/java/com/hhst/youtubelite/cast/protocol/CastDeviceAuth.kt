package com.hhst.youtubelite.cast.protocol

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.CertPathValidator
import java.security.cert.PKIXCertPathValidatorResult
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.SSLSocket

/** Cast DeviceAuth: authenticate the hardware key and bind it to the provisional TLS peer.
 * Protocol/trust data: Chromium/OpenScreen, BSD; the general HTTPS trust store is never changed.
 */
object CastDeviceAuth {
    const val NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth"
    /** Cast device certificates are short-lived by policy; anything longer is a proxy/emulator. */
    private const val MAX_TLS_LIFETIME_MS = 4L * 24 * 60 * 60 * 1000
    private const val AUTH_TIMEOUT_MS = 7_000L
    /** Issuance window of the bundled fallback CRL; its chain is validated as of this instant. */
    private val FALLBACK_CRL_TIME = Date(1692255600000L) // 2023-08-17 UTC
    private val factory get() = CertificateFactory.getInstance("X.509")
    private fun cert(bytes: ByteArray) = factory.generateCertificate(bytes.inputStream()) as X509Certificate
    private fun decode(value: String) = Base64.decode(value, Base64.DEFAULT)
    private val roots by lazy { listOf(cert(decode(CastTrustData.CAST)), cert(decode(CastTrustData.EUREKA))) }
    private val crlRoot by lazy { cert(decode(CastTrustData.CRL_ROOT)) }

    @JvmStatic fun authenticate(socket: SSLSocket, input: InputStream, output: OutputStream) {
        authenticateWithRoots(socket, input, output, roots, crlRoot)
    }

    /** Injectable trust anchors for in-process tests; production always uses the bundled anchors. */
    internal fun authenticateWithRoots(socket: SSLSocket, input: InputStream, output: OutputStream,
                                      trusted: List<X509Certificate>, revocationRoot: X509Certificate,
                                      now: () -> Date = { Date() }) {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val challenge = Proto.encode(1 to 1L, 2 to nonce, 3 to 1L)
        CastMessageCodec.writeFramed(output, CastMessage(0, CastV2Channel.SENDER_ID, CastV2Channel.RECEIVER_ID,
            NAMESPACE, CastMessage.PAYLOAD_TYPE_BINARY, null, Proto.encode(1 to challenge)))
        output.flush()
        val deadline = System.nanoTime() + AUTH_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val message = CastMessageCodec.readFramed(input) ?: error("DeviceAuth disconnected")
            if (message.namespace == CastV2Channel.NS_HEARTBEAT) {
                if (message.payloadUtf8?.contains("PING") == true) {
                    CastMessageCodec.writeFramed(output, CastMessage.utf8(CastV2Channel.SENDER_ID, message.sourceId,
                        CastV2Channel.NS_HEARTBEAT, "{\"type\":\"PONG\"}"))
                    output.flush()
                }
                continue
            }
            require(message.namespace == NAMESPACE && message.sourceId == CastV2Channel.RECEIVER_ID &&
                message.destinationId == CastV2Channel.SENDER_ID &&
                message.payloadType == CastMessage.PAYLOAD_TYPE_BINARY)
            val envelope = Proto.parse(requireNotNull(message.payloadBinary))
            require(envelope[3] == null) { "DeviceAuth rejected" }
            verify(Proto.bytes(envelope, 2), socket.session.peerCertificates.first() as X509Certificate,
                nonce, trusted, revocationRoot, now())
            return
        }
        error("DeviceAuth timeout")
    }

    internal fun verify(response: ByteArray, tlsPeer: X509Certificate, nonce: ByteArray,
                        trusted: List<X509Certificate> = roots, revocationRoot: X509Certificate = crlRoot,
                        now: Date = Date()) {
        tlsPeer.checkValidity(now)
        require(tlsPeer.notAfter.time - now.time <= MAX_TLS_LIFETIME_MS) { "TLS certificate lifetime" }
        val fields = Proto.parse(response)
        require(MessageDigest.isEqual(nonce, Proto.bytes(fields, 5))) { "DeviceAuth nonce mismatch" }
        require(Proto.number(fields, 4, 1) == 1L) { "Unsupported DeviceAuth signature" }
        val chain = listOf(cert(Proto.bytes(fields, 2))) +
            fields[3].orEmpty().map { cert(it as ByteArray) }
        require(chain.size <= 8)
        val path = validateChain(chain, trusted, now)
        val key = chain.first().publicKey as? RSAPublicKey ?: error("DeviceAuth requires RSA")
        require(key.modulus.bitLength() >= 2048)
        require(chain.first().keyUsage?.getOrNull(0) == true) { "Device certificate key usage" }
        // An audio-only receiver is not a video target; enforce the signed policy as well as mDNS ca.
        val audioPolicy = byteArrayOf(0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01, 0xd6.toByte(), 0x79, 0x02, 0x05, 0x02)
        require(path.none { contains(it.getExtensionValue("2.5.29.32") ?: byteArrayOf(), audioPolicy) })
        val algorithm = when (Proto.number(fields, 6, 0)) {
            0L -> "SHA1withRSA" // Legacy Cast authentication, scoped to device signatures only.
            1L -> "SHA256withRSA"
            else -> error("Unsupported DeviceAuth digest")
        }
        require(signature(algorithm, key, nonce + tlsPeer.encoded, Proto.bytes(fields, 1))) { "DeviceAuth signature" }
        val offered = (fields[7]?.firstOrNull() as? ByteArray)?.let {
            runCatching { verifiedCrl(it, revocationRoot, now, false) }.getOrNull()
        }
        // Chromium's bundled, signed historical deny-list. This offline fallback does not prove
        // current revocation freshness; a valid receiver-supplied CRL takes precedence.
        val crl = offered ?: verifiedCrl(decode(CastTrustData.FALLBACK_CRL), crlRoot, now, true)
        checkRevocation(path, crl)
    }

    private fun validateChain(chain: List<X509Certificate>, trusted: List<X509Certificate>, time: Date): List<X509Certificate> {
        require(chain.isNotEmpty() && chain.size <= 8)
        val withoutAnchor = chain.filterNot { candidate -> trusted.any { it == candidate } }
        require(withoutAnchor.isNotEmpty())
        val params = PKIXParameters(trusted.map { TrustAnchor(it, null) }.toSet()).apply {
            isRevocationEnabled = false // Cast uses its signed protobuf CRL, verified below.
            date = time
        }
        val result = CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(withoutAnchor), params)
            as PKIXCertPathValidatorResult
        return withoutAnchor + requireNotNull(result.trustAnchor.trustedCert)
    }

    private fun signature(algorithm: String, key: PublicKey, data: ByteArray, signed: ByteArray) =
        Signature.getInstance(algorithm).run { initVerify(key); update(data); verify(signed) }

    private fun verifiedCrl(bundle: ByteArray, trusted: X509Certificate, now: Date, fallback: Boolean): Map<Int, List<Any>> {
        for (raw in Proto.parse(bundle)[1].orEmpty()) {
            val candidate = runCatching {
                val entry = Proto.parse(raw as ByteArray)
                val tbsBytes = Proto.bytes(entry, 1)
                val tbs = Proto.parse(tbsBytes)
                require(Proto.number(tbs, 1, 0) == 0L)
                val issuer = cert(Proto.bytes(entry, 2))
                validateChain(listOf(issuer), listOf(trusted), if (fallback) FALLBACK_CRL_TIME else now)
                require(signature("SHA256withRSA", issuer.publicKey, tbsBytes, Proto.bytes(entry, 3)))
                if (!fallback) {
                    val seconds = now.time / 1000
                    require(seconds >= Proto.number(tbs, 2) && seconds <= Proto.number(tbs, 3))
                }
                tbs
            }.getOrNull()
            if (candidate != null) return candidate
        }
        error("No valid Cast revocation list")
    }

    private fun checkRevocation(chain: List<X509Certificate>, crl: Map<Int, List<Any>>) {
        val hashes = chain.map { MessageDigest.getInstance("SHA-256").digest(it.publicKey.encoded) }
        require(hashes.none { hash -> crl[4].orEmpty().any { MessageDigest.isEqual(hash, it as ByteArray) } }) { "Revoked device key" }
        for (raw in crl[5].orEmpty()) {
            val range = Proto.parse(raw as ByteArray)
            val first = unsigned(Proto.number(range, 2))
            val last = unsigned(Proto.number(range, 3))
            require(last >= first)
            for (index in 0 until chain.lastIndex) {
                if (MessageDigest.isEqual(hashes[index + 1], Proto.bytes(range, 1))) {
                    require(chain[index].serialNumber !in first..last) { "Revoked device serial" }
                }
            }
        }
    }

    private fun unsigned(value: Long) = BigInteger.valueOf(value).let {
        if (value < 0) it.add(BigInteger.ONE.shiftLeft(64)) else it
    }

    private fun contains(bytes: ByteArray, pattern: ByteArray): Boolean =
        (0..bytes.size - pattern.size).any { index -> pattern.indices.all { bytes[index + it] == pattern[it] } }
}

/** Bounded protobuf subset for DeviceAuth and its revocation messages. */
internal object Proto {
    fun parse(data: ByteArray): Map<Int, List<Any>> {
        require(data.size <= 65536)
        var position = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(position < data.size)
                val byte = data[position++].toInt() and 255
                require(shift != 63 || byte < 2)
                result = result or ((byte and 127).toLong() shl shift)
                if (byte < 128) return result
            }
            error("Invalid protobuf varint")
        }
        val fields = mutableMapOf<Int, MutableList<Any>>()
        while (position < data.size) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            require(field > 0)
            val value: Any = when ((tag and 7).toInt()) {
                0 -> varint()
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= data.size - position)
                    data.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
                }
                1, 5 -> {
                    val length = if (tag and 7 == 1L) 8 else 4
                    require(length <= data.size - position)
                    position += length
                    continue
                }
                else -> error("Invalid protobuf wire type")
            }
            fields.getOrPut(field) { mutableListOf() }.add(value)
        }
        return fields
    }
    fun bytes(fields: Map<Int, List<Any>>, field: Int) = requireNotNull(fields[field]?.singleOrNull() as? ByteArray)
    fun number(fields: Map<Int, List<Any>>, field: Int, default: Long? = null) =
        fields[field]?.singleOrNull() as? Long ?: requireNotNull(default)
    fun encode(vararg fields: Pair<Int, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(input: Long) {
            var value = input
            while (value and -128L != 0L) { out.write((value.toInt() and 127) or 128); value = value ushr 7 }
            out.write(value.toInt())
        }
        for ((field, value) in fields) when (value) {
            is Long -> { varint((field shl 3).toLong()); varint(value) }
            is ByteArray -> { varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); out.write(value) }
        }
        return out.toByteArray()
    }
}
