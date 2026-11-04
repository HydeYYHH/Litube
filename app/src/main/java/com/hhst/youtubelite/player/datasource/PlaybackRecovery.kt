@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.datasource

import androidx.media3.datasource.HttpDataSource
import com.hhst.youtubelite.extractor.MediaRejection
import java.io.IOException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

internal object PlaybackRecovery {
    enum class Reason(val needsLightRefresh: Boolean, val rejection: MediaRejection? = null) {
        HTTP_403(false, MediaRejection.FORBIDDEN),
        INVALID_MEDIA_RESPONSE(false, MediaRejection.INVALID_BODY),
        MANIFEST_UNAVAILABLE(false, MediaRejection.UNAVAILABLE),
        URL_STALE(true), SESSION_CHANGED(true), TRANSPORT_FAILED(true);
        fun afterLightBudget(): Reason = if (this == URL_STALE) HTTP_403 else this
    }
    fun classify(error: Throwable): Reason? {
        val seen = mutableSetOf<Throwable>(); var cause: Throwable? = error
        var transportFailure = false
        var tlsHandshake = false
        while (cause != null && seen.add(cause)) {
            // Certificate/identity failures are terminal even if an outer TLS exception
            // also mentions a closed connection. Only transient disconnects are retried.
            if (cause is CertificateException || cause is SSLPeerUnverifiedException) return null
            if (cause is IOException && cause.message == "MEDIA_SESSION_CHANGED") return Reason.SESSION_CHANGED
            if (cause is IOException && cause.message == "MEDIA_ERROR_BODY") return Reason.INVALID_MEDIA_RESPONSE
            if (cause is IOException && cause.message in setOf("MEDIA_OBJECT_CHANGED", "MEDIA_URL_EXPIRED")) return Reason.URL_STALE
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException ->
                    classifyHttp(cause.responseCode, cause.dataSpec.uri.toString())?.let { return it }
                is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
                is UnknownHostException -> transportFailure = true
                is SSLHandshakeException, is SSLProtocolException -> {
                    tlsHandshake = true
                    if (cause.message?.lowercase(java.util.Locale.ROOT) in setOf(
                            "connection closed", "connection closed by peer", "connection reset",
                            "connection reset by peer", "remote host terminated the handshake")) transportFailure = true
                }
                is EOFException, is SocketException -> if (tlsHandshake) transportFailure = true
            }
            cause = cause.cause
        }
        return if (transportFailure) Reason.TRANSPORT_FAILED else null
    }

    internal fun classifyHttp(status: Int, url: String): Reason? {
        if (status == 403) return Reason.HTTP_403
        if (status != 404 && status != 410) return null
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        val manifest = path.endsWith(".m3u8", true) || path.endsWith(".mpd", true) ||
            path.startsWith("/api/manifest/hls_") || path.startsWith("/api/manifest/dash/")
        return Reason.MANIFEST_UNAVAILABLE.takeIf { manifest }
    }
}
