@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Let source recovery own invalid URLs and initialization requests that never made progress. */
internal class PlaybackLoadErrorPolicy(private val nanoTime: () -> Long = System::nanoTime) : DefaultLoadErrorHandlingPolicy() {
    /** One quick probe before trusting an ad-derived availability estimate. */
    private companion object {
        const val PROBE_RETRY_MS = 600L
    }

    override fun getFallbackSelectionFor(
        options: LoadErrorHandlingPolicy.FallbackOptions,
        info: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = if (info.isOpenTimeout()) null else when (PlaybackRecovery.classify(info.exception)) {
        PlaybackRecovery.Reason.HTTP_403, PlaybackRecovery.Reason.INVALID_MEDIA_RESPONSE, PlaybackRecovery.Reason.MANIFEST_UNAVAILABLE,
        PlaybackRecovery.Reason.URL_STALE, PlaybackRecovery.Reason.SESSION_CHANGED -> null
        else -> super.getFallbackSelectionFor(options, info)
    }

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // A seek chunk that never got response headers must reach the same finite
        // source recovery as a failed init read, rather than paying this budget repeatedly.
        if (info.isOpenTimeout()) return C.TIME_UNSET
        val readiness = generateSequence<Throwable>(info.exception) { it.cause }
            .take(16).filterIsInstance<MediaNotReadyException>().firstOrNull()
        readiness?.let {
            val remaining = it.retryAtNanos - nanoTime()
            if (remaining > 0) {
                // The dry horizon comes from a pre-roll ad estimate, not a real content gate: the
                // refusal is usually transient. Probe once quickly, then accept the estimate, so a
                // gated source only pays the short delay instead of stalling the first frame.
                if (info.errorCount <= 1) return PROBE_RETRY_MS
                return ((remaining + 999_999) / 1_000_000).coerceAtMost(30_000)
            }
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

    private fun LoadErrorHandlingPolicy.LoadErrorInfo.isOpenTimeout(): Boolean =
        loadEventInfo.bytesLoaded == 0L && generateSequence<Throwable>(exception) { it.cause }
            .take(16).any { it is java.net.SocketTimeoutException && it.message == "MEDIA_OPEN_TIMEOUT" }
}
