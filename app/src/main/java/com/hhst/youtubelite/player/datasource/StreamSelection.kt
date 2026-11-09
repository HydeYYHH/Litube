package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format

object StreamSelection {

    /** Nearest lower height to [preferredQuality], then smallest step up; null/invalid selects the highest. */
    fun selectVideo(formats: List<Format>, preferredQuality: String?): Format? {
        val best = filterBest(formats)
        return selectVideoQuality(best, preferredQuality, { it.height }, { it.fps })
            ?: best.firstOrNull() // Missing dimensions alone do not make a stream unplayable.
    }

    internal fun <T> selectVideoQuality(
        candidates: List<T>, preferredQuality: String?, height: (T) -> Int, fps: (T) -> Int,
    ): T? {
        val sorted = candidates.filter { height(it) > 0 }
            .sortedWith(compareByDescending<T>(height).thenByDescending(fps))
        val target = preferredQuality?.let(::parseHeight) ?: 0
        if (target <= 0) return sorted.firstOrNull()
        val chosenHeight = sorted.firstOrNull { height(it) <= target }?.let(height)
            ?: sorted.lastOrNull()?.let(height) ?: return null
        val atHeight = sorted.filter { height(it) == chosenHeight }
        val targetFps = preferredQuality?.let(::parseFps) ?: 0
        return atHeight.firstOrNull { targetFps > 0 && fps(it) == targetFps } ?: atHeight.firstOrNull()
    }

    fun audioCandidates(formats: List<Format>, preferredKey: String? = null): List<Format> {
        val matching = if (preferredKey.isNullOrBlank()) formats
        else AudioTrackIdentity.matchingFormats(formats, preferredKey)
        return matching.filter(AudioTrackIdentity::isOriginal).ifEmpty {
            matching.filter { it.audioTrackType.equals("default", ignoreCase = true) }.ifEmpty { matching }
        }
    }

    fun selectAudio(formats: List<Format>, preferredKey: String? = null): Format? =
        audioCandidates(formats, preferredKey).maxByOrNull { it.bitrate }

    fun filterBest(formats: List<Format>): List<Format> {
        if (formats.isEmpty()) return emptyList()
        val best = LinkedHashMap<String, Format>()
        for (f in formats) {
            val key = "${f.height}#${f.fps}"
            val prev = best[key]
            if (prev == null || isBetter(f, prev)) best[key] = f
        }
        return best.values.sortedWith(
            compareByDescending<Format> { it.height }.thenByDescending { it.fps },
        )
    }

    internal fun isBetter(a: Format, b: Format): Boolean {
        if (a.requestPlan == null && b.requestPlan == null) {
            val potA = hasPoToken(a)
            val potB = hasPoToken(b)
            if (potA != potB) return potA
        }
        val pa = codecPriority(a.codec)
        val pb = codecPriority(b.codec)
        if (pa != pb) return pa > pb
        if (a.fps != b.fps) return a.fps > b.fps
        return a.bitrate > b.bitrate
    }

    /**
     * Playback-stable pool: decodable first, poToken first, then anything except
     * ANDROID_VR (pot-less ANDROID_VR 403s mid-stream — last resort only). Empty
     * when nothing is hardware-decodable so the caller fails fast into re-extract /
     * client exclusion instead of handing ExoPlayer an undecodable rep.
     */
    fun preferPlayable(formats: List<Format>, allowAndroidVr: Boolean = false): List<Format> {
        val decodable = formats.filter(CodecCapabilities::isDecodable)
        if (decodable.any { it.requestPlan != null }) return decodable.filter { it.requestPlan != null }
        val withPot = decodable.filter(::hasPoToken)
        if (withPot.isNotEmpty()) return withPot
        val stable = decodable.filter { !isAndroidVr(it) }
        if (stable.isNotEmpty()) return stable
        if (allowAndroidVr && decodable.isNotEmpty()) return decodable
        return emptyList()
    }

    fun hasPoToken(format: Format): Boolean {
        val url = format.url
        return "?pot=" in url || "&pot=" in url
    }

    fun isAndroidVr(format: Format): Boolean = streamingClient(format.url) == "ANDROID_VR"

    fun isMuxed(format: Format): Boolean = !format.videoOnly && !format.audioOnly

    /**
     * True when the format's minting client serves adaptive URLs without the ~64 s
     * read window pot-less IOS/ANDROID/WEB URLs are throttled by. As of 2026-08
     * VISIONOS is the only such client; ANDROID carries only the window-exempt
     * muxed itag 18.
     */
    fun isWindowExempt(format: Format): Boolean =
        streamingClient(format.url) == "VISIONOS" || hasPoToken(format)

    fun streamingClient(url: String): String? {
        val amp = url.indexOf("&c=")
        val q = url.indexOf("?c=")
        val start = when {
            amp >= 0 && (q < 0 || amp < q) -> amp + 3
            q >= 0 -> q + 3
            else -> return null
        }
        val end = url.indexOf('&', start).let { if (it < 0) url.length else it }
        return url.substring(start, end).ifEmpty { null }
    }

    fun codecPriority(codec: String?): Int {
        val lower = codec?.lowercase() ?: return 0
        return when {
            lower.startsWith("avc") || lower.startsWith("h264") -> 4
            // VP9 arrives as "vp9" or RFC 6381 ("vp09.00.10.08" — no "vp9" substring).
            lower.startsWith("vp9") || lower.startsWith("vp09") || lower.contains("vp8") -> 3
            // HEVC arrives as h265/hevc or RFC 6381 ("hev1.1.6.L93").
            lower.contains("h265") || lower.contains("hevc") ||
                lower.startsWith("hvc1") || lower.startsWith("hev1") -> 2
            lower.contains("av01") || lower.contains("av1") -> 1
            else -> 0
        }
    }

    fun parseHeight(quality: String): Int {
        if (quality.equals("4K", ignoreCase = true)) return 2160
        val pIndex = quality.indexOf('p')
        if (pIndex > 0) {
            return quality.substring(0, pIndex).toIntOrNull() ?: 0
        }
        return quality.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    fun parseFps(quality: String): Int {
        val pIndex = quality.indexOf('p')
        if (pIndex < 0 || pIndex + 1 >= quality.length) return 0
        return quality.substring(pIndex + 1).toIntOrNull() ?: 0
    }
}
