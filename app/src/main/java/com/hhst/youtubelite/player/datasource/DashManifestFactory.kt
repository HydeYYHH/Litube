package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.core.Markup
import com.hhst.youtubelite.extractor.Format

/**
 * Builds synthetic DASH MPDs from [Format]s' byte-range info.
 *
 * Adaptive streams support range requests; wrapping them in a synthetic
 * DASH manifest lets ExoPlayer use its DASH pipeline (adaptive buffering, seek
 * via `SegmentBase` index) without fetching a remote manifest.
 *
 * Single format: one representation (audio, muxed). A video pool becomes one
 * manifest with a representation per format so quality switching is a track
 * selection, not a media-source rebuild.
 *
 * Format reference: NewPipe's progressive DASH manifest creator.
 */
object DashManifestFactory {

    /**
     * Builds a DASH MPD XML string for [format].
     *
     * @param durationMsFallback video duration in ms, used when [Format.approxDurationMs] is unknown.
     * @throws IllegalArgumentException when [format] lacks DASH ranges or duration is unknown.
     */
    fun build(format: Format, durationMsFallback: Long = -1L): String {
        require(format.hasDashRanges) {
            "Format lacks DASH ranges: init=${format.initStart}-${format.initEnd} " +
                "index=${format.indexStart}-${format.indexEnd}"
        }
        val durationMs = resolveDuration(listOf(format), durationMsFallback)
        return buildString {
            appendHeader(durationMs)
            append("<Period>")
            appendAdaptationSet(0, listOf(format), fallbackId = 0)
            append("</Period>")
            append("</MPD>")
        }
    }

    /**
     * Builds a multi-representation DASH MPD for a video pool, grouped into
     * one AdaptationSet per MIME type. Media3 adapts bitrate only within an
     * AdaptationSet, so mp4 and webm representations do not switch automatically.
     *
     * @throws IllegalArgumentException when [formats] is empty or lacks ranges/duration.
     */
    fun buildVideoPool(formats: List<Format>, durationMsFallback: Long = -1L): String {
        require(formats.isNotEmpty()) { "Empty video pool for DASH manifest" }
        formats.forEach { format ->
            require(format.hasDashRanges) {
                "Format lacks DASH ranges: init=${format.initStart}-${format.initEnd} " +
                    "index=${format.indexStart}-${format.indexEnd}"
            }
        }
        val durationMs = resolveDuration(formats, durationMsFallback)
        return buildString {
            appendHeader(durationMs)
            append("<Period>")
            var nextFallbackId = 0
            formats.groupBy { it.mimeType.ifBlank { guessMimeType(it) } }
                .entries.forEachIndexed { setId, (_, group) ->
                    appendAdaptationSet(setId, group, nextFallbackId)
                    nextFallbackId += group.size
                }
            append("</Period>")
            append("</MPD>")
        }
    }

    private fun resolveDuration(formats: List<Format>, durationMsFallback: Long): Long {
        val approx = formats.firstOrNull { it.approxDurationMs > 0 }?.approxDurationMs
        if (approx != null) return approx
        require(durationMsFallback > 0) { "No duration available for DASH manifest" }
        return durationMsFallback
    }

    private fun StringBuilder.appendHeader(durationMs: Long) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<MPD xmlns=\"urn:mpeg:DASH:schema:MPD:2011\"")
        append(" minBufferTime=\"PT1.500S\"")
        append(" profiles=\"urn:mpeg:dash:profile:full:2011\"")
        append(" type=\"static\"")
        append(" mediaPresentationDuration=\"PT${formatDuration(durationMs)}S\"")
        append(">")
    }

    private fun StringBuilder.appendAdaptationSet(setId: Int, group: List<Format>, fallbackId: Int) {
        val first = group.first()
        append("<AdaptationSet id=\"$setId\" mimeType=\"${
            Markup.xml(first.mimeType.ifBlank { guessMimeType(first) })
        }\" subsegmentAlignment=\"true\"")
        if (first.audioOnly && first.audioLocale != null) {
            append(" lang=\"${Markup.xml(first.audioLocale)}\"")
        }
        append(">")
        group.forEachIndexed { index, format -> appendRepresentation(format, fallbackId + index) }
        append("</AdaptationSet>")
    }

    private fun StringBuilder.appendRepresentation(format: Format, fallbackId: Int) {
        val codecs = Markup.xml(format.codec ?: "")
        val bandwidth = format.bitrate.takeIf { it > 0 } ?: fallbackBandwidth(format)
        // itag is normally present; fall back to a stable per-format index so
        // Representation ids stay unique when it is not.
        append("<Representation id=\"${format.itag ?: fallbackId}\" bandwidth=\"$bandwidth\"")
        if (codecs.isNotBlank()) append(" codecs=\"$codecs\"")
        if (format.width > 0) append(" width=\"${format.width}\"")
        if (format.height > 0) append(" height=\"${format.height}\"")
        if (format.fps > 0) append(" frameRate=\"${format.fps}\"")
        if (format.audioOnly && format.sampleRate > 0) {
            append(" audioSamplingRate=\"${format.sampleRate}\"")
        }
        append(">")
        if (format.audioOnly && format.audioChannels > 0) {
            append("<AudioChannelConfiguration" +
                " schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2012\"" +
                " value=\"${format.audioChannels}\"/>")
        }
        append("<BaseURL>${Markup.xml(format.url)}</BaseURL>")
        append("<SegmentBase indexRange=\"${format.indexStart}-${format.indexEnd}\">")
        append("<Initialization range=\"${format.initStart}-${format.initEnd}\"/>")
        append("</SegmentBase>")
        append("</Representation>")
    }

    /** Milliseconds → ISO 8601 duration seconds (e.g. "123.456"). */
    private fun formatDuration(ms: Long): String {
        val wholeSec = ms / 1000
        val frac = ms % 1000
        return if (frac == 0L) wholeSec.toString() else "$wholeSec.${frac.toString().padStart(3, '0')}"
    }

    private fun guessMimeType(format: Format): String = when {
        format.audioOnly -> when (format.container) {
            "M4A", "MP4" -> "audio/mp4"
            "WEBMA", "WEBM" -> "audio/webm"
            else -> "audio/mp4"
        }
        else -> when (format.container) {
            "MP4" -> "video/mp4"
            "WEBM" -> "video/webm"
            else -> "video/mp4"
        }
    }

    /**
     * Conservative per-height bandwidth floors for representations whose
     * bitrate the extractor could not report: a missing bandwidth breaks some
     * DASH parsers, and an order-of-magnitude floor is enough for media3 to
     * distinguish the representations it must not start with.
     */
    private fun fallbackBandwidth(format: Format): Int {
        if (format.audioOnly) return 128_000
        return when {
            format.height >= 2160 -> 15_000_000
            format.height >= 1440 -> 8_000_000
            format.height >= 1080 -> 4_000_000
            format.height >= 720 -> 2_000_000
            format.height >= 480 -> 1_000_000
            format.height >= 360 -> 600_000
            else -> 400_000
        }
    }
}
