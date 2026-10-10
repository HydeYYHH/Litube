@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Let source recovery own invalid URLs and initialization requests that never made progress. */
internal class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        return when (PlaybackRecovery.classify(info.exception)) {
            PlaybackRecovery.Reason.HTTP_403, PlaybackRecovery.Reason.URL_STALE,
            PlaybackRecovery.Reason.SESSION_CHANGED -> C.TIME_UNSET
            PlaybackRecovery.Reason.TRANSPORT_FAILED ->
                if (info.mediaLoadData.dataType == C.DATA_TYPE_MEDIA_INITIALIZATION &&
                    info.loadEventInfo.bytesLoaded == 0L) C.TIME_UNSET
                else super.getRetryDelayMsFor(info)
            null -> super.getRetryDelayMsFor(info)
        }
    }
}
