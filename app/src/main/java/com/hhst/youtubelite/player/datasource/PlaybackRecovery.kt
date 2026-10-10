@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.datasource

import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object PlaybackRecovery {
    enum class Reason(val needsLightRefresh: Boolean) {
        HTTP_403(false), URL_STALE(true), SESSION_CHANGED(true), TRANSPORT_FAILED(true);
        fun afterLightBudget(): Reason = if (this == URL_STALE) HTTP_403 else this
    }
    fun classify(error: Throwable): Reason? {
        val seen = mutableSetOf<Throwable>(); var cause: Throwable? = error
        var transportFailure = false
        while (cause != null && seen.add(cause)) {
            if (cause is IOException && cause.message == "MEDIA_SESSION_CHANGED") return Reason.SESSION_CHANGED
            if (cause is IOException && cause.message in setOf("MEDIA_OBJECT_CHANGED", "MEDIA_URL_EXPIRED")) return Reason.URL_STALE
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> if (cause.responseCode == 403) return Reason.HTTP_403
                is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
                is UnknownHostException -> transportFailure = true
            }
            cause = cause.cause
        }
        return if (transportFailure) Reason.TRANSPORT_FAILED else null
    }
}
