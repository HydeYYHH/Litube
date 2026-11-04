package com.hhst.youtubelite.player.engine

import com.hhst.youtubelite.player.datasource.PlaybackRecovery
import com.hhst.youtubelite.extractor.MediaRejection
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
    @Test fun unavailableManifestsChangeSourceWithoutTreatingLiveEdgeChunksAsExpired() {
        for (status in listOf(404, 410)) {
            for (path in listOf("/master.m3u8", "/manifest.mpd", "/api/manifest/hls_playlist/itag/301/file/index.m3u8")) {
                val reason = PlaybackRecovery.classifyHttp(status, "https://manifest.googlevideo.com$path")
                assertEquals(PlaybackRecovery.Reason.MANIFEST_UNAVAILABLE, reason)
                assertEquals(MediaRejection.UNAVAILABLE, reason?.rejection)
                assertFalse(requireNotNull(reason).needsLightRefresh)
            }
            assertNull(PlaybackRecovery.classifyHttp(status, "https://r1.googlevideo.com/videoplayback?sq=20"))
            assertNull(PlaybackRecovery.classifyHttp(status, "https://www.youtube.com/api/timedtext"))
        }
        assertNull(PlaybackRecovery.classifyHttp(429, "https://manifest.googlevideo.com/master.m3u8"))
        assertNull(PlaybackRecovery.classifyHttp(500, "https://manifest.googlevideo.com/master.m3u8"))
    }
    @Test fun errorBodiesDoNotRemainUnclassifiedIoFailures() {
        val reason = PlaybackRecovery.classify(IOException("load", IOException("MEDIA_ERROR_BODY")))
        assertNotNull("A media endpoint returned a document, so the source must be replaced", reason)
        assertFalse(requireNotNull(reason).needsLightRefresh)
        assertEquals(PlaybackRecovery.Reason.INVALID_MEDIA_RESPONSE, reason)
        assertEquals(MediaRejection.INVALID_BODY, reason.rejection)
        assertEquals(reason, reason.afterLightBudget())
    }

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
