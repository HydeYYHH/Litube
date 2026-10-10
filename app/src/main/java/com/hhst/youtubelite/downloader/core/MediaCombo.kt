@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.core

import androidx.media3.common.MimeTypes

/**
 * Media3 MP4/M4A combo matrix for the downloader mux path.
 *
 * [Status.ENABLED] means Mp4Muxer lists the MIME as muxable and this tree proved
 * extract and/or remux. [Status.GATED] means the muxer claims support but extract
 * /mux/playback has not yet been proven on a representative sample.
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
        // Audio-only MP4 (m4a) via Mp4Muxer AAC track.
        Entry("M4A", null, MimeTypes.AUDIO_AAC,
            if (provenAacM4a) Status.ENABLED else Status.GATED),
        // Muxer-listed; pending extract/mux/playback proof.
        Entry("MP4", MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, Status.GATED),
        // Muxer-listed; VP9 is usually WEBM, not MP4.
        Entry("MP4", MimeTypes.VIDEO_VP9, MimeTypes.AUDIO_AAC, Status.GATED),
        // Muxer-listed; playback still hardware-gated.
        Entry("MP4", MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_AAC, Status.GATED),
        // Muxer-listed; not an adaptive download target.
        Entry("MP4", MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.AUDIO_AAC, Status.GATED),
        // Muxer-listed; Opus is typically WEBM audio.
        Entry("MP4", MimeTypes.VIDEO_H264, MimeTypes.AUDIO_OPUS, Status.GATED),
    )
}
