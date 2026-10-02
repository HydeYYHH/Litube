package com.hhst.youtubelite.core

/** Shared constants for browser and network layers. */
object Constants {
    const val HOME_URL = "https://m.youtube.com"
    const val YOUTUBE_DOMAIN = "youtube.com"
    /**
     * The WebView engine's own User-Agent, captured at app start. A hardcoded
     * one contradicts the engine's real Client Hints and TLS fingerprint, and
     * googlevideo reads the mismatch as a low-trust client, cutting playback
     * sessions short.
     */
    @Volatile var genuineUserAgent: String = ""

    /** Last resort before [App.onCreate] has run; practically unused. */
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    fun userAgent(): String = genuineUserAgent.ifBlank { FALLBACK_USER_AGENT }

    const val PAGE_HOME = "home"
    const val PAGE_SHORTS = "shorts"
    const val PAGE_WATCH = "watch"
    const val PAGE_SUBSCRIPTIONS = "subscriptions"
    const val PAGE_LIBRARY = "library"
}
