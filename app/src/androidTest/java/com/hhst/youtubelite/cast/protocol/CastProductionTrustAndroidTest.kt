package com.hhst.youtubelite.cast.protocol

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 351 regression guard: the bundled production roots and fallback CRL must parse and
 * validate on the Android security providers, not only on the JVM. The fixture-based VM tests
 * exercise the protocol with a synthetic chain; this one runs the real bundled trust data
 * through the production verifier without ever disabling a check.
 */
class CastProductionTrustAndroidTest {
    private fun decode(value: String) = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
    private fun certificate(payload: String) =
        CertificateFactory.getInstance("X.509").generateCertificate(decode(payload).inputStream()) as X509Certificate

    @Test fun productionRootsParseAndSelfVerifyOnAndroid() {
        for (payload in listOf(CastTrustData.CAST, CastTrustData.EUREKA, CastTrustData.CRL_ROOT)) {
            val root = certificate(payload)
            root.checkValidity()
            root.verify(root.publicKey)
        }
    }

    @Test fun bundledFallbackCrlValidatesThroughTheProductionVerifier() {
        val crlRoot = certificate(CastTrustData.CRL_ROOT)
        // The fallback branch validates the CRL chain as of its own issuance instant.
        val tbs = CastDeviceAuth.verifiedCrl(decode(CastTrustData.FALLBACK_CRL), crlRoot, Date(), fallback = true)
        assertTrue("The fallback CRL must yield its TBS structure for revocation checks", tbs.isNotEmpty())
    }
}
