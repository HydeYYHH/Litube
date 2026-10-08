package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.downloader.net.DownloadBitrate

import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.net.DownloadResourceIdentity
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.datasource.AudioTrackIdentity
import com.hhst.youtubelite.player.datasource.StreamSelection
import com.hhst.youtubelite.player.datasource.SubtitleSelection

/**
 * Download stream picks. Playback [StreamSelection] still chooses unbounded
 * highest quality; this layer caps default height at 1080p and refuses gated
 * mux combos and silent language fallbacks.
 */
object DownloadSelector {
    const val DEFAULT_MAX_HEIGHT = 1080

    fun select(catalog: DownloadCatalog, config: DownloadConfig): DownloadSelection {
        liveOrPremiere(catalog)?.let { return it }
        if (config.attachmentsOnly) return selectAttachments(catalog, config)
        val files = catalog.formats.filter(::isFileStream)
        if (files.isEmpty()) {
            return fail(
                DownloadUnavailableReason.NO_FILE_STREAMS,
                "no downloadable file streams",
            )
        }

        val subtitle = subtitleOutcome(catalog, config)

        val audioPick = when (val audioResult = selectAudio(files, config, catalog.videoId)) {
            is DownloadSelection.Failed -> return audioResult
            is DownloadSelection.Ready -> audioResult.plan.audio
        }

        if (config.audioOnly) {
            val chosen = audioPick ?: return codecUnavailable()
            if (!DownloadCodecs.comboEnabled(null, chosen.format, audioOnly = true) &&
                !DownloadCodecs.comboEnabled(chosen.format, null, audioOnly = false)
            ) {
                return codecUnavailable()
            }
            return DownloadSelection.Ready(
                DownloadPlan(
                    audio = chosen,
                    subtitle = subtitle.choice,
                    subtitleFailure = subtitle.failure,
                    cover = coverChoice(catalog, config),
                    coverFailure = coverFailure(catalog, config),
                ),
            )
        }

        val videoPick = when (val videoResult = selectVideo(files, config, catalog.videoId)) {
            is DownloadSelection.Failed -> return videoResult
            is DownloadSelection.Ready -> videoResult
        }
        val video = videoPick.plan.video
        val muxed = videoPick.plan.muxed
        val audio = audioPick

        val comboOk = when {
            video != null && audio != null ->
                DownloadCodecs.comboEnabled(video.format, audio.format, audioOnly = false)
            muxed != null ->
                DownloadCodecs.comboEnabled(muxed.format, null, audioOnly = false)
            else -> false
        }
        if (!comboOk) return codecUnavailable()

        return DownloadSelection.Ready(
            DownloadPlan(
                video = video,
                audio = audio.takeIf { muxed == null },
                muxed = muxed,
                subtitle = subtitle.choice,
                subtitleFailure = subtitle.failure,
                cover = coverChoice(catalog, config),
                coverFailure = coverFailure(catalog, config),
            ),
        )
    }

    private fun selectAttachments(catalog: DownloadCatalog, config: DownloadConfig): DownloadSelection {
        if (!config.includeSubtitle && !config.includeCover) {
            return fail(
                DownloadUnavailableReason.NO_FILE_STREAMS,
                "attachments-only download has no subtitle or cover",
            )
        }
        val subtitle = subtitleOutcome(catalog, config)
        val cover = coverChoice(catalog, config)
        if (subtitle.choice == null && cover == null) {
            return fail(
                subtitle.failure ?: coverFailure(catalog, config) ?: DownloadUnavailableReason.NO_FILE_STREAMS,
                "attachments-only download has no subtitle or cover",
            )
        }
        return DownloadSelection.Ready(
            DownloadPlan(
                subtitle = subtitle.choice,
                subtitleFailure = subtitle.failure,
                cover = cover,
                coverFailure = coverFailure(catalog, config),
            ),
        )
    }

    private fun liveOrPremiere(catalog: DownloadCatalog): DownloadSelection.Failed? {
        if (!catalog.isLive) return null
        return if (catalog.durationSec > 0L) {
            fail(DownloadUnavailableReason.PREMIERE_UNSTARTED, "unstarted premiere")
        } else {
            fail(DownloadUnavailableReason.LIVE, "live stream is not downloadable")
        }
    }

    internal fun isFileStream(format: Format): Boolean {
        val url = format.url
        if (url.isBlank()) return false
        return "/videoplayback" in url || format.itag != null
    }

    private fun selectVideo(files: List<Format>, config: DownloadConfig, videoId: String): DownloadSelection {
        val hint = config.videoItagHint
        if (hint != null && hint > 0) {
            // An explicit confirm-sheet itag is an EXACT request: when it is
            // gone the user must reselect, not silently drop a quality tier.
            val hinted = files.firstOrNull { it.itag == hint }
            if (hinted != null && DownloadCodecs.videoEnabled(hinted)) {
                return if (hinted.videoOnly) {
                    DownloadSelection.Ready(DownloadPlan(video = mediaChoice(videoId, hinted)))
                } else {
                    DownloadSelection.Ready(DownloadPlan(muxed = mediaChoice(videoId, hinted)))
                }
            }
            return fail(
                DownloadUnavailableReason.QUALITY_UNAVAILABLE,
                "selected quality is unavailable; choose again",
            )
        }
        val cap = config.qualityCapHeight()
        val adaptive = files.filter { it.videoOnly && DownloadCodecs.videoEnabled(it) }
        val chosen = pickCappedVideo(adaptive, cap, config.videoQuality)
        if (chosen != null) {
            return DownloadSelection.Ready(DownloadPlan(video = mediaChoice(videoId, chosen)))
        }
        val muxedEnabled = files.filter {
            StreamSelection.isMuxed(it) && DownloadCodecs.videoEnabled(it)
        }
        val muxed = pickCappedVideo(muxedEnabled, cap, config.videoQuality)
        if (muxed != null) {
            return DownloadSelection.Ready(DownloadPlan(muxed = mediaChoice(videoId, muxed)))
        }
        val gatedExists = files.any { it.videoOnly || StreamSelection.isMuxed(it) }
        return if (gatedExists) codecUnavailable() else fail(
            DownloadUnavailableReason.NO_FILE_STREAMS,
            "no downloadable file streams",
        )
    }

    /**
     * Highest enabled height ≤ cap. Same height prefers AVC via [StreamSelection.filterBest].
     * Explicit [qualityLabel] is a cap so retries keep the stored quality string.
     */
    private fun pickCappedVideo(formats: List<Format>, capHeight: Int, qualityLabel: String?): Format? {
        val best = StreamSelection.filterBest(formats).filter { it.height <= capHeight }
        if (best.isEmpty()) return null
        val fps = qualityLabel?.let { StreamSelection.parseFps(it) } ?: 0
        if (fps > 0) {
            best.firstOrNull { it.height == capHeight && it.fps == fps }?.let { return it }
        }
        return best.first()
    }

    private fun selectAudio(
        files: List<Format>,
        config: DownloadConfig,
        videoId: String,
    ): DownloadSelection {
        val pool = files.filter { it.audioOnly }.ifEmpty { files.filter(StreamSelection::isMuxed) }
        val candidates = pool.filter { format ->
            if (format.audioOnly) DownloadCodecs.audioEnabled(format)
            else DownloadCodecs.comboEnabled(format, null, audioOnly = false)
        }.ifEmpty { pool }

        val itagHint = config.audioItagHint
        if (itagHint != null && itagHint > 0) {
            val hinted = candidates.firstOrNull { it.itag == itagHint &&
                (config.audioTrack.isNullOrBlank() || AudioTrackIdentity.matches(it, config.audioTrack)) }
            val usable = hinted != null && (
                !hinted.audioOnly || DownloadCodecs.audioEnabled(hinted)
            )
            if (hinted != null && usable) {
                return DownloadSelection.Ready(DownloadPlan(audio = mediaChoice(videoId, hinted)))
            }
        }

        val preferred = config.audioTrack
        if (!preferred.isNullOrBlank()) {
            val match = candidates.filter { AudioTrackIdentity.matches(it, preferred) }.maxByOrNull { it.bitrate }
                ?: return fail(
                    DownloadUnavailableReason.AUDIO_LANGUAGE_UNAVAILABLE,
                    "audio track $preferred is unavailable",
                )
            if (match.audioOnly && !DownloadCodecs.audioEnabled(match)) return codecUnavailable()
            return DownloadSelection.Ready(DownloadPlan(audio = mediaChoice(videoId, match)))
        }

        // Group by track identity. Streams WITHOUT track metadata share one
        // "unknown" bucket — bitrate variants of a single track must never
        // read as separate dubs (that dead-ends the sheet with no chips).
        val tracks = candidates.groupBy { AudioTrackIdentity.key(it) }
        fun original(group: List<Format>) = group.first().let {
            it.audioTrackOriginal || it.audioTrackType.equals("original", ignoreCase = true)
        }
        fun flaggedDefault(group: List<Format>) =
            group.first().audioTrackType.equals("default", ignoreCase = true)

        val originals = tracks.values.filter(::original)
        if (originals.size > 1) return ambiguousAudio()
        val defaults = tracks.values.filter(::flaggedDefault)
        if (defaults.size > 1) return ambiguousAudio()

        val chosenGroup = when {
            originals.size == 1 -> originals.single()
            defaults.size == 1 -> defaults.single()
            tracks.size == 1 -> tracks.values.single()
            else -> return ambiguousAudio()
        }
        // One track, several renditions: take the highest bitrate.
        return DownloadSelection.Ready(
            DownloadPlan(audio = mediaChoice(videoId, chosenGroup.maxBy { it.bitrate })),
        )
    }

    /** Subtitle selection never aborts the media plan; failures ride along. */
    private class SubtitleOutcome(
        val choice: DownloadSidecarChoice?,
        val failure: DownloadUnavailableReason?,
    )

    private fun subtitleOutcome(catalog: DownloadCatalog, config: DownloadConfig): SubtitleOutcome {
        if (!config.includeSubtitle) return SubtitleOutcome(null, null)
        val language = config.subtitleLanguage
        if (language.isNullOrBlank()) {
            return SubtitleOutcome(
                null,
                DownloadUnavailableReason.SUBTITLE_LANGUAGE_UNAVAILABLE,
            )
        }
        val picked = SubtitleSelection.select(catalog.subtitles, language).firstOrNull { sub ->
            !language.contains('|') || SubtitleSelection.key(sub.languageCode, sub.autoGenerated) == language
        }
            ?: return SubtitleOutcome(
                null,
                DownloadUnavailableReason.SUBTITLE_LANGUAGE_UNAVAILABLE,
            )
        return SubtitleOutcome(
            DownloadSidecarChoice(
                url = picked.url,
                mimeType = picked.mimeType.ifBlank { "text/vtt" },
                extension = subtitleExtension(picked.mimeType),
                language = picked.languageCode,
                requestPlan = picked.requestPlan,
            ),
            null,
        )
    }

    private fun coverFailure(catalog: DownloadCatalog, config: DownloadConfig): DownloadUnavailableReason? {
        if (!config.includeCover) return null
        return if (catalog.thumbnailUrl.isNullOrBlank()) {
            DownloadUnavailableReason.NO_FILE_STREAMS
        } else {
            null
        }
    }

    private fun coverChoice(catalog: DownloadCatalog, config: DownloadConfig): DownloadSidecarChoice? {
        if (!config.includeCover) return null
        val url = catalog.thumbnailUrl ?: return null
        val extension = when {
            url.contains(".webp", ignoreCase = true) -> "webp"
            url.contains(".png", ignoreCase = true) -> "png"
            else -> "jpg"
        }
        val mime = when (extension) {
            "webp" -> "image/webp"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        return DownloadSidecarChoice(url = url, mimeType = mime, extension = extension)
    }

    internal fun subtitleExtension(mime: String): String {
        val lower = mime.lowercase()
        return when {
            "vtt" in lower -> "vtt"
            "ttml" in lower || "xml" in lower -> "ttml"
            "srv3" in lower || "3gpp" in lower -> "srv3"
            "srt" in lower -> "srt"
            else -> "vtt"
        }
    }

    internal fun mediaChoice(videoId: String, format: Format): DownloadMediaChoice {
        val durationSec = if (format.approxDurationMs > 0) format.approxDurationMs / 1000L else 0L
        return DownloadMediaChoice(
            format = format,
            expectedBytes = format.requestPlan?.resourceLength?.takeIf { it > 0 }
                ?: DownloadBitrate.contentLengthFromUrl(format.url),
            estimatedBytes = DownloadBitrate.expectedBytes(format, durationSec),
            resourceIdentity = DownloadResourceIdentity.of(videoId, format),
            audioTrackKey = AudioTrackIdentity.key(format).takeIf { it.isNotBlank() },
        )
    }

    private fun codecUnavailable() = fail(
        DownloadUnavailableReason.CODEC_NOT_ENABLED,
        "selected codec is not on the download whitelist",
    )

    private fun ambiguousAudio() = fail(
        DownloadUnavailableReason.AUDIO_TRACK_AMBIGUOUS,
        "audio track is ambiguous; choose original or a specific track",
    )

    private fun fail(reason: DownloadUnavailableReason, message: String) =
        DownloadSelection.Failed(reason, message)
}
