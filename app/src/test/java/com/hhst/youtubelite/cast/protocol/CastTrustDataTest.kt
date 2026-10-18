package com.hhst.youtubelite.cast.protocol

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTrustDataTest {
    @Test fun bundledTrustAnchorsMatchUpstreamAndVerifyTheirSignatures() {
        // SHA-256 of the DER arrays in Chromium Open Screen's cast/common/certificate/.
        val anchors = listOf(
            Triple("Cast", CastTrustData.CAST, "809af14700b3fe2611ad597eb1584d6354313b64ccdb3390f097fa3e5826d6ca"),
            Triple("Eureka", CastTrustData.EUREKA, "caf6d1e37b532203e01a76bf07187bb731ccd38801565ab2211a2c0ab7f3bc46"),
            Triple("CRL", CastTrustData.CRL_ROOT, "8b77c60291f9d62764613083f49750b1c5caac2d9efbe5a28edf301f9d84831d"),
        )
        for ((name, encoded, expected) in anchors) {
            val bytes = Base64.getDecoder().decode(encoded)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("$name trust anchor bytes", expected, digest)
            val input = bytes.inputStream()
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            assertEquals("$name trailing data", 0, input.available())
            assertTrue("$name must be a CA", certificate.basicConstraints >= 0)
            certificate.verify(certificate.publicKey)
        }
    }
}
