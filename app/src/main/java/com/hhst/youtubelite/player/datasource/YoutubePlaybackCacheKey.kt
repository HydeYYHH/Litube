package com.hhst.youtubelite.player.datasource

/**
 * Byte-cache identity for googlevideo `/videoplayback` URLs.
 *
 * Version prefix isolates older `yt:id:itag` entries so they cannot be
 * replayed as a different language or content-length. Rotating `sig` /
 * `expire` / `pot` / `rn` are omitted so a 403 URL refresh still hits
 * previously cached bytes.
 */
object YoutubePlaybackCacheKey {
    const val VERSION = 2

    fun ofQuery(query: String): String? {
        if (query.isEmpty()) return null
        val params = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            params[part.substring(0, eq)] = part.substring(eq + 1)
        }
        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val itag = params["itag"]?.takeIf { it.isNotBlank() } ?: return null
        val xtags = params["xtags"].orEmpty()
        val clen = params["clen"].orEmpty()
        return "yt:v$VERSION:$id:$itag:$xtags:$clen"
    }
}
