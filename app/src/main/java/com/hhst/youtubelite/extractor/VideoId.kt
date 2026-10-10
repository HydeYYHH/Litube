package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.core.YoutubeThumbnail

/** Parses bare 11-char ids and common watch, shorts, and short-link URLs. */
object VideoId {
    private val fromUrl = Regex(
        """(?:v=|=v/|/v/|/u/\w/|embed/|watch\?v=|shorts/|live/|youtu\.be/)([a-zA-Z0-9_-]{11})""",
    )
    private val bare = Regex("""^[a-zA-Z0-9_-]{11}$""")

    /** Returns the 11-char id, or null if [input] cannot be parsed. */
    fun parse(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        if (bare.matches(trimmed)) return trimmed
        return fromUrl.find(trimmed)?.groupValues?.getOrNull(1)
    }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    // Host single-sourced with the thumbnail helper: if one side changed without
    // the other, every thumbnail fetch would silently fail its allowlist.
    fun thumbnailUrl(videoId: String): String =
        "https://${YoutubeThumbnail.HOST}/vi/$videoId/hqdefault.jpg"

    /**
     * Share/start offset from `t=` / `start=` (query or `#t=`).
     * Invalid values are ignored so history resume can still apply.
     */
    fun startPositionMs(url: String?): Long? {
        if (url.isNullOrBlank()) return null
        val raw = timeParam(url) ?: return null
        val seconds = parseYoutubeTimeSeconds(raw) ?: return null
        return seconds * 1000L
    }

    private fun timeParam(url: String): String? {
        queryParam(url, "t")?.let { return it }
        queryParam(url, "start")?.let { return it }
        val hashIdx = url.indexOf('#')
        if (hashIdx >= 0) {
            hashTParam.find(url.substring(hashIdx))?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun queryParam(url: String, name: String): String? {
        val cut = url.substringBefore('#')
        val regex = Regex("[?&]$name=([^&]*)")
        return regex.find(cut)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    private val hashTParam = Regex("""[#&]t=([^&]+)""")

    /**
     * `t=` forms: `90`, `90s`, `1m30s`, `1h2m3s`.
     * Matches player-hook.js `parseTime` (seconds unit may omit the `s`).
     */
    private fun parseYoutubeTimeSeconds(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return if (it >= 0L) it else null }
        val m = youtubeHms.matchEntire(trimmed) ?: return null
        val h = m.groupValues[1]
        val min = m.groupValues[2]
        val s = m.groupValues[3]
        val hours = h.toLongOrNull() ?: 0L
        val minutes = min.toLongOrNull() ?: 0L
        val seconds = s.toLongOrNull() ?: 0L
        return hours * 3600L + minutes * 60L + seconds
    }

    private val youtubeHms = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$""")
}
