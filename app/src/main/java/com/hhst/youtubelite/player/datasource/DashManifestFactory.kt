package com.hhst.youtubelite.player.datasource

import android.net.Uri
import android.util.Base64
import com.hhst.youtubelite.core.Markup
import com.hhst.youtubelite.extractor.Format

/**
 * Builds synthetic DASH MPDs from [Format]s' byte-range info: wrapping adaptive
 * range streams in DASH lets ExoPlayer use its DASH pipeline (adaptive buffering,
 * `SegmentBase` index seek) without a remote manifest. A video pool becomes one
 * manifest with a representation per format so quality switching is a track
 * selection, not a media-source rebuild.
 */
object DashManifestFactory {

    /** DataSchemeDataSource URL-decodes plain data URIs after Android has decoded them.
     * Base64 preserves the signed media URL's own percent escapes and plus signs. */
    internal fun dataUri(mpd: String): Uri = Uri.parse(
        "data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
    )

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
     * Groups one AdaptationSet per MIME type: Media3 adapts bitrate only within
     * an AdaptationSet, so mp4 and webm representations must not share one.
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
            formats.groupBy(::containerMimeType)
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
            Markup.xml(containerMimeType(first))
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
        // Fall back to a stable per-format index so Representation ids stay
        // unique when the itag is absent.
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

    // Per-rendition codec params in mimeType must stay off the AdaptationSet:
    // keeping them there splits each profile/level into a fixed single track.
    // Codecs already belong on each Representation; group by container MIME alone.
    private fun containerMimeType(format: Format): String =
        format.mimeType.substringBefore(';').trim().ifBlank { guessMimeType(format) }

    /**
     * Conservative per-height bandwidth floors when the extractor could not
     * report a bitrate: a missing bandwidth breaks some DASH parsers, and an
     * order-of-magnitude floor is enough for media3 to distinguish the
     * representations it must not start with.
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
