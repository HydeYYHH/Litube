package com.hhst.youtubelite.extractor

import java.net.URI

/** Transport family of a planned media request. */
enum class MediaProtocol { HTTPS, DASH, HLS }

/** How a byte window is expressed on the wire. */
enum class MediaRange { HEADER, QUERY, NONE }

/**
 * Playback-capable clients, their Innertube identity and request posture.
 * Names match the protocol values used by the player endpoints.
 */
enum class StreamClient(
    val clientName: String,
    val clientId: Int,
    val version: String,
    val fixedUserAgent: String?,
    val supportsCookies: Boolean,
    val requiresGvs: Boolean,
) {
    VISIONOS(
        "VISIONOS", 101, "1.02",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        false, false,
    ),
    WEB("WEB", 1, "2.20260708.00.00", null, true, true),
    WEB_EMBED(
        "WEB_EMBEDDED_PLAYER", 56, "2.20260708.00.00",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.5 Safari/605.1.15",
        true, false,
    ),
    WEB_SAFARI(
        "WEB", 1, "2.20260708.00.00",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/15.5 Safari/15.5",
        true, true,
    ),
    TV(
        "TVHTML5", 7, "5.20260707",
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        true, false,
    ),
    /** Labels reel-floor muxed URLs minted under the ANDROID identity; never issues /player requests itself. */
    ANDROID_VR(
        "ANDROID", 3, "21.03.36",
        "com.google.android.youtube/21.03.36 (Linux; U; Android 16; US) gzip",
        false, false,
    ),
    ;
    /** Client-fixed UA, else the capturing session's user agent. */
    fun userAgent(session: YoutubeWebSession): String = fixedUserAgent ?: session.userAgent

    fun version(session: YoutubeWebSession): String =
        if ((this == WEB || this == WEB_SAFARI) && session.webClientVersion != null) session.webClientVersion
        else version

    val webTokens: Boolean get() = clientName.startsWith("WEB")

    companion object {
        fun byName(name: String): StreamClient? = entries.firstOrNull {
            it.name == name || it.clientName == name
        }
    }
}

/** Non-serializable host media request context. API authorization is never media authorization. */
class MediaRequestPlan(
    val session: YoutubeWebSession,
    val client: StreamClient,
    val protocol: MediaProtocol,
    val range: MediaRange,
    val allowPostFallback: Boolean = false,
    val resourceLength: Long = -1,
    val expiresAtMillis: Long = Long.MAX_VALUE,
) {
    val userAgent: String = client.userAgent(session)
    val headers: Map<String, String> = mapOf(
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
    )
    val chunkLimit: Long = 10L * 1024 * 1024

    fun cookies(url: String): String {
        val uri = URI.create(url)
        val host = uri.host
        // Never disclose account cookies to arbitrary manifests or googlevideo.
        if (client.supportsCookies && "https" == uri.scheme && host != null &&
            (host == "youtube.com" || host.endsWith(".youtube.com"))
        ) {
            return session.cookies("https://$host")
        }
        return ""
    }
}

/** Selection needs affect when resolution stops, never which formats are retained. */
data class PlaybackDemand(
    val height: Int,
    val audioTrack: String?,
    val audioOnly: Boolean,
    val videoCodecPrefixes: Set<String>,
) {
    fun cacheKey(): String = "$height:$audioTrack:$audioOnly:" + videoCodecPrefixes.sorted().joinToString(",")
}
