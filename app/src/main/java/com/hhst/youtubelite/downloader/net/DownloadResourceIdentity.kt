package com.hhst.youtubelite.downloader.net

import java.util.Base64
import java.security.MessageDigest
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

    fun of(videoId: String, format: Format): String {
        val key = format.formatKey ?: return ofUrl(videoId, format.url, format.itag)
        val resource = format.resourceIdentity?.takeIf { it.isNotBlank() }
            ?: return "dl:unproven:${YoutubeSessionDigest.of(format.url)}:0"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$resource:${key.audioTrack}:${key.codec}:${key.drc}:${key.hdr}:${key.protocol}".toByteArray())
        val length = format.requestPlan?.resourceLength?.takeIf { it > 0 }
            ?: DownloadBitrate.queryParam(format.url, "clen")?.toLongOrNull() ?: 0
        return "dl:v2:$encoded:$length"
    }

    /** Legacy tasks may resume only on one unambiguous, matching media object. */
    fun matching(videoId: String, previous: String, formats: List<Format>): Format? {
        val exact = formats.filter { of(videoId, it) == previous }
        if (exact.isNotEmpty()) return exact.first()
        if (previous.startsWith("dl:v2:")) return null
        val legacy = formats.filter { ofUrl(videoId, it.url, it.itag) == previous }
            .distinctBy { it.formatKey ?: listOf(it.itag, it.audioTrackId, it.codec) }
        return legacy.singleOrNull()
    }

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

private object YoutubeSessionDigest {
    fun of(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
