package com.hhst.youtubelite.player.datasource

import android.net.Uri
import android.os.Bundle
import com.google.gson.Gson
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Subtitle
import androidx.core.net.toUri

/**
 * Converts the engine's candidates into a Media3 source using their frozen request plans.
 * Device codec support, quality and audio-track preferences drive format selection.
 * VOD can use synthetic DASH, progressive streams or remote HLS/DASH manifests.
 * Live manifest updates and all child requests inherit the same session and profile.
 * Sources built from the legacy Java adapter retain its compatibility selection rules.
 */
@UnstableApi
class MediaSourceResolver(
    private val dataSources: PlayerDataSource,
    /** Player bandwidth estimate for the HLS start-variant order; null keeps the manifest order. */
    private val startupBandwidth: (() -> Long)? = null,
    private val startupVideoSupport: ((MediaFormat) -> Int)? = null,
    private val diagnosticContext: DiagnosticContext? = null,
) {
    @Volatile private var startupParameters: (() -> TrackSelectionParameters)? = null

    fun configureStartupTrackSelection(parameters: () -> TrackSelectionParameters) {
        startupParameters = parameters
    }

    data class Resolved(
        val mediaSource: MediaSource,
        val videoFormat: Format?,
        val audioFormat: Format?,
        /**
         * Video formats carried by the (multi-representation) manifest. Non-empty
         * only for adaptive resolves; quality switching then happens through
         * track-selection constraints instead of a media-source rebuild.
         */
        val videoFormats: List<Format> = emptyList(),
    )

    /** MediaItem metadata so the session player exposes title/author/artwork to the system media card. */
    private fun sessionMetadata(m: Metadata): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(m.title)
            .setArtist(m.author)
            .setArtworkUri(m.thumbnailUrl?.toUri())
            .setExtras(diagnosticContext?.let { diagnostic -> Bundle().apply { putString("litube.diagnostic_context", Gson().toJson(diagnostic)) } })
            .build()

    /**
     * [excludeClient] is the 403 last resort: drop that client's URLs and
     * allow ANDROID_VR formats.
     */
    fun resolve(
        stream: Stream,
        metadata: Metadata,
        preferredQuality: String? = null,
        audioTrackKey: String? = null,
        forceMuxed: Boolean = false,
        subtitleKey: String? = null,
        excludeClient: String? = null,
        diagnosticContext: DiagnosticContext? = null,
    ): Resolved {
        if (diagnosticContext != null) {
            val scoped = MediaSourceResolver(dataSources.withDiagnostics(diagnosticContext), startupBandwidth, startupVideoSupport, diagnosticContext)
            startupParameters?.let(scoped::configureStartupTrackSelection)
            return scoped.resolve(stream, metadata, preferredQuality, audioTrackKey, forceMuxed, subtitleKey, excludeClient)
        }
        val meta = sessionMetadata(metadata)
        if (metadata.isLive) return resolveLive(stream, subtitleKey, meta)

        val subtitles = SubtitleSelection.select(stream.subtitles, subtitleKey)
        val durationMs = metadata.duration * 1000
        if (stream.formats.any { it.requestPlan != null } && !forceMuxed) {
            val requested = stream.formats.filter { excludeClient == null || it.requestPlan?.profile?.clientName != excludeClient }
            adaptive(requested, subtitles, durationMs, preferredQuality, audioTrackKey,
                requirePoToken = false, meta = meta)?.let { return it }
        }

        // Last-resort pool: without the rejected client the remainder may be
        // ANDROID_VR-only, so VR restrictions are lifted for this resolve.
        val allowVr = excludeClient != null
        val formats = if (excludeClient == null) stream.formats
        else stream.formats
            .filter { StreamSelection.streamingClient(it.url) != excludeClient }
            .ifEmpty { stream.formats }

        // 1. Adaptive DASH with a streaming poToken — the only full-quality
        //    path that is exempt from the ~64 s read window.
        if (!forceMuxed) {
            adaptive(
                formats, subtitles, durationMs, preferredQuality, audioTrackKey,
                requirePoToken = true, allowAndroidVr = allowVr,
                meta = meta,
            )?.let { return it }
        }

        // 2. Muxed progressive, only when forceMuxed is set (403 recovery).
        val muxed = if (forceMuxed) pickMuxed(formats, allowVr, preferredQuality) else null
        if (muxed != null) {
            return Resolved(
                withSubtitles(singleFormatSource(muxed, durationMs, meta), subtitles),
                muxed, null,
            )
        }

        // 3. Adaptive DASH from a client whose URLs carry no 64 s read window
        //    (VISIONOS direct URLs, or any pot-bearing adaptive format). This
        //    outranks both muxed (360p itag 18) and HLS: full quality, sidx
        //    chunking, and the whole pool stays switchable in-manifest.
        if (!forceMuxed) {
            adaptive(
                formats, subtitles, durationMs, preferredQuality, audioTrackKey,
                requirePoToken = false, allowAndroidVr = allowVr,
                requireWindowExempt = true,
                meta = meta,
            )?.let { return it }
        }

        // 4. HLS.
        stream.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            // Keep every named rendition. Stripping them down to one merged
            // file leaves the audio menu with only Default.
            val parsers = AudioCodecHlsPlaylistParserFactory()
            val select = startupVideoSupport?.let { support ->
                { formats: List<MediaFormat>, estimate: Long ->
                    val parameters = startupParameters?.invoke()
                        ?: TrackSelectionParameters.DEFAULT.buildUpon()
                            .apply {
                                preferredQuality?.let(StreamSelection::parseHeight)?.takeIf { it > 0 }?.let {
                                    setMinVideoSize(0, it); setMaxVideoSize(Int.MAX_VALUE, it)
                                }
                            }.build()
                    PlaybackStartup.selectFormat(formats, estimate, parameters, support)
                }
            }
            val factory = HlsMediaSource.Factory(dataSources.vodHls(stream.hlsRequestPlan))
                .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
                .setAllowChunklessPreparation(true)
                .setPlaylistParserFactory(startupBandwidth?.let { StartupHlsPlaylistParserFactory(parsers, it, preferredQuality, select) } ?: parsers)
            val video = factory.createMediaSource(
                MediaItem.fromUri(url).buildUpon().setMediaMetadata(meta).build(),
            )
            return Resolved(withSubtitles(video, subtitles), null, null)
        }

        // 5. Remote DASH manifest URL.
        stream.dashUrl?.takeIf { it.isNotBlank() }?.let {
            return Resolved(
                withSubtitles(dashSource(it, meta, stream.dashRequestPlan), subtitles),
                null, null,
            )
        }

        // 6. Muxed floor (360p) when no exempt adaptive pool exists. Reached
        //    only when forceMuxed was false but no adaptive pool at all was
        //    usable — i.e. WEB SABR-rolled and VISIONOS bot-checked.
        if (!forceMuxed) {
            val muxedFloor = pickMuxed(formats, allowVr, preferredQuality)
            if (muxedFloor != null) {
                return Resolved(
                    withSubtitles(singleFormatSource(muxedFloor, durationMs, meta), subtitles),
                    muxedFloor, null,
                )
            }
        }

        // 7. Adaptive DASH from window-bound clients without pot (64 s window
        //    applies).
        if (!forceMuxed) {
            adaptive(
                formats, subtitles, durationMs, preferredQuality, audioTrackKey,
                requirePoToken = false, allowAndroidVr = allowVr,
                meta = meta,
            )?.let { return it }
        }

        // 8. Audio-only. ANDROID_VR remains excluded unless allowVr is true.
        val audioOnly = StreamSelection.selectAudio(StreamSelection.preferPlayable(
            formats.filter { it.audioOnly },
            allowVr,
        ), audioTrackKey)
        if (audioOnly != null) {
            return Resolved(
                withSubtitles(singleFormatSource(audioOnly, durationMs, meta), subtitles),
                null, audioOnly,
            )
        }

        throw IllegalStateException("No playable stream formats")
    }

    private fun adaptive(
        formats: List<Format>,
        subtitles: List<Subtitle>,
        durationMs: Long,
        preferredQuality: String?,
        audioTrackKey: String?,
        requirePoToken: Boolean,
        allowAndroidVr: Boolean = false,
        requireWindowExempt: Boolean = false,
        meta: MediaMetadata,
    ): Resolved? {
        var videos = StreamSelection.preferPlayable(
            formats.filter { it.videoOnly && it.hasDashRanges },
            allowAndroidVr,
        )
        var audios = StreamSelection.preferPlayable(
            formats.filter { it.audioOnly && it.hasDashRanges },
            allowAndroidVr,
        )
        if (requirePoToken) {
            videos = videos.filter(StreamSelection::hasPoToken)
            audios = audios.filter(StreamSelection::hasPoToken)
        }
        if (requireWindowExempt) {
            // Only pools fully readable beyond the ~64 s window qualify; a
            // mixed pool would stall mid-playback. Video and audio can come
            // from different clients — both must be exempt.
            videos = videos.filter(StreamSelection::isWindowExempt)
            audios = audios.filter(StreamSelection::isWindowExempt)
        }
        if (videos.isEmpty() || audios.isEmpty()) return null
        // Whole pool (deduped per height#fps) goes into one multi-representation
        // manifest so later quality picks are track selections, not rebuilds.
        val videoPool = StreamSelection.filterBest(videos)
        val audio = StreamSelection.selectAudio(audios, audioTrackKey) ?: return null
        // selectVideo never returns null for a non-empty pool (falls back to best).
        val video = StreamSelection.selectVideo(videoPool, preferredQuality) ?: return null
        return Resolved(
            mediaSource = mergedAdaptive(videoPool, audio, subtitles, durationMs, meta),
            videoFormat = video,
            audioFormat = audio,
            videoFormats = videoPool,
        )
    }

    // -- live --

    private fun resolveLive(stream: Stream, subtitleKey: String?, meta: MediaMetadata): Resolved {
        val liveItem = { url: String ->
            MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                        .build(),
                )
                .setMediaMetadata(meta)
                .build()
        }
        val subtitles = SubtitleSelection.select(stream.subtitles, subtitleKey)
        stream.dashUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val factory = DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(dataSources.manifest(stream.dashRequestPlan)),
                dataSources.manifest(stream.dashRequestPlan),
            ).setManifestParser(YoutubeDashLiveManifestParser())
                .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
            return Resolved(
                withSubtitles(factory.createMediaSource(liveItem(url)), subtitles),
                null, null,
            )
        }
        stream.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val factory = HlsMediaSource.Factory(dataSources.manifest(stream.hlsRequestPlan))
                .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
                .setAllowChunklessPreparation(true)
            return Resolved(
                withSubtitles(factory.createMediaSource(liveItem(url)), subtitles),
                null, null,
            )
        }
        // Some "live" entries only have progressive formats; reuse VOD
        // playable filtering so AV1 is not picked on devices without decode.
        // Muxed only: an adaptive highest-height pick would be videoOnly and
        // play silent video through ProgressiveMediaSource. The source is
        // UNCACHED: live bytes must never land in the VOD LRU.
        val best = StreamSelection.preferPlayable(
            stream.formats.filter(StreamSelection::isMuxed),
        ).maxByOrNull { it.height }
            ?: throw IllegalStateException("No playable live stream")
        return Resolved(
            withSubtitles(progressive(best.url, best.mimeType.ifBlank { null }, meta, dataSources.ytLiveProgressive), subtitles),
            best, null,
        )
    }

    // -- construction --

    private fun mergedAdaptive(
        videos: List<Format>,
        audio: Format,
        subtitles: List<Subtitle>,
        durationMs: Long,
        meta: MediaMetadata,
    ): MediaSource = withSubtitles(
        MergingMediaSource(
            true,
            syntheticDash(DashManifestFactory.buildVideoPool(videos, durationMs), meta, videos),
            syntheticDash(DashManifestFactory.build(audio, durationMs), meta, listOf(audio)),
        ),
        subtitles,
    )

    /**
     * Wrap any itag with a sidx index as DASH (`range`+`rn` query params).
     * ProgressiveMediaSource uses the HTTP Range header, which googlevideo often 403s.
     */
    private fun singleFormatSource(format: Format, durationMs: Long, meta: MediaMetadata): MediaSource =
        if (format.hasDashRanges) {
            syntheticDash(DashManifestFactory.build(format, durationMs), meta, listOf(format))
        } else {
            progressive(format.url, format.mimeType.ifBlank { null }, meta, dataSources.formats(listOfNotNull(format), dash = false))
        }

    private fun pickMuxed(
        formats: List<Format>,
        allowVr: Boolean,
        preferredQuality: String?,
    ): Format? = StreamSelection.selectVideo(
        StreamSelection.preferPlayable(
            formats.filter(StreamSelection::isMuxed),
            allowVr,
        ),
        preferredQuality,
    )

    private fun withSubtitles(base: MediaSource, subtitles: List<Subtitle>): MediaSource {
        val subs = subtitleSources(subtitles)
        if (subs.isEmpty()) return base
        return MergingMediaSource(true, *(listOf(base) + subs).toTypedArray())
    }

    /** Whether [trackKey] resolves to a text track the player can actually parse. */
    fun hasSubtitleTrack(subtitles: List<Subtitle>, trackKey: String?): Boolean =
        subtitleSources(SubtitleSelection.select(subtitles, trackKey)).isNotEmpty()

    /** Synthetic DASH manifest served from a data: URI; chunks via cached YT factory. */
    private fun syntheticDash(mpd: String, meta: MediaMetadata, formats: List<Format>): MediaSource {
        val item = MediaItem.fromUri(
            ("data:application/dash+xml," + Uri.encode(mpd)).toUri(),
        ).buildUpon().setMediaMetadata(meta).build()
        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(dataSources.formats(formats)),
            DataSource.Factory { DataSchemeDataSource() },
        ).setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy()).createMediaSource(item)
    }

    private fun dashSource(url: String, meta: MediaMetadata, plan: RequestPlan?): MediaSource =
        DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(dataSources.manifest(plan)),
            dataSources.manifest(plan),
        ).setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
            .createMediaSource(MediaItem.fromUri(url).buildUpon().setMediaMetadata(meta).build())

    private fun progressive(
        url: String,
        mimeType: String? = null,
        meta: MediaMetadata,
        factory: DataSource.Factory,
    ): MediaSource {
        val builder = MediaItem.fromUri(url).buildUpon()
        if (mimeType != null) builder.setMimeType(mimeType)
        builder.setMediaMetadata(meta)
        return ProgressiveMediaSource.Factory(factory)
            .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
            .setContinueLoadingCheckIntervalBytes(
                PlayerDataSource.PROGRESSIVE_LOAD_INTERVAL_BYTES,
            )
            .createMediaSource(builder.build())
    }

    /**
     * Side-loaded subtitles parsed during extraction into `application/x-media3-cues`.
     * [ProgressiveMediaSource] plus [SubtitleExtractor] is the Media3 1.4+ path.
     * `SingleSampleMediaSource` would emit raw TTML/VTT samples that
     * `TextRenderer` no longer decodes.
     */
    private fun subtitleSources(subtitles: List<Subtitle>): List<MediaSource> {
        val parserFactory = DefaultSubtitleParserFactory()
        return subtitles.mapNotNull { sub ->
            val mime = when {
                sub.mimeType.contains("ttml", ignoreCase = true) -> MimeTypes.APPLICATION_TTML
                sub.mimeType.contains("srt", ignoreCase = true) ||
                    sub.mimeType.contains("subrip", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            }
            val format = MediaFormat.Builder()
                .setId("sub:${sub.languageCode}:${sub.autoGenerated}")
                .setSampleMimeType(mime)
                .setLanguage(sub.languageCode)
                .setLabel(sub.languageCode + if (sub.autoGenerated) " (auto)" else "")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                .build()
            if (!parserFactory.supportsFormat(format)) return@mapNotNull null
            val extractorsFactory = ExtractorsFactory {
                arrayOf(SubtitleExtractor(parserFactory.create(format), format))
            }
            ProgressiveMediaSource.Factory(dataSources.manifest(sub.requestPlan), extractorsFactory)
                .createMediaSource(MediaItem.fromUri(sub.url))
        }
    }

    private companion object {
        const val LIVE_TARGET_OFFSET_MS = 20_000L
    }
}
