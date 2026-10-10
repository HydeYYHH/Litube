package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants
import java.net.URI
import java.util.Locale

/** Maps watch URLs to routing kinds. */
object PageKind {

    /** Bottom-nav surfaces; each reuses one tab. */
    val NAV: Set<String> = setOf(
        Constants.PAGE_HOME,
        Constants.PAGE_SHORTS,
        Constants.PAGE_SUBSCRIPTIONS,
        Constants.PAGE_LIBRARY,
    )

    /** Native player overlays watch, live and embed pages. */
    fun isPlayerSurface(kind: String?): Boolean =
        kind == Constants.PAGE_WATCH

    /** True when [url] is a /shorts/ URL (case-insensitive). */
    fun isShorts(url: String?): Boolean =
        of(url) == Constants.PAGE_SHORTS

    fun of(url: String?): String {
        if (url.isNullOrBlank()) return "unknown"
        return try {
            val uri = URI(url)
            val host = uri.host ?: return "unknown"
            val path = uri.path
            val segments = if (path.isNullOrEmpty()) {
                emptyList()
            } else {
                path.split('/').filter { it.isNotEmpty() }
            }
            fromHost(host, segments)
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun fromHost(host: String, segments: List<String>): String {
        val lowerHost = host.lowercase(Locale.ROOT)
        if (lowerHost == "youtu.be") {
            return if (segments.isEmpty()) "unknown" else Constants.PAGE_WATCH
        }
        // Any first-party host or subdomain (aligned with UrlPolicy / nav.js).
        if (!isYoutubeHost(lowerHost)) return "unknown"
        if (segments.isEmpty()) return Constants.PAGE_HOME

        val first = segments[0].lowercase(Locale.ROOT)
        if (first.startsWith("@")) return "@"

        return when (first) {
            "shorts" -> Constants.PAGE_SHORTS
            "watch" -> Constants.PAGE_WATCH
            // live/embed are watch pages too (VideoId.parse and the page hooks
            // treat them as such).
            "live" -> Constants.PAGE_WATCH
            "embed" -> Constants.PAGE_WATCH
            "channel" -> "channel"
            "gaming" -> "gaming"
            "select_site" -> "select_site"
            "results" -> "searching"
            "feed" -> if (segments.size > 1) {
                when (segments[1].lowercase(Locale.ROOT)) {
                    "subscriptions" -> Constants.PAGE_SUBSCRIPTIONS
                    "library" -> Constants.PAGE_LIBRARY
                    "history" -> "history"
                    "channels" -> "channels"
                    "playlists" -> "playlists"
                    else -> segments.joinToString("/")
                }
            } else {
                segments.joinToString("/")
            }
            else -> segments.joinToString("/")
        }
    }

    private fun isYoutubeHost(host: String): Boolean {
        val domain = Constants.YOUTUBE_DOMAIN
        return host == domain || host.endsWith(".$domain")
    }
}
