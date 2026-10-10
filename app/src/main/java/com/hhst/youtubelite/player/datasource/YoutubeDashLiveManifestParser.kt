package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.ProgramInformation
import androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement

/**
 * Compatibility with live DASH manifests. Those dynamic MPDs advertise
 * a real `availabilityStartTime`, but the segment timeline is already relative
 * to the live edge. Media3 then computes the seek window from the epoch and
 * clamps live playback incorrectly. Setting AST to zero keeps the window
 * relative to the listed periods. This matches current live DASH and
 * is not a general DASH rule.
 */
@UnstableApi
open class YoutubeDashLiveManifestParser : DashManifestParser() {

    override fun buildMediaPresentationDescription(
        availabilityStartTimeMs: Long,
        durationMs: Long,
        minBufferTimeMs: Long,
        dynamic: Boolean,
        minUpdatePeriodMs: Long,
        timeShiftBufferDepthMs: Long,
        suggestedPresentationDelayMs: Long,
        publishTimeMs: Long,
        programInformation: ProgramInformation?,
        utcTiming: UtcTimingElement?,
        serviceDescription: ServiceDescriptionElement?,
        location: Uri?,
        periods: List<Period>,
    ): DashManifest = super.buildMediaPresentationDescription(
        0L, durationMs, minBufferTimeMs, dynamic, minUpdatePeriodMs,
        timeShiftBufferDepthMs, suggestedPresentationDelayMs, publishTimeMs,
        programInformation, utcTiming, serviceDescription, location, periods,
    )
}
