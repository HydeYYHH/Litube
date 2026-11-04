package com.hhst.youtubelite.extractor

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Only replay read endpoints; token minting and account mutations are never replayed. */
internal object ExtractionReadRetry {
    fun allowed(method: String, url: String, error: IOException): Boolean {
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        if (target.scheme != "https" || target.host !in setOf("www.youtube.com", "youtubei.googleapis.com")) return false
        val read = when (method) {
            "GET" -> target.path in setOf("/", "/watch") || target.path.startsWith("/s/player/")
            "POST" -> target.path in setOf("/youtubei/v1/player", "/youtubei/v1/next", "/youtubei/v1/reel/reel_item_watch")
            else -> false
        }
        return read && isTransient(error)
    }

    /** Also used by the separately validated, same-identity session page reader. */
    fun isTransient(error: IOException): Boolean {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        if (causes.any { it is CertificateException || it is SSLPeerUnverifiedException || it is ProtocolException ||
                it is InterruptedException || it.message.equals("canceled", true) || it.message.equals("cancelled", true) }) return false
        return causes.any {
            it is EOFException || it is UnknownHostException || it is ConnectException ||
                it is NoRouteToHostException || it is SocketException ||
                (it is SSLException && listOf("connection closed", "connection reset", "unexpected end of stream",
                    "peer shut down incorrectly").any { reason -> it.message?.contains(reason, true) == true }) ||
                it is SocketTimeoutException || (it is InterruptedIOException && it.message == "timeout") ||
                (it is IOException && it.message?.startsWith("unexpected end of stream", true) == true)
        }
    }

    /** Fixed labels only: never include signed URLs, request bodies or account headers. */
    fun stage(url: String): String = when (runCatching { URI(url).path }.getOrNull()) {
        "/youtubei/v1/player" -> "player"
        "/youtubei/v1/next" -> "next"
        "/youtubei/v1/reel/reel_item_watch" -> "reel"
        "/", "/watch" -> "page"
        else -> if (runCatching { URI(url).path.startsWith("/s/player/") }.getOrDefault(false)) "player-source" else "other"
    }
}
