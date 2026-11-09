package com.hhst.youtubelite.cast.protocol

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 351 regression guard: bundled production roots and fallback CRL must validate
 * on the Android security providers, not only the JVM — real trust data through the
 * production verifier, no disabled checks.
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
