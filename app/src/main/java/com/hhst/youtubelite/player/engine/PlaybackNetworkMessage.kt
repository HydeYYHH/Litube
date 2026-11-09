package com.hhst.youtubelite.player.engine

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extractor.PlaybackResolutionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A validated Wi-Fi connection does not prove that YouTube is reachable. */
internal object PlaybackNetworkMessage {
    fun resource(error: Throwable, connected: Boolean?): Int? {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        // An HTTP response is a server/content failure, even if connectivity changed afterwards.
        if (causes.any { it is HttpDataSource.InvalidResponseCodeException }) return null
        val resolution = causes.filterIsInstance<PlaybackResolutionException>().firstOrNull()?.error
            ?.takeIf { it.kind == RequestFailure.TRANSPORT }
        val timeout = causes.any {
            it is SocketTimeoutException || (it is InterruptedIOException && it.message == "timeout") ||
                (it is PlaybackException && it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        } || resolution?.code == "transport_timeout"
        val connection = causes.any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
                it is SocketException || (it is PlaybackException &&
                it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        } || resolution?.code in setOf("transport_dns", "transport_connect", "transport_unreachable",
            "transport_socket", "transport_tls", "transport_io")
        return when {
            !timeout && !connection -> null
            connected == false -> R.string.player_network_offline
            timeout -> R.string.player_network_timeout
            else -> R.string.player_network_unreachable
        }
    }
}
