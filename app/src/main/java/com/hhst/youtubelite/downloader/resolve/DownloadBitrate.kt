package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.extractor.Format

/**
 * Playback stores video [Format.bitrate] in bits/s and audio
 * [Format.bitrate] as NewPipe `averageBitrate` when present (typically kb/s).
 * Download expected-size math always uses bits/s. Playback display/selection
 * is not changed here.
 */
object DownloadBitrate {
    /** Audio values below this are treated as kb/s; YouTube audio bps is ≥ ~30 kb/s * 1000. */
    internal const val AUDIO_KBPS_CEILING = 2048L

    fun bitsPerSecond(format: Format): Long {
        val raw = format.bitrate.toLong()
        if (raw <= 0L) return 0L
        if (format.audioOnly && raw < AUDIO_KBPS_CEILING) return raw * 1000L
        return raw
    }

    fun expectedBytes(format: Format, durationSec: Long): Long? {
        contentLengthFromUrl(format.url)?.let { return it }
        val bps = bitsPerSecond(format)
        val ms = format.approxDurationMs.takeIf { it > 0 } ?: durationSec.takeIf { it > 0 }?.times(1000)
        if (bps <= 0L || ms == null || ms <= 0L) return null
        return (bps * ms) / 8_000L
    }

    fun contentLengthFromUrl(url: String): Long? {
        val clen = queryParam(url, "clen")?.toLongOrNull()
        return clen?.takeIf { it > 0L }
    }

    internal fun queryParam(url: String, name: String): String? {
        val queryStart = url.indexOf('?').takeIf { it >= 0 } ?: return null
        val query = url.substring(queryStart + 1)
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq) == name) {
                return part.substring(eq + 1).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
