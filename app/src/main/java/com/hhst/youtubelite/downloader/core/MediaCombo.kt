@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.core

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Media3 MP4/M4A combo matrix for the downloader mux path.
 *
 * [ENABLED] means Mp4Muxer lists the MIME as muxable and this tree proved
 * extract and/or remux. [GATED] means the muxer claims support but extract/mux
 * /playback has not yet been proven on a representative sample.
 */
object MediaCombo {

    enum class Status { ENABLED, GATED }

    data class Entry(
        val container: String,
        val videoMime: String?,
        val audioMime: String?,
        val status: Status,
        val note: String,
    )

    data class Sample(
        val timeUs: Long,
        val flags: Int,
        val data: ByteArray,
    )

    data class Track(
        val format: Format,
        val samples: List<Sample>,
    )

    val muxerVideoMimes: List<String> = Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES
    val muxerAudioMimes: List<String> = Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES

    fun matrix(provenAvcAac: Boolean, provenAacM4a: Boolean): List<Entry> = listOf(
        Entry(
            container = "MP4",
            videoMime = MimeTypes.VIDEO_H264,
            audioMime = MimeTypes.AUDIO_AAC,
            status = if (provenAvcAac) Status.ENABLED else Status.GATED,
            note = "Minimum acceptance combo (YouTube itag 18 / 137+140).",
        ),
        Entry(
            container = "M4A",
            videoMime = null,
            audioMime = MimeTypes.AUDIO_AAC,
            status = if (provenAacM4a) Status.ENABLED else Status.GATED,
            note = "Audio-only MP4 (m4a) via Mp4Muxer AAC track.",
        ),
        Entry(
            container = "MP4",
            videoMime = MimeTypes.VIDEO_H265,
            audioMime = MimeTypes.AUDIO_AAC,
            status = Status.GATED,
            note = "Muxer-listed; pending extract/mux/playback proof.",
        ),
        Entry(
            container = "MP4",
            videoMime = MimeTypes.VIDEO_VP9,
            audioMime = MimeTypes.AUDIO_AAC,
            status = Status.GATED,
            note = "Muxer-listed; YouTube VP9 is usually WEBM, not MP4.",
        ),
        Entry(
            container = "MP4",
            videoMime = MimeTypes.VIDEO_AV1,
            audioMime = MimeTypes.AUDIO_AAC,
            status = Status.GATED,
            note = "Muxer-listed; playback still hardware-gated (see CodecCapabilities).",
        ),
        Entry(
            container = "MP4",
            videoMime = MimeTypes.VIDEO_DOLBY_VISION,
            audioMime = MimeTypes.AUDIO_AAC,
            status = Status.GATED,
            note = "Muxer-listed; not a YouTube adaptive download target.",
        ),
        Entry(
            container = "MP4",
            videoMime = MimeTypes.VIDEO_H264,
            audioMime = MimeTypes.AUDIO_OPUS,
            status = Status.GATED,
            note = "Muxer-listed; YouTube Opus is typically WEBM audio.",
        ),
    )

    fun mux(tracks: List<Track>, dest: File) {
        FileOutputStream(dest).use { stream ->
            @Suppress("DEPRECATION")
            Mp4Muxer.Builder(stream).build().use { muxer ->
                val ids = tracks.map { muxer.addTrack(it.format) }
                tracks.forEachIndexed { index, track ->
                    track.samples.forEach { sample ->
                        muxer.writeSampleData(
                            ids[index],
                            ByteBuffer.wrap(sample.data),
                            BufferInfo(sample.timeUs, sample.data.size, sample.flags),
                        )
                    }
                }
            }
        }
    }
}
