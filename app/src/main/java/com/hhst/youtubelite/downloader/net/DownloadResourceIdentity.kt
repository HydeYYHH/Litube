package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.resolve.DownloadBitrate
import com.hhst.youtubelite.extractor.Format

/**
 * Stable media-object identity for a download component.
 *
 * Expired googlevideo URLs (`expire`, `sig`, `pot`, `rn`, `range`) are not
 * identity. Playback's SimpleCache key (`yt:v2:…` under the `player/` dir) is
 * also not a download identity.
 */
object DownloadResourceIdentity {
    const val PREFIX = "dl:"

    fun of(videoId: String, format: Format): String = ofUrl(videoId, format.url, format.itag)

    fun ofUrl(videoId: String, url: String, itag: Int? = null): String {
        val id = DownloadBitrate.queryParam(url, "id")?.takeIf { it.isNotBlank() } ?: videoId
        val tag = DownloadBitrate.queryParam(url, "itag") ?: itag?.toString().orEmpty()
        val xtags = DownloadBitrate.queryParam(url, "xtags").orEmpty()
        val clen = DownloadBitrate.queryParam(url, "clen").orEmpty()
        return "$PREFIX$id:$tag:$xtags:$clen"
    }

    fun contentLength(identity: String): Long? {
        if (!identity.startsWith(PREFIX)) return null
        return identity.substringAfterLast(':').toLongOrNull()?.takeIf { it > 0L }
    }

    /** Same object only when id/itag/xtags/clen all match and length is present. */
    fun proven(previous: String, refreshed: String): Boolean {
        if (previous.isBlank() || refreshed.isBlank()) return false
        if (previous == refreshed) return contentLength(previous) != null
        return false
    }
}
