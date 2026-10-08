package com.hhst.youtubelite.player.datasource

import android.media.MediaCodecList
import androidx.annotation.VisibleForTesting
import com.hhst.youtubelite.extractor.Format

/**
 * Device decoder capabilities applied to the format pool before selection.
 *
 * YouTube's highest-bitrate formats increasingly use AV1 (itags 398–401);
 * most mid-range devices have no AV1 hardware decoder, and Media3's software
 * fallback (libgav1) drops frames well below real-time at 1080p60. An
 * undecodable pick is worse than a lower-resolution one, so AV1 is kept only
 * when a hardware decoder exists and the height is at or below
 * [AV1_MAX_HEIGHT_PX].
 *
 * The check result is cached per codec-mime in a map so repeated resolve()
 * calls (quality switch, fallback rebuild) do not rescan the codec list.
 */
object CodecCapabilities {

    /** AV1 accepted only up to this height even with a hardware decoder. */
    private const val AV1_MAX_HEIGHT_PX = 1080

    private val hardwareMimes = HashMap<String, Boolean>()
    private var scanned = false

    /**
     * True when [format]'s codec can be decoded by this device at its
     * resolution. Formats without a known codec are assumed decodable.
     */
    fun isDecodable(format: Format): Boolean {
        val codec = format.codec ?: return true
        return if (isAv1(codec)) av1Supported(format) else true
    }

    private fun isAv1(codec: String): Boolean =
        codec.contains("av01", ignoreCase = true) || codec.contains("av1", ignoreCase = true)

    private fun av1Supported(format: Format): Boolean =
        hasHardwareDecoder("video/av01") && format.height <= AV1_MAX_HEIGHT_PX

    /** Hardware decoder presence for [mime], cached after the first scan. */
    @Synchronized
    private fun hasHardwareDecoder(mime: String): Boolean {
        hardwareMimes[mime]?.let { return it }
        if (!scanned) {
            scan()
            scanned = true
        }
        return hardwareMimes.getOrDefault(mime, false)
    }

    /**
     * Collects hardware decoder mimes once from [MediaCodecList]. Software
     * decoders (c2.android.* / OMX.google.*) are skipped: REGULAR_CODECS lists
     * them too, and counting them would report AV1 as "hardware" on exactly
     * the mid-range devices whose software AV1 decode janks.
     */
    private fun scan() {
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val name = info.name.lowercase()
                if (name.startsWith("c2.android") || name.startsWith("c2.google") ||
                    name.startsWith("omx.google")
                ) {
                    continue
                }
                for (type in info.supportedTypes) {
                    hardwareMimes[type] = true
                }
            }
        }
    }

    /** Test hook: inject scan results and skip the platform query. */
    @VisibleForTesting
    @Synchronized
    fun setHardwareMimesForTest(mimes: Map<String, Boolean>) {
        hardwareMimes.clear()
        hardwareMimes.putAll(mimes)
        scanned = true
    }

    /** Test hook: forget injected results. */
    @VisibleForTesting
    @Synchronized
    fun resetForTest() {
        hardwareMimes.clear()
        scanned = false
    }
}
