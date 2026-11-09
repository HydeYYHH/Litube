@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.net.Uri
import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * First-chunk policy shared by HLS variant order and track selection. Both must name the same
 * variant: Media3 loads the first listed variant's playlist before selecting tracks, so a
 * different initial pick costs one more serial playlist read.
 */
internal object PlaybackStartup {
    private const val FRACTION = AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION

    fun videoSupport(context: Context): (Format) -> Int {
        val app = context.applicationContext
        return { MediaCodecVideoRenderer.supportsFormat(app, MediaCodecSelector.DEFAULT, it) }
    }

    /** Ask the same selector and decoder capabilities used by playback, before loading a child. */
    fun selectFormat(
        formats: List<Format>, estimate: Long, parameters: TrackSelectionParameters,
        support: (Format) -> Int,
    ): Format? {
        val video = formats.filter { it.height > 0 }
        if (video.isEmpty()) return null
        val group = TrackGroup("startup", *video.map { format ->
            val codecs = Util.getCodecsOfType(format.codecs, C.TRACK_TYPE_VIDEO)
            format.buildUpon().setCodecs(codecs)
                .setSampleMimeType(MimeTypes.getMediaMimeType(codecs)).build()
        }.toTypedArray())
        val capabilities = object : RendererCapabilities {
            override fun getName() = "startup-video"
            override fun getTrackType() = C.TRACK_TYPE_VIDEO
            override fun supportsFormat(format: Format) = support(format)
            override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
        }
        val meter = object : BandwidthMeter {
            override fun getBitrateEstimate() = estimate
            override fun getTransferListener(): TransferListener? = null
            override fun addEventListener(handler: Handler, listener: BandwidthMeter.EventListener) = Unit
            override fun removeEventListener(listener: BandwidthMeter.EventListener) = Unit
        }
        // Standalone selection with explicit parameters: the context-free selector
        // constructor is the supported path; a null context is never dereferenced
        // unless parameters fall back to context-derived defaults.
        @Suppress("DEPRECATION")
        val selector = DefaultTrackSelector(parameters, AdaptiveTrackSelection.Factory())
        selector.init(TrackSelector.InvalidationListener { }, meter)
        try {
            val result = selector.selectTracks(arrayOf(capabilities), TrackGroupArray(group),
                MediaSource.MediaPeriodId("startup", 0L), Timeline.EMPTY)
            val selection = result.selections.firstOrNull() ?: return null
            selection.enable()
            try {
                selection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), emptyArray<MediaChunkIterator>())
                return video[selection.selectedIndexInTrackGroup]
            } finally { selection.disable() }
        } finally { selector.release() }
    }

    /**
     * Fallback without a decoder-capability selector: highest bitrate the estimate affords,
     * else cheapest, within the most common video MIME type. Production HLS uses [selectFormat].
     */
    fun startFormat(formats: List<Format>, estimate: Long): Format? {
        val video = formats.filter { it.bitrate > 0 && it.height > 0 }
            .groupBy { MimeTypes.getMediaMimeType(Util.getCodecsOfType(it.codecs, C.TRACK_TYPE_VIDEO)) }
            .values.maxByOrNull { it.size } ?: return null
        val allocated = (estimate * FRACTION).toLong()
        return video.filter { it.bitrate <= allocated }.maxByOrNull { it.bitrate }
            ?: video.minByOrNull { it.bitrate }
    }
}

/** Puts the predicted start variant first, so its playlist is the one Media3 reads for the timeline. */
internal fun startVariantFirst(
    playlist: HlsPlaylist, estimate: Long, preferredQuality: String? = null,
    select: ((List<Format>, Long) -> Format?)? = null,
): HlsPlaylist {
    val multi = playlist as? HlsMultivariantPlaylist ?: return playlist
    val formats = multi.variants.map { it.format }
    val height = preferredQuality?.let(StreamSelection::parseHeight) ?: 0
    val fps = preferredQuality?.let(StreamSelection::parseFps) ?: 0
    val start = select?.invoke(formats, estimate) ?: if (height > 0) formats.filter { it.height == height }
        .let { matches -> matches.firstOrNull { fps <= 0 || it.frameRate.toInt() == fps } ?: matches.firstOrNull() }
        ?: formats.filter { it.height in 1..height }.maxByOrNull { it.height }
        ?: formats.minByOrNull { it.height }
    else PlaybackStartup.startFormat(formats, estimate)
    if (start == null) return playlist
    val index = multi.variants.indexOfFirst { it.format === start }
    if (index <= 0) return playlist
    return HlsMultivariantPlaylist(
        multi.baseUri,
        multi.tags,
        listOf(multi.variants[index]) + multi.variants.filterIndexed { i, _ -> i != index },
        multi.videos,
        multi.audios,
        multi.subtitles,
        multi.closedCaptions,
        multi.muxedAudioFormat,
        multi.muxedCaptionFormats,
        multi.hasIndependentSegments,
        multi.variableDefinitions,
        multi.sessionKeyDrmInitData,
    )
}

internal class StartupHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory,
    private val bandwidth: () -> Long,
    private val preferredQuality: String? = null,
    private val select: ((List<Format>, Long) -> Format?)? = null,
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = delegate.createPlaylistParser()
        return ParsingLoadable.Parser { uri: Uri, input: InputStream -> startVariantFirst(parser.parse(uri, input), bandwidth(), preferredQuality, select) }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}
