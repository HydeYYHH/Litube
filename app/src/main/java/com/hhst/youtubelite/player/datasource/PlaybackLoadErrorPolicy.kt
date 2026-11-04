@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Let source recovery own invalid URLs and initialization requests that never made progress. */
internal class PlaybackLoadErrorPolicy(private val nanoTime: () -> Long = System::nanoTime) : DefaultLoadErrorHandlingPolicy() {
    override fun getFallbackSelectionFor(
        options: LoadErrorHandlingPolicy.FallbackOptions,
        info: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = when (PlaybackRecovery.classify(info.exception)) {
        PlaybackRecovery.Reason.HTTP_403, PlaybackRecovery.Reason.INVALID_MEDIA_RESPONSE, PlaybackRecovery.Reason.MANIFEST_UNAVAILABLE,
        PlaybackRecovery.Reason.URL_STALE, PlaybackRecovery.Reason.SESSION_CHANGED -> null
        else -> super.getFallbackSelectionFor(options, info)
    }

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val readiness = generateSequence<Throwable>(info.exception) { it.cause }
            .take(16).filterIsInstance<MediaNotReadyException>().firstOrNull()
        readiness?.let {
            val remaining = it.retryAtNanos - nanoTime()
            if (remaining > 0) return ((remaining + 999_999) / 1_000_000).coerceAtMost(30_000)
        }
        return when (PlaybackRecovery.classify(info.exception)) {
            PlaybackRecovery.Reason.HTTP_403, PlaybackRecovery.Reason.INVALID_MEDIA_RESPONSE, PlaybackRecovery.Reason.MANIFEST_UNAVAILABLE, PlaybackRecovery.Reason.URL_STALE,
            PlaybackRecovery.Reason.SESSION_CHANGED -> C.TIME_UNSET
            PlaybackRecovery.Reason.TRANSPORT_FAILED ->
                if (info.mediaLoadData.dataType == C.DATA_TYPE_MEDIA_INITIALIZATION &&
                    info.loadEventInfo.bytesLoaded == 0L) C.TIME_UNSET
                else super.getRetryDelayMsFor(info)
            null -> super.getRetryDelayMsFor(info)
        }
    }
}
