package com.hhst.youtubelite.player.engine

import androidx.media3.common.PlaybackException
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extractor.PlaybackResolutionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException

class PlaybackNetworkMessageTest {
    @Test fun reportedCallTimeoutIsActionableEvenOnValidatedWifi() {
        val failure = InterruptedIOException("timeout").apply { initCause(SocketException("Socket closed")) }
        assertEquals(R.string.player_network_timeout, PlaybackNetworkMessage.resource(IOException(failure), true))
        assertEquals(R.string.player_network_offline, PlaybackNetworkMessage.resource(failure, false))
        assertEquals(R.string.player_network_timeout, PlaybackNetworkMessage.resource(failure, null))
    }
    @Test fun dnsAndRefusedConnectionExplainYoutubeReachability() {
        for (failure in listOf(UnknownHostException(), ConnectException("refused"))) {
            assertEquals(R.string.player_network_unreachable, PlaybackNetworkMessage.resource(failure, true))
            assertEquals(R.string.player_network_offline, PlaybackNetworkMessage.resource(failure, false))
        }
    }
    @Test fun media3NetworkCodeWorksWithoutPlatformCause() {
        val failure = PlaybackException("network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(R.string.player_network_offline, PlaybackNetworkMessage.resource(failure, false))
        assertEquals(R.string.player_network_unreachable, PlaybackNetworkMessage.resource(failure, true))
    }
    @Test fun codecContentAndCancellationFailuresAreNotMislabelledAsOffline() {
        for (failure in listOf(IOException("content"), IllegalStateException("codec"),
            InterruptedIOException("interrupted"), PlaybackException("decode", null, PlaybackException.ERROR_CODE_DECODING_FAILED))) {
            assertNull(PlaybackNetworkMessage.resource(failure, false))
        }
    }

    @Test fun normalizedExtractionFailuresStillExplainNetworkReachability() {
        for (code in listOf("transport_io", "transport_tls", "transport_dns", "transport_connect")) {
            val failure = PlaybackResolutionException(RequestError(RequestFailure.TRANSPORT, code))
            assertEquals(R.string.player_network_unreachable, PlaybackNetworkMessage.resource(failure, true))
            assertEquals(R.string.player_network_offline, PlaybackNetworkMessage.resource(failure, false))
        }
        val timeout = PlaybackResolutionException(RequestError(RequestFailure.TRANSPORT, "transport_timeout"))
        assertEquals(R.string.player_network_timeout, PlaybackNetworkMessage.resource(timeout, true))
    }

    @Test fun authAndDeliberateTransportPolicyErrorsKeepTheirOwnMeaning() {
        for (failure in listOf(RequestError(RequestFailure.AUTH, "http_403", status = 403),
            RequestError(RequestFailure.CANCELLED, "cancelled"), RequestError(RequestFailure.TRANSPORT, "missing_player_script"),
            RequestError(RequestFailure.TRANSPORT, "transport_protocol"))) {
            assertNull(PlaybackNetworkMessage.resource(PlaybackResolutionException(failure), false))
        }
    }
}
