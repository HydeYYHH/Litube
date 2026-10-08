@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.resolve

import androidx.media3.common.MimeTypes
import com.hhst.youtubelite.downloader.core.MediaCombo
import com.hhst.youtubelite.extractor.Format

/**
 * Mux whitelist: AVC+AAC MP4 and AAC M4A are enabled. HEVC/VP9/AV1/Dolby
 * Vision/H.264+Opus stay gated until extract/mux/playback is proven.
 */
object DownloadCodecs {

    private fun videoSampleMime(format: Format): String? = mimeFromCodec(format.codec, video = true)
        ?: format.mimeType.takeIf { it.startsWith("video/") }

    private fun audioSampleMime(format: Format): String? = mimeFromCodec(format.codec, video = false)
        ?: format.mimeType.takeIf { it.startsWith("audio/") }

    private fun isMp4Family(format: Format): Boolean {
        val container = format.container?.uppercase().orEmpty()
        val mime = format.mimeType.lowercase()
        return container == "MPEG_4" || container == "MP4" || container == "M4A" ||
            mime.contains("mp4") || mime.contains("m4a")
    }

    fun videoEnabled(format: Format): Boolean {
        if (!isMp4Family(format)) return false
        val mime = videoSampleMime(format) ?: return false
        return MediaCombo.matrix(provenAvcAac = true, provenAacM4a = true).any {
            it.status == MediaCombo.Status.ENABLED && it.videoMime == mime
        }
    }

    fun audioEnabled(format: Format): Boolean {
        if (!isMp4Family(format)) return false
        val mime = audioSampleMime(format) ?: return false
        return MediaCombo.matrix(provenAvcAac = true, provenAacM4a = true).any {
            it.status == MediaCombo.Status.ENABLED && it.audioMime == mime
        }
    }

    fun comboEnabled(video: Format?, audio: Format?, audioOnly: Boolean): Boolean {
        if (audioOnly) return audio != null && audioEnabled(audio)
        if (video != null && audio == null) {
            return !video.videoOnly && videoEnabled(video) && muxedAudioEnabled(video)
        }
        if (video == null || audio == null) return false
        val videoMime = videoSampleMime(video) ?: return false
        val audioMime = audioSampleMime(audio) ?: return false
        return MediaCombo.matrix(provenAvcAac = true, provenAacM4a = true).any {
            it.status == MediaCombo.Status.ENABLED &&
                it.container == "MP4" &&
                it.videoMime == videoMime &&
                it.audioMime == audioMime
        }
    }

    private fun muxedAudioEnabled(muxed: Format): Boolean {
        val mime = audioSampleMime(muxed) ?: MimeTypes.AUDIO_AAC
        return mime == MimeTypes.AUDIO_AAC
    }

    private fun mimeFromCodec(codec: String?, video: Boolean): String? {
        val lower = codec?.lowercase() ?: return null
        if (video) {
            return when {
                lower.startsWith("avc") || lower.startsWith("h264") -> MimeTypes.VIDEO_H264
                lower.contains("h265") || lower.contains("hevc") ||
                    lower.startsWith("hvc1") || lower.startsWith("hev1") -> MimeTypes.VIDEO_H265
                lower.startsWith("vp9") || lower.startsWith("vp09") || lower.contains("vp8") ->
                    MimeTypes.VIDEO_VP9
                lower.contains("av01") || lower.contains("av1") -> MimeTypes.VIDEO_AV1
                lower.startsWith("dvh1") || lower.startsWith("dvhe") -> MimeTypes.VIDEO_DOLBY_VISION
                else -> null
            }
        }
        return when {
            lower.startsWith("mp4a") || lower.contains("aac") -> MimeTypes.AUDIO_AAC
            lower.contains("opus") -> MimeTypes.AUDIO_OPUS
            else -> null
        }
    }
}
