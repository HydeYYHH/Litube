package com.hhst.youtubelite.player.datasource

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTransportTest {
    @Test fun peerDisconnectsUseFiniteTransportRecoveryWithoutClientRejection() {
        val failures = listOf(
            SSLHandshakeException("connection closed"),
            SSLHandshakeException("failed").apply { initCause(EOFException()) },
            SSLHandshakeException("failed").apply { initCause(SocketException("Connection reset")) },
            SSLProtocolException("connection reset by peer"),
        )
        for (failure in failures) {
            val reason = PlaybackRecovery.classify(IOException("load", failure))
            assertEquals(PlaybackRecovery.Reason.TRANSPORT_FAILED, reason)
            assertNull("A TLS disconnect must not demote the successful client", reason?.rejection)
        }
    }

    @Test fun certificateFailuresAndUnknownTlsFailuresRemainTerminal() {
        for (failure in listOf(
            SSLHandshakeException("connection closed").apply { initCause(CertificateException("untrusted")) },
            SSLPeerUnverifiedException("identity mismatch"),
            SSLHandshakeException("protocol mismatch"),
            SSLProtocolException("unexpected handshake"),
        )) assertNull(PlaybackRecovery.classify(failure))
    }

    @Test fun explicitInterruptionAndAnUnrelatedClosedSocketDoNotRestartPlayback() {
        assertNull(PlaybackRecovery.classify(InterruptedIOException("cancelled")))
        assertNull(PlaybackRecovery.classify(SocketException("Socket closed")))
    }
}
