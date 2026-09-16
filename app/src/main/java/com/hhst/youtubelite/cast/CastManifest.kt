@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.cast

import androidx.media3.extractor.ChunkIndex
import com.hhst.youtubelite.core.Markup
import com.hhst.youtubelite.extractor.Format

/**
 * DASH MPD for Chromecast / dash.js.
 *
 * Each Representation points at the local proxy `/stream/<token>` endpoint.
 * When a sidx [ChunkIndex] is present, emits a `SegmentList` with explicit
 * `?seg=START-END` URLs — the Chromecast default receiver cannot parse sidx
 * itself, so per-segment URLs are what make seek and segment addressing work.
 * Otherwise falls back to `BaseURL + SegmentBase + indexRange` and lets the
 * receiver parse sidx itself.
 */
object CastManifest {

    /** ~4 h at 5 s per segment; longer videos fall back to SegmentBase. */
    private const val MAX_SEGMENT_LIST_ENTRIES = 3000

    fun usableSegmentIndex(index: ChunkIndex?): Boolean =
        index != null && index.length in 1..MAX_SEGMENT_LIST_ENTRIES

    fun build(
        video: Format?,
        audio: Format?,
        durationMs: Long,
        proxyBase: String,
        videoIndex: ChunkIndex? = null,
        audioIndex: ChunkIndex? = null,
        generation: Long = 0L,
    ): String? {
        val videoXml = video?.let { adaptation(it, "v", proxyBase, videoIndex, generation) }
        val audioXml = audio?.let { adaptation(it, "a", proxyBase, audioIndex, generation) }
        if (videoXml == null && audioXml == null) return null
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:MPD:2011\"")
            append(" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\"")
            append(" type=\"static\" minBufferTime=\"PT1.500S\"")
            append(" mediaPresentationDuration=\"${formatDuration(durationMs / 1000)}\">")
            append("<Period>")
            if (videoXml != null) append(videoXml)
            if (audioXml != null) append(audioXml)
            append("</Period></MPD>")
        }
    }

    private fun adaptation(
        format: Format,
        token: String,
        proxyBase: String,
        index: ChunkIndex?,
        generation: Long,
    ): String? {
        // Unusable without byte ranges: no init segment and no sidx → the
        // receiver cannot locate any media.
        if (!format.hasDashRanges) return null
        // Container mime drives decoder choice; a mismatched container
        // (webm/opus served as audio/mp4) is MEDIA_ERR_DECODE on receivers.
        val mime = Markup.xml(format.mimeType.ifBlank {
            if (format.audioOnly) "audio/mp4" else "video/mp4"
        })
        val contentType = if (format.audioOnly) "audio" else "video"
        val codecs = Markup.xml(format.codec.orEmpty())
        val bandwidth = format.bitrate.takeIf { it > 0 } ?: 1
        val rawStreamUrl = streamUrl(proxyBase, token, generation)
        val streamUrl = Markup.xml(rawStreamUrl)
        return buildString {
            append("<AdaptationSet id=\"${if (format.audioOnly) 1 else 0}\"")
            append(" mimeType=\"$mime\" contentType=\"$contentType\"")
            append(" subsegmentAlignment=\"true\">")
            append("<Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>")
            append("<Representation id=\"${format.itag ?: token}\"")
            append(" codecs=\"$codecs\" startWithSAP=\"1\" maxPlayoutRate=\"1\"")
            append(" bandwidth=\"$bandwidth\"")
            if (format.width > 0) append(" width=\"${format.width}\"")
            if (format.height > 0) append(" height=\"${format.height}\"")
            if (format.fps > 0) append(" frameRate=\"${format.fps}\"")
            append(">")
            if (format.audioOnly) {
                append("<AudioChannelConfiguration")
                append(" schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2012\"")
                append(" value=\"${if (format.audioChannels > 0) format.audioChannels else 2}\"/>")
            }
            if (index != null && usableSegmentIndex(index)) {
                // SegmentTimeline with run-length repetition keeps the entry
                // count low for uniform segment durations. The FIRST <S> group
                // carries the first segment's presentation time explicitly —
                // a missing t continues from the previous entry, so writing it
                // on any later group would break the timeline. Some transcodes
                // start the media timeline at a non-zero tfdt, and an implied
                // t=0 desyncs receiver seek/period mapping. Zero stays omitted
                // (the common VOD case) so existing manifests are unchanged.
                val eptUs = if (index.length > 0) index.timesUs[0] else 0L
                val firstTUs = if (eptUs != 0L) eptUs else null
                append("<SegmentList timescale=\"1000000\"")
                if (eptUs != 0L) append(" presentationTimeOffset=\"$eptUs\"")
                append(">")
                append("<Initialization sourceURL=\"$streamUrl\" range=\"${format.initStart}-${format.initEnd}\"/>")
                append("<SegmentTimeline>")
                var prevDuration = -1L
                var repeat = 0
                var firstEmitted = false
                for (i in 0 until index.length) {
                    val duration = index.durationsUs[i]
                    if (duration == prevDuration) {
                        repeat++
                    } else {
                        if (prevDuration != -1L) {
                            appendSegmentDuration(prevDuration, repeat, tUs = if (firstEmitted) null else firstTUs)
                            firstEmitted = true
                        }
                        prevDuration = duration
                        repeat = 0
                    }
                }
                if (prevDuration != -1L) {
                    appendSegmentDuration(prevDuration, repeat, tUs = if (firstEmitted) null else firstTUs)
                }
                append("</SegmentTimeline>")
                for (i in 0 until index.length) {
                    val start = index.offsets[i]
                    val end = start + index.sizes[i] - 1
                    append(
                        "<SegmentURL media=\"${Markup.xml(
                            "$rawStreamUrl${queryJoin(rawStreamUrl)}seg=$start-$end",
                        )}\"/>",
                    )
                }
                append("</SegmentList>")
            } else {
                append("<BaseURL>$streamUrl</BaseURL>")
                append("<SegmentBase indexRange=\"${format.indexStart}-${format.indexEnd}\"")
                append(">")
                append("<Initialization range=\"${format.initStart}-${format.initEnd}\"/>")
                append("</SegmentBase>")
            }
            append("</Representation></AdaptationSet>")
        }
    }

    internal fun streamUrl(proxyBase: String, token: String, generation: Long): String {
        val base = "$proxyBase/stream/$token"
        return if (generation > 0L) "$base?g=$generation" else base
    }

    private fun queryJoin(url: String): String = if ('?' in url) "&" else "?"

    private fun StringBuilder.appendSegmentDuration(durationUs: Long, repeat: Int, tUs: Long? = null) {
        append("<S")
        if (tUs != null) append(" t=\"$tUs\"")
        append(" d=\"$durationUs\"")
        if (repeat > 0) append(" r=\"$repeat\"")
        append("/>")
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "PT${h}H${m}M${s}S"
    }
}
