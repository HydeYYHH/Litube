package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.Subtitle
import com.hhst.youtubelite.player.datasource.StreamSelection
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan

/** Extractor snapshot the download layer selects from. Playback models are unchanged. */
data class DownloadCatalog(
    val videoId: String,
    val title: String = "",
    val author: String? = null,
    val durationSec: Long = 0L,
    val isLive: Boolean = false,
    val thumbnailUrl: String? = null,
    val formats: List<Format> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
) {
    companion object {
        fun from(metadata: Metadata, stream: Stream) = DownloadCatalog(
            videoId = metadata.id,
            title = metadata.title,
            author = metadata.author,
            durationSec = metadata.duration,
            isLive = metadata.isLive,
            thumbnailUrl = metadata.thumbnailUrl,
            formats = stream.formats,
            subtitles = stream.subtitles,
        )
    }
}

enum class DownloadUnavailableReason {
    LIVE,
    PREMIERE_UNSTARTED,
    NO_FILE_STREAMS,
    QUALITY_UNAVAILABLE,
    AUDIO_TRACK_AMBIGUOUS,
    AUDIO_LANGUAGE_UNAVAILABLE,
    SUBTITLE_LANGUAGE_UNAVAILABLE,
    CODEC_NOT_ENABLED,
    NETWORK_ERROR,
    SESSION_CHANGED,
    EXTRACTION_FAILED,
}

sealed class DownloadSelection {
    data class Ready(val plan: DownloadPlan) : DownloadSelection()
    data class Failed(val reason: DownloadUnavailableReason, val message: String) : DownloadSelection()
}

/**
 * [subtitleFailure]/[coverFailure] keep an unavailable attachment from
 * blocking the media assets: video/audio still resolve, the missing sidecar
 * is reported failed on its own asset row.
 */
data class DownloadPlan(
    val video: DownloadMediaChoice? = null,
    val audio: DownloadMediaChoice? = null,
    val muxed: DownloadMediaChoice? = null,
    val subtitle: DownloadSidecarChoice? = null,
    val cover: DownloadSidecarChoice? = null,
    val subtitleFailure: DownloadUnavailableReason? = null,
    val coverFailure: DownloadUnavailableReason? = null,
)

data class DownloadMediaChoice(
    val format: Format,
    /** Trusted length (clen). Never a bitrate estimate. */
    val expectedBytes: Long?,
    val resourceIdentity: String,
    val audioTrackKey: String? = null,
    /** bitrate × duration estimate; display only. */
    val estimatedBytes: Long? = null,
)

data class DownloadSidecarChoice(
    val url: String,
    val mimeType: String,
    val extension: String,
    val language: String? = null,
    @Transient val requestPlan: RequestPlan? = null,
)

/** Shared parse vs independent URL-refresh. Cancel only the wait, never the parse. */
interface DownloadCatalogSource {
    suspend fun catalog(videoId: String): DownloadCatalog
    suspend fun refresh(videoId: String): DownloadCatalog
    fun scope(): String = ""
}

fun interface PoTokenEvictor {
    fun evict(videoId: String)
}

fun DownloadConfig.qualityCapHeight(): Int {
    val raw = videoQuality ?: return DownloadSelector.DEFAULT_MAX_HEIGHT
    val parsed = StreamSelection.parseHeight(raw)
    return if (parsed > 0) parsed else DownloadSelector.DEFAULT_MAX_HEIGHT
}
