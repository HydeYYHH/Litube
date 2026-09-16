package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format

/** Pure stream-selection logic. */
object StreamSelection {

    /**
     * Picks a video format matching [preferredQuality] (e.g. "1080p", "720p60");
     * falls back to the highest available height when no match or null.
     *
     * Input is deduped per (height, fps) keeping the best candidate
     * (poToken first, then codec avc > vp9 > h265 > av01, then bitrate), then
     * sorted by height desc, fps desc.
     */
    fun selectVideo(formats: List<Format>, preferredQuality: String?): Format? {
        val best = filterBest(formats)
        if (best.isEmpty()) return null
        if (preferredQuality != null) {
            val targetHeight = parseHeight(preferredQuality)
            val targetFps = parseFps(preferredQuality)
            if (targetHeight > 0) {
                // Exact resolution+fps match first.
                best.firstOrNull { it.height == targetHeight && (targetFps == 0 || it.fps == targetFps) }
                    ?.let { return it }
                best.firstOrNull { it.height == targetHeight }?.let { return it }
                // Then highest not exceeding target.
                best.firstOrNull { it.height <= targetHeight }?.let { return it }
                // All above the target: take the smallest step up, not the
                // pool's top — YouTube never jumps a "360p" pick to 1080p.
                best.lastOrNull { it.height > targetHeight }?.let { return it }
            }
        }
        return best.first()
    }

    /**
     * Picks the best audio format. [preferredKey] is a track identity
     * (`id:` / `loc:`) or a legacy language code; it wins even when an
     * original track exists (otherwise the audio menu cannot select a dub).
     * Without a preference, original then bitrate.
     */
    fun selectAudio(formats: List<Format>, preferredKey: String? = null): Format? {
        if (formats.isEmpty()) return null
        if (preferredKey != null) {
            formats.firstOrNull { AudioTrackIdentity.matches(it, preferredKey) }
                ?.let { return it }
        }
        val original = formats.filter { it.audioTrackOriginal }
        return (original.ifEmpty { formats }).maxByOrNull { it.bitrate }
    }

    /**
     * Dedupes per `height#fps`, preferring poToken, then codec
     * (avc > vp9 > h265 > av01), then fps and bitrate; sorted by height desc,
     * then fps desc.
     */
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
        val potA = hasPoToken(a)
        val potB = hasPoToken(b)
        if (potA != potB) return potA
        val pa = codecPriority(a.codec)
        val pb = codecPriority(b.codec)
        if (pa != pb) return pa > pb
        if (a.fps != b.fps) return a.fps > b.fps
        return a.bitrate > b.bitrate
    }

    /**
     * Playback-stable pool: decodable formats first, poToken first, then
     * anything except ANDROID_VR. ANDROID_VR without pot 403s mid-stream;
     * keep it only as an explicit last resort. Nothing hardware-decodable
     * means nothing playable: return empty so the caller fails fast into its
     * re-extract / client-exclusion recovery instead of handing ExoPlayer a
     * rep it cannot decode (which would burn another recovery round).
     */
    fun preferPlayable(formats: List<Format>, allowAndroidVr: Boolean = false): List<Format> {
        val decodable = formats.filter(CodecCapabilities::isDecodable)
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

    /** Muxed (video+audio in one progressive itag); the muxed fallback's pool predicate. */
    fun isMuxed(format: Format): Boolean = !format.videoOnly && !format.audioOnly

    /**
     * True when the format's minting client serves adaptive URLs without the
     * ~64 s read window that pot-less IOS/ANDROID/WEB URLs are throttled by.
     * As of 2026-08 VISIONOS is the only such client left (see the extractor
     * fork's client notes); ANDROID carries only the window-exempt muxed itag
     * 18, not adaptive formats.
     */
    fun isWindowExempt(format: Format): Boolean =
        streamingClient(format.url) == "VISIONOS" || hasPoToken(format)

    /** `c` query param from a googlevideo URL, or null. */
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

    /** avc/h264=4 > vp9/vp8=3 > h265=2 > av01=1. */
    fun codecPriority(codec: String?): Int {
        val lower = codec?.lowercase() ?: return 0
        return when {
            lower.startsWith("avc") || lower.startsWith("h264") -> 4
            // VP9 arrives as "vp9" or in RFC 6381 form ("vp09.00.10.08" —
            // no "vp9" substring), so both spellings must rank 3.
            lower.startsWith("vp9") || lower.startsWith("vp09") || lower.contains("vp8") -> 3
            // HEVC arrives as h265/hevc or in RFC 6381 form ("hev1.1.6.L93").
            lower.contains("h265") || lower.contains("hevc") ||
                lower.startsWith("hvc1") || lower.startsWith("hev1") -> 2
            lower.contains("av01") || lower.contains("av1") -> 1
            else -> 0
        }
    }

    /** "1080p" → 1080, "720p60" → 720, "4K" → 2160, else 0. */
    fun parseHeight(quality: String): Int {
        if (quality.equals("4K", ignoreCase = true)) return 2160
        val pIndex = quality.indexOf('p')
        if (pIndex > 0) {
            return quality.substring(0, pIndex).toIntOrNull() ?: 0
        }
        return quality.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    /** "720p60" → 60, "1080p" → 0. */
    fun parseFps(quality: String): Int {
        val pIndex = quality.indexOf('p')
        if (pIndex < 0 || pIndex + 1 >= quality.length) return 0
        return quality.substring(pIndex + 1).toIntOrNull() ?: 0
    }
}
