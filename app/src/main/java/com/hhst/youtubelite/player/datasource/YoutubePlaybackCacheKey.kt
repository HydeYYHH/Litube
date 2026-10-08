package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import java.security.MessageDigest
import java.util.Base64

/**
 * Byte-cache identity for googlevideo `/videoplayback` URLs.
 *
 * Two schemes coexist, one per call site, and their prefixes never collide:
 * - [of] keys by the extractor's opaque resource identity ("yt:v3:…"). Rotating
 *   `sig` / `expire` / `pot` / `rn` are omitted so a 403 URL refresh still hits
 *   previously cached bytes.
 * - [ofQuery] keys by raw `/videoplayback` query parameters ("yt:v2:…"); [VERSION]
 *   isolates pre-v2 `yt:id:itag` entries.
 */
object YoutubePlaybackCacheKey {
    fun of(format: Format): String {
        val identity = format.resourceIdentity
        return if (identity.isNullOrBlank() || format.formatKey == null) "yt:unproven:" + MessageDigest.getInstance("SHA-256").digest(format.url.toByteArray()).joinToString("") { "%02x".format(it) }
        else "yt:v3:" + Base64.getUrlEncoder().withoutPadding().encodeToString(identity.toByteArray())
    }
    private const val VERSION = 2

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
