package com.hhst.youtubelite.core

import java.net.URI

private val coverPath = Regex("/(vi|vi_webp)/([A-Za-z0-9_-]{11})/(default|mqdefault|hqdefault|sddefault|hq720|maxresdefault)\\.(jpg|webp)")

/** Nearby card sizes share a decoded image, including during window resizing. */
internal fun artworkDecodeDimension(targetPx: Int): Int =
    ((targetPx.coerceIn(64, 1600) + 63) / 64 * 64).coerceAtMost(1600)

internal fun artworkSources(url: String?, targetPx: Int): List<String> {
    if (url.isNullOrBlank()) return emptyList()
    if (targetPx <= 320) return listOf(url)
    val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
    if (uri.scheme != "https" || uri.host !in setOf("i.ytimg.com", "i9.ytimg.com") ||
        uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return listOf(url)
    val match = coverPath.matchEntire(uri.path) ?: return listOf(url)
    if (match.groupValues[3] in setOf("hq720", "maxresdefault") && uri.rawQuery == null) return listOf(url)
    val directory = match.groupValues[1]
    val extension = if (directory == "vi_webp") "webp" else "jpg"
    val preferred = "https://${uri.host}/$directory/${match.groupValues[2]}/hq720.$extension"
    return listOf(preferred, url).distinct()
}
