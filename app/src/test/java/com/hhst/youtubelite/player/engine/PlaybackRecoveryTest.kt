package com.hhst.youtubelite.player.engine

import com.hhst.youtubelite.player.datasource.PlaybackRecovery
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PlaybackRecoveryTest {
    @Test fun localSessionFenceIsNeverConvertedToAServer403() {
        val cause = IOException("MEDIA_SESSION_CHANGED")
        val error = IllegalStateException("wrapper", IOException("load wrapper", cause))
        val reason = PlaybackRecovery.classify(error)
        assertEquals(PlaybackRecovery.Reason.SESSION_CHANGED, reason)
        assertEquals(PlaybackRecovery.Reason.SESSION_CHANGED, reason?.afterLightBudget())
    }
    @Test fun staleUrlsKeepTheirExistingFallbackAndUnclassifiedIoDoesNotRefreshTokens() {
        for (code in listOf("MEDIA_OBJECT_CHANGED", "MEDIA_URL_EXPIRED")) {
            val reason = PlaybackRecovery.classify(IOException(code))
            assertEquals(PlaybackRecovery.Reason.URL_STALE, reason)
            assertEquals(PlaybackRecovery.Reason.HTTP_403, reason?.afterLightBudget())
        }
        assertNull(PlaybackRecovery.classify(IOException("ordinary network error")))
    }
    @Test fun transportFailuresThroughNestedWrappersNeverEscalateToTokenRefresh() {
        for (cause in listOf(SocketTimeoutException(), ConnectException(), NoRouteToHostException(), UnknownHostException())) {
            val reason = PlaybackRecovery.classify(IOException("load", IOException("transport", cause)))
            assertEquals(PlaybackRecovery.Reason.TRANSPORT_FAILED, reason)
            assertTrue(reason!!.needsLightRefresh)
            assertEquals(reason, reason.afterLightBudget())
        }
    }
    @Test fun cancellationAndUnclassifiedFailuresDoNotRequestSourceRefresh() {
        assertNull(PlaybackRecovery.classify(InterruptedIOException("cancelled")))
        assertNull(PlaybackRecovery.classify(EOFException()))
        assertFalse(PlaybackRecovery.Reason.HTTP_403.needsLightRefresh)
    }
    @Test fun sessionFenceTakesPrecedenceOverAnOuterTransportFailure() {
        val timeout = SocketTimeoutException().apply { initCause(IOException("MEDIA_SESSION_CHANGED")) }
        assertEquals(PlaybackRecovery.Reason.SESSION_CHANGED, PlaybackRecovery.classify(timeout))
    }
    @Test fun cyclicCauseChainDoesNotHangRecovery() {
        val first = IOException()
        val second = IOException(first)
        first.initCause(second)
        assertNull(PlaybackRecovery.classify(first))
    }
}
