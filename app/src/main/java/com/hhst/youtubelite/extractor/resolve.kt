package com.hhst.youtubelite.extractor

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ExtractorResolve"

/**
 * Shared `/player` payload for metadata and stream.
 *
 * Fetched at most once, only when a promise needs the network. The extractor
 * instance is shared with [resolveChapters] so chapters reuse the already
 * fetched player state instead of building a second extractor (which would
 * re-issue `/player` behind the scenes).
 */
internal class PlayerPage private constructor(
    val info: StreamInfo,
    val extractor: StreamExtractor,
) {
    companion object {
        fun shared(
            videoId: String,
            scope: CoroutineScope,
            clientOrder: ClientOrderStore? = null,
        ): () -> PlayerPage {
            val deferred = CompletableDeferred<PlayerPage>()
            val started = AtomicBoolean(false)
            return {
                if (started.compareAndSet(false, true)) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            deferred.complete(fetch(videoId, clientOrder))
                        } catch (t: Throwable) {
                            deferred.completeExceptionally(t)
                        }
                    }
                }
                runBlocking { deferred.await() }
            }
        }

        private fun fetch(videoId: String, clientOrder: ClientOrderStore?): PlayerPage {
            val extractor = ServiceList.YouTube.getStreamExtractor(VideoId.watchUrl(videoId))
            val info = StreamInfo.getInfo(extractor)
            clientOrder?.save()
            return PlayerPage(info, extractor)
        }
    }
}

/** Cache hit or `/player`, then persist [Metadata]. */
internal fun Promise<Metadata>.resolveMetadata(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getMetadata(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { fillFrom(player()) }
    cache.putMetadata(videoId, value)
}

/** Cache hit or `/player`, then persist [Stream]. */
internal fun Promise<Stream>.resolveStream(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getStream(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    set { fillFrom(player()) }
    cache.putStream(videoId, value)
}

/**
 * Cache hit or `/next`, then persist [ChapterList].
 *
 * Reuses [PlayerPage]'s extractor when the player page was already fetched —
 * `fetchNextResponse` then costs one `/next` POST and nothing else. When
 * chapters resolve first (WebView prefetch race), this blocks on the shared
 * player page instead of issuing a second `/player`; only if that fetch
 * *fails* does a private extractor run here, requesting just `/next`.
 */
internal fun Promise<ChapterList>.resolveChapters(
    videoId: String,
    cache: Cache,
    player: () -> PlayerPage,
) {
    cache.getChapters(videoId)?.let { cached ->
        set { copyFrom(cached) }
        return
    }
    val sharedPage: PlayerPage? = try {
        player()
    } catch (e: Exception) {
        Log.w(TAG, "shared /player fetch failed; using a private extractor for chapters", e)
        null
    }
    val extractor = sharedPage?.extractor
        ?: ServiceList.YouTube.getStreamExtractor(VideoId.watchUrl(videoId))
    val chapters = StreamInfo.getSegments(extractor).map {
        Chapter(
            title = it.title.orEmpty(),
            startSeconds = it.startTimeSeconds,
            previewUrl = it.previewUrl,
        )
    }
    set { this.chapters = chapters }
    cache.putChapters(videoId, value)
}

private fun Metadata.fillFrom(page: PlayerPage) {
    val info = page.info
    val extractor = page.extractor
    id = info.id
    title = info.name.orEmpty()
    author = runCatching { extractor.uploaderName }.getOrNull()
    description = runCatching { extractor.description.content }.getOrNull()
    duration = runCatching { extractor.length }.getOrDefault(0L).coerceAtLeast(0L)
    thumbnailUrl = bestImageUrl(runCatching { extractor.thumbnails }.getOrDefault(emptyList()))
        ?: VideoId.thumbnailUrl(info.id)
    likeCount = runCatching { extractor.likeCount }.getOrDefault(-1L)
    dislikeCount = runCatching { extractor.dislikeCount }.getOrDefault(-1L)
    uploadedAt = runCatching {
        extractor.uploadDate?.instant?.toEpochMilli()
    }.getOrNull()
    uploaderUrl = runCatching { extractor.uploaderUrl }.getOrNull()
    uploaderAvatarUrl = bestImageUrl(
        runCatching { extractor.uploaderAvatars }.getOrDefault(emptyList()),
    )
    viewCount = runCatching { extractor.viewCount }.getOrDefault(-1L)
    isLive = runCatching {
        info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM ||
            info.streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM
    }.getOrDefault(false)
}

private fun Stream.fillFrom(page: PlayerPage) {
    val info = page.info
    formats = buildList {
        info.videoOnlyStreams.forEach { add(it.toFormat(videoOnly = true)) }
        info.audioStreams.forEach { add(it.toFormat()) }
        info.videoStreams.forEach { add(it.toFormat()) }
    }
        .filter { it.url.isNotBlank() }
        .sortedWith(
            compareByDescending<Format> { it.height }
                .thenByDescending { it.bitrate },
        )
    subtitles = runCatching { page.extractor.subtitlesDefault }
        .getOrDefault(emptyList())
        .mapNotNull { track ->
            val url = track.content.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Subtitle(
                url = url,
                languageCode = track.languageTag.orEmpty(),
                autoGenerated = track.isAutoGenerated,
                mimeType = track.format?.mimeType.orEmpty(),
            )
        }
    dashUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() }
    hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() }
}

private fun VideoStream.toFormat(videoOnly: Boolean = false): Format {
    val item = runCatching { itagItem }.getOrNull()
    return Format(
        url = content,
        height = height,
        width = width,
        bitrate = bitrate,
        fps = fps,
        qualityLabel = getResolution(),
        codec = codec,
        container = format?.name,
        itag = itag.takeIf { it > 0 },
        videoOnly = videoOnly,
        mimeType = format?.mimeType.orEmpty(),
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd,
        approxDurationMs = item?.approxDurationMs ?: -1L,
    )
}

private fun AudioStream.toFormat(): Format {
    val item = runCatching { itagItem }.getOrNull()
    return Format(
        url = content,
        bitrate = if (averageBitrate > 0) averageBitrate else bitrate,
        codec = codec,
        container = format?.name,
        itag = itag.takeIf { it > 0 },
        audioOnly = true,
        mimeType = format?.mimeType.orEmpty(),
        initStart = initStart,
        initEnd = initEnd,
        indexStart = indexStart,
        indexEnd = indexEnd,
        approxDurationMs = item?.approxDurationMs ?: -1L,
        sampleRate = item?.sampleRate ?: -1,
        audioChannels = item?.audioChannels ?: -1,
        audioLocale = audioLocale?.toLanguageTag()?.takeIf { it.isNotBlank() },
        audioTrackId = audioTrackId?.takeIf { it.isNotBlank() },
        audioTrackName = audioTrackName?.takeIf { it.isNotBlank() },
        audioTrackType = audioTrackType?.name?.lowercase(),
        audioTrackOriginal = audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL,
    )
}

private fun bestImageUrl(images: List<Image>): String? {
    if (images.isEmpty()) return null
    val rank = mapOf(
        Image.ResolutionLevel.HIGH to 3,
        Image.ResolutionLevel.MEDIUM to 2,
        Image.ResolutionLevel.LOW to 1,
        Image.ResolutionLevel.UNKNOWN to 0,
    )
    return images.maxByOrNull { rank[it.estimatedResolutionLevel] ?: 0 }?.url
}
