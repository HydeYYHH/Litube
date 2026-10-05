package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * YouTube master playlists declare both muxed audio codecs and an
 * `#EXT-X-MEDIA` audio rendition. Media3 then drops the muxed audio and plays
 * only that rendition, so a failed rendition playlist is silent. When the
 * extractor already has the selected audio, the video playlist is reduced to
 * video and that audio is merged beside it.
 */
@UnstableApi
internal fun withoutHlsAudio(playlist: HlsPlaylist): HlsPlaylist {
    val multi = playlist as? HlsMultivariantPlaylist ?: return playlist
    val variants = multi.variants.map { variant ->
        val videoCodecs = Util.getCodecsOfType(variant.format.codecs, C.TRACK_TYPE_VIDEO)
        val format = variant.format.buildUpon()
            .setCodecs(videoCodecs)
            .setSampleMimeType(MimeTypes.getMediaMimeType(videoCodecs))
            .build()
        HlsMultivariantPlaylist.Variant(
            variant.url,
            format,
            variant.videoGroupId,
            null,
            variant.subtitleGroupId,
            variant.captionGroupId,
            variant.pathwayId,
            variant.stableVariantId,
        )
    }
    return HlsMultivariantPlaylist(
        multi.baseUri,
        multi.tags,
        variants,
        multi.videos,
        emptyList(),
        multi.subtitles,
        multi.closedCaptions,
        null,
        multi.muxedCaptionFormats,
        multi.hasIndependentSegments,
        multi.variableDefinitions,
        multi.sessionKeyDrmInitData,
    )
}

/**
 * YouTube audio renditions name each language but omit `CODECS`, channel count,
 * and sample rate. Media3 then either skips chunkless preparation or marks the
 * track unsupported, so the menu never sees the other languages. Fill those in
 * from the variant so every name is a supported track.
 */
@UnstableApi
internal fun declareAudioCodecs(playlist: HlsPlaylist): HlsPlaylist {
    val multi = playlist as? HlsMultivariantPlaylist ?: return playlist
    if (multi.audios.isEmpty()) return playlist
    val codecByGroup = HashMap<String, String>()
    var fallback: String? = null
    for (variant in multi.variants) {
        val codec = singleAudioCodec(variant.format.codecs) ?: continue
        if (fallback == null) fallback = codec
        val group = variant.audioGroupId ?: continue
        codecByGroup.putIfAbsent(group, codec)
    }
    val fallbackCodec = fallback ?: return playlist
    var changed = false
    val audios = multi.audios.map { rendition ->
        val format = rendition.format
        val codec = singleAudioCodec(format.codecs)
            ?: codecByGroup[rendition.groupId]
            ?: fallbackCodec
        val needsCodec = singleAudioCodec(format.codecs) == null
        val needsLayout = format.channelCount == Format.NO_VALUE || format.sampleRate == Format.NO_VALUE
        if (!needsCodec && !needsLayout) {
            rendition
        } else {
            changed = true
            val builder = format.buildUpon()
            if (needsCodec) {
                builder.setCodecs(codec).setSampleMimeType(MimeTypes.getMediaMimeType(codec))
            }
            if (format.channelCount == Format.NO_VALUE) builder.setChannelCount(2)
            if (format.sampleRate == Format.NO_VALUE) builder.setSampleRate(48_000)
            HlsMultivariantPlaylist.Rendition(
                rendition.url,
                builder.build(),
                rendition.groupId,
                rendition.name,
                null,
            )
        }
    }
    if (!changed) return playlist
    return HlsMultivariantPlaylist(
        multi.baseUri,
        multi.tags,
        multi.variants,
        multi.videos,
        audios,
        multi.subtitles,
        multi.closedCaptions,
        multi.muxedAudioFormat,
        multi.muxedCaptionFormats,
        multi.hasIndependentSegments,
        multi.variableDefinitions,
        multi.sessionKeyDrmInitData,
    )
}

/** One audio codec, or null when the string is missing or mixed. */
@UnstableApi
private fun singleAudioCodec(codecs: String?): String? {
    if (codecs.isNullOrBlank()) return null
    val audio = Util.getCodecsOfType(codecs, C.TRACK_TYPE_AUDIO) ?: return null
    return audio.takeIf { Util.getCodecCountOfType(it, C.TRACK_TYPE_AUDIO) == 1 }
}

@UnstableApi
internal class AudioCodecHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        stamp(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        stamp(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun stamp(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser { uri: Uri, inputStream: InputStream ->
            declareAudioCodecs(parser.parse(uri, inputStream))
        }
}

@UnstableApi
internal class VideoOnlyHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        strip(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        strip(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun strip(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser { uri: Uri, inputStream: InputStream ->
            withoutHlsAudio(parser.parse(uri, inputStream))
        }
}
