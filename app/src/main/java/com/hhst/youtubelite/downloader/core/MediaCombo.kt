@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.core

import androidx.media3.common.MimeTypes

/**
 * Media3 MP4/M4A combo matrix for the downloader mux path.
 * ENABLED = muxable and proven extract/remux; GATED = muxer claims support but unproven.
 */
object MediaCombo {

    enum class Status { ENABLED, GATED }

    data class Entry(
        val container: String,
        val videoMime: String?,
        val audioMime: String?,
        val status: Status,
    )

    fun matrix(provenAvcAac: Boolean, provenAacM4a: Boolean): List<Entry> = listOf(
        // Minimum acceptance combo (itag 18 / 137+140).
        Entry("MP4", MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC,
            if (provenAvcAac) Status.ENABLED else Status.GATED),
        // Audio-only m4a via Mp4Muxer AAC track.
        Entry("M4A", null, MimeTypes.AUDIO_AAC,
            if (provenAacM4a) Status.ENABLED else Status.GATED),
        Entry("MP4", MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, Status.GATED),
        // VP9 is usually WEBM, not MP4.
        Entry("MP4", MimeTypes.VIDEO_VP9, MimeTypes.AUDIO_AAC, Status.GATED),
        // Playback still hardware-gated.
        Entry("MP4", MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_AAC, Status.GATED),
        // Not an adaptive download target.
        Entry("MP4", MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.AUDIO_AAC, Status.GATED),
        // Opus is typically WEBM audio.
        Entry("MP4", MimeTypes.VIDEO_H264, MimeTypes.AUDIO_OPUS, Status.GATED),
    )
}
