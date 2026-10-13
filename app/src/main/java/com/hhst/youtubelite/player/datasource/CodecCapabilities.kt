@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import androidx.annotation.VisibleForTesting
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.hhst.youtubelite.extractor.Format
import androidx.media3.common.Format as MediaFormat
import android.media.MediaFormat as PlatformFormat

/** Filters source formats using decoder profiles, dimensions and frame rates. */
object CodecCapabilities {
    internal class Decoder(val hardware: Boolean, val supports: (Format) -> Boolean)

    private val cache = HashMap<String, List<Decoder>?>()
    private var testDecoders: Map<String, List<Decoder>?>? = null

    fun isDecodable(format: Format): Boolean {
        if (format.audioOnly) return true
        val codec = format.codec?.lowercase()
        val mime = MimeTypes.getVideoMediaMimeType(codec) ?: when {
            codec?.startsWith("av1") == true -> MimeTypes.VIDEO_AV1
            codec?.startsWith("h264") == true -> MimeTypes.VIDEO_H264
            codec?.startsWith("h265") == true || codec?.startsWith("hevc") == true -> MimeTypes.VIDEO_H265
            else -> null
        }
        val av1 = mime == MimeTypes.VIDEO_AV1
        val decoders = mime?.let(::decoders)
            ?: return !av1 && format.height <= 1080
        if (format.width <= 0 || format.height <= 0) return !av1 && format.height <= 1080 && decoders.isNotEmpty()
        return decoders.any { decoder ->
            (decoder.hardware || !av1 && format.height <= 1080) &&
                runCatching { decoder.supports(format) }.getOrDefault(false)
        }
    }

    @Synchronized
    private fun decoders(mime: String): List<Decoder>? {
        testDecoders?.let { return it[mime] }
        if (cache.containsKey(mime)) return cache[mime]
        val result = runCatching {
            MediaCodecUtil.getDecoderInfos(mime, false, false).map { info ->
                Decoder(info.hardwareAccelerated) { source ->
                    val format = MediaFormat.Builder().setSampleMimeType(mime).setCodecs(source.codec)
                        .setWidth(source.width).setHeight(source.height)
                        .setFrameRate(source.fps.takeIf { it > 0 }?.toFloat() ?: MediaFormat.NO_VALUE.toFloat())
                        .build()
                    val platform = PlatformFormat.createVideoFormat(mime, source.width, source.height)
                    if (source.fps > 0) platform.setFloat(PlatformFormat.KEY_FRAME_RATE, source.fps.toFloat())
                    CodecSpecificDataUtil.getCodecProfileAndLevel(format)?.let { profile ->
                        platform.setInteger(PlatformFormat.KEY_PROFILE, profile.first)
                        platform.setInteger(PlatformFormat.KEY_LEVEL, profile.second)
                    }
                    info.capabilities?.isFormatSupported(platform) == true
                }
            }
        }.getOrNull()
        cache[mime] = result
        return result
    }

    @VisibleForTesting
    @Synchronized
    internal fun setDecodersForTest(decoders: Map<String, List<Decoder>?>) {
        testDecoders = decoders
    }

    @VisibleForTesting
    @Synchronized
    fun resetForTest() {
        cache.clear()
        testDecoders = null
    }
}
