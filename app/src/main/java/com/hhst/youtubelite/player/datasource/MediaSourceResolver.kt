package com.hhst.youtubelite.player.datasource

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticJson
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.MediaRequestPlan
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Subtitle
import androidx.core.net.toUri
import java.io.IOException
import android.os.SystemClock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers

/**
 * Converts the engine's candidates into a Media3 source using their frozen request plans.
 * Live manifest updates and all child requests inherit the same session and profile;
 * sources from the legacy Java adapter retain its compatibility selection rules.
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
    @Volatile private var subtitleLoadError: ((DiagnosticContext?, IOException) -> Unit)? = null

    fun configureSubtitleLoadError(listener: (DiagnosticContext?, IOException) -> Unit) {
        subtitleLoadError = listener
    }

    fun configureStartupTrackSelection(parameters: () -> TrackSelectionParameters) {
        startupParameters = parameters
    }

    /** The exact issued master only: no variant choice, credentials or quality changes. */
    internal suspend fun prepareHlsFallback(stream: Stream, checkCurrent: () -> Unit): Long = runInterruptible(Dispatchers.IO) {
        val url = stream.hlsUrl ?: return@runInterruptible 0L
        val plan = stream.hlsRequestPlan ?: return@runInterruptible 0L
        val source = dataSources.vodHls(plan).createDataSource(C.DATA_TYPE_MANIFEST)
        try {
            checkCurrent()
            source.open(DataSpec.Builder().setUri(url)
                .setCustomData(PrefetchDeadline(SystemClock.elapsedRealtime() + 2_000)).build())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                checkCurrent()
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                total += count
                if (total > VodManifestCache.MAX_DOCUMENT_BYTES) throw IOException("HLS_MANIFEST_TOO_LARGE")
            }
            total
        } finally { source.close() }
    }

    data class Resolved(
        val mediaSource: MediaSource,
        val videoFormat: Format?,
        val audioFormat: Format?,
        /**
         * Non-empty only for adaptive resolves; quality switching then happens
         * through track-selection constraints instead of a media-source rebuild.
         */
        val videoFormats: List<Format> = emptyList(),
    )

    private fun sessionMetadata(m: Metadata): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(m.title)
            .setArtist(m.author)
            .setArtworkUri(m.thumbnailUrl?.toUri())
            .setExtras(diagnosticContext?.let { diagnostic -> Bundle().apply { putString("litube.diagnostic_context", DiagnosticJson.encodeContext(diagnostic)) } })
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
        onSlowInitialRead: (() -> Unit)? = null,
    ): Resolved {
        if (diagnosticContext != null || onSlowInitialRead != null) {
            var sources = diagnosticContext?.let(dataSources::withDiagnostics) ?: dataSources
            onSlowInitialRead?.let { sources = sources.withSlowInitialRead(it) }
            val scoped = MediaSourceResolver(sources, startupBandwidth, startupVideoSupport, diagnosticContext)
            startupParameters?.let(scoped::configureStartupTrackSelection)
            subtitleLoadError?.let(scoped::configureSubtitleLoadError)
            return scoped.resolve(stream, metadata, preferredQuality, audioTrackKey, forceMuxed, subtitleKey, excludeClient)
        }
        val meta = sessionMetadata(metadata)
        if (metadata.isLive) return resolveLive(stream, subtitleKey, meta)

        val subtitles = SubtitleSelection.select(stream.subtitles, subtitleKey)
        val durationMs = metadata.duration * 1000
        if (stream.formats.any { it.requestPlan != null } && !forceMuxed) {
            val requested = stream.formats.filter { excludeClient == null || it.requestPlan?.client?.clientName != excludeClient }
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
        //    path exempt from the ~64 s read window.
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
        //    (VISIONOS direct URLs, or pot-bearing adaptive formats); outranks
        //    muxed and HLS: full quality, sidx chunking, switchable in-manifest.
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
            // Keep every named rendition; one merged file leaves the audio
            // menu with only Default.
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

        // 6. Muxed floor (360p) when no exempt adaptive pool exists — i.e.
        //    WEB SABR-rolled and VISIONOS bot-checked.
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
        val audioOnly = StreamSelection.preferPlayable(
            StreamSelection.audioCandidates(formats.filter { it.audioOnly }, audioTrackKey),
            allowVr,
        ).maxByOrNull { it.bitrate }
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
            StreamSelection.audioCandidates(formats.filter { it.audioOnly && it.hasDashRanges }, audioTrackKey),
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
        val audio = audios.maxByOrNull { it.bitrate } ?: return null
        // Never null for a non-empty pool (falls back to best).
        val video = StreamSelection.selectVideo(videoPool, preferredQuality) ?: return null
        return Resolved(
            mediaSource = mergedAdaptive(videoPool, audio, subtitles, durationMs, meta),
            videoFormat = video,
            audioFormat = audio,
            videoFormats = videoPool,
        )
    }

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
        // play silent video. UNCACHED: live bytes must never land in the VOD LRU.
        val best = StreamSelection.preferPlayable(
            stream.formats.filter(StreamSelection::isMuxed),
        ).maxByOrNull { it.height }
            ?: throw IllegalStateException("LIVE_STREAM_SOURCE_UNAVAILABLE")
        return Resolved(
            withSubtitles(progressive(best.url, best.mimeType.ifBlank { null }, meta, dataSources.ytLiveProgressive), subtitles),
            best, null,
        )
    }

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
     * Wrap any itag with a sidx index as DASH (`range`+`rn` query params);
     * ProgressiveMediaSource's HTTP Range header often 403s on googlevideo.
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

    fun hasSubtitleTrack(subtitles: List<Subtitle>, trackKey: String?): Boolean =
        subtitleSources(SubtitleSelection.select(subtitles, trackKey)).isNotEmpty()

    /** Synthetic DASH manifest served from a data: URI; chunks via cached YT factory. */
    private fun syntheticDash(mpd: String, meta: MediaMetadata, formats: List<Format>): MediaSource {
        val item = MediaItem.fromUri(DashManifestFactory.dataUri(mpd))
            .buildUpon().setMediaMetadata(meta).build()
        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(dataSources.formats(formats)),
            DataSource.Factory { DataSchemeDataSource() },
        ).setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy()).createMediaSource(item)
    }

    private fun dashSource(url: String, meta: MediaMetadata, plan: MediaRequestPlan?): MediaSource =
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
     * Side-loaded subtitles parsed into `application/x-media3-cues`.
     * [ProgressiveMediaSource] plus [SubtitleExtractor] is the Media3 1.4+ path;
     * `SingleSampleMediaSource` would emit raw samples `TextRenderer` no longer decodes.
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
            androidx.media3.exoplayer.source.LazySubtitleSourceFactory.create(
                dataSources.manifest(sub.requestPlan), format, sub.url.toUri()).also { source ->
                subtitleLoadError?.let { listener ->
                    source.addEventListener(Handler(Looper.getMainLooper()), object : MediaSourceEventListener {
                        override fun onLoadError(windowIndex: Int, mediaPeriodId: MediaSource.MediaPeriodId?, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                            // wasCanceled: the error ended the load without
                            // retry — the terminal failure the caption owner needs.
                            listener(diagnosticContext, error)
                        }
                    })
                }
            }
        }
    }

    private companion object {
        const val LIVE_TARGET_OFFSET_MS = 20_000L
    }
}
