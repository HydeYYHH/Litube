@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.cast

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.cast.protocol.CastDeviceAuth
import com.hhst.youtubelite.cast.protocol.CastV2Channel
import com.hhst.youtubelite.cast.protocol.Proto
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.json.JSONObject

internal object CastVmFixture {
    val fixedTime = Date(1791331200000L) // 2026-10-07 UTC; fixtures are intentionally short-lived.
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    fun bytes(name: String) = assets.open("cast/$name").use { it.readBytes() }
    fun certificate(name: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(bytes(name).inputStream()) as X509Certificate
    val root get() = certificate("root.der")
    val tls get() = certificate("tls.der")
    private val key get() = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes("device.pk8")))
    fun sign(data: ByteArray) = Signature.getInstance("SHA256withRSA").run { initSign(key); update(data); sign() }
    fun response(nonce: ByteArray, wrongSignature: Boolean = false, revoked: Boolean = false): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(certificate("device.der").publicKey.encoded)
        val tbs = if (revoked) Proto.encode(1 to 0L, 2 to (fixedTime.time / 1000 - 60),
            3 to (fixedTime.time / 1000 + 60), 4 to hash)
        else Proto.encode(1 to 0L, 2 to (fixedTime.time / 1000 - 60), 3 to (fixedTime.time / 1000 + 60))
        val bundle = Proto.encode(1 to Proto.encode(1 to tbs, 2 to bytes("device.der"), 3 to sign(tbs)))
        val signature = sign(nonce + tls.encoded).also { if (wrongSignature) it[0] = (it[0].toInt() xor 1).toByte() }
        return Proto.encode(1 to signature, 2 to bytes("device.der"), 4 to 1L,
            5 to nonce, 6 to 1L, 7 to bundle)
    }
    fun context(): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        store.load(bytes("receiver.p12").inputStream(), "vm-fixture".toCharArray())
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        managers.init(store, "vm-fixture".toCharArray())
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }
    fun authenticator() = CastV2Channel.Authenticator { socket, input, output ->
        CastDeviceAuth.authenticateWithRoots(socket, input, output, listOf(root), root) { fixedTime }
    }
    fun media() = JSONObject(String(bytes("media.json")))
}
