@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.PlaybackDemand
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.datasource.PlaybackStartup
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.datasource.StreamSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Warms the shared playback cache with the head of the next short so a swipe
 * lands on buffered bytes instead of a cold fetch.
 *
 * Only real media is prefetched: init + index plus a small head of the audio
 * and video renditions the resolver would pick, read through the same
 * [PlayerDataSource] factories as playback so request plans, cache keys and
 * the shared SimpleCache all match what the player later reads. Both
 * transports are covered: DASH formats are sliced by their init/index tables,
 * and when only HLS remains the master playlist is read through the same
 * manifest source before the startup variant's media playlist and first
 * segment heads are warmed. Prefetch is not viewing: nothing here touches
 * history or watch reporting.
 */
class ShortsMediaPreloader(
    private val context: Context,
    private val extractor: Extractor,
    private val dataSources: PlayerDataSource,
    private val prefs: ExtensionManager? = null,
    private val demandFor: (String) -> PlaybackDemand? = { null },
) {
    /** Serializes prefetch network work; playback always outranks it. */
    private val networkPermit = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    @Volatile private var target: String? = null
    /** Videos this session's prefetch warmed, with the bytes written. */
    private val warmed = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_WARMED
    }

    /** Starts (or re-targets) the single background prefetch for [videoId]. */
    fun preload(videoId: String?) {
        val id = videoId?.let(VideoId::parse) ?: run { cancel(); return }
        synchronized(lock) {
            if (id == target && job?.isActive == true) return
            if (target != null && target != id) AppLog.detail(
                AppLog.Category.PLAYER, "shorts_prefetch_retarget",
                mapOf("video_id" to id, "superseded" to true))
            job?.cancel()
            target = id
            val demand = demandFor(id)
            job = scope.launch { run(id, demand) }
        }
    }

    /** Stops any in-flight prefetch; called on identity change, privacy switch and leaving Shorts. */
    fun cancel() {
        synchronized(lock) {
            if (job?.isActive == true) AppLog.detail(
                AppLog.Category.PLAYER, "shorts_prefetch_cancelled", emptyMap())
            job?.cancel()
            job = null
            target = null
            warmed.clear()
        }
    }

    /**
     * Bytes this session's prefetch wrote for [videoId]; 0 when it never
     * warmed. First-frame telemetry uses this to tell a prefetch hit from a
     * cold start.
     */
    fun prefetchedBytes(videoId: String): Long = synchronized(lock) { warmed[videoId] ?: 0L }

    private suspend fun run(videoId: String, demand: PlaybackDemand?) {
        val session = MediaSession.snapshot().generation
        if (MediaSession.privateBrowsing() || prefs?.isEnabled(PreferenceKeys.DATA_SAVER) == true) return
        coroutineContext.ensureActive()
        val preparation = extractor.prefetch(videoId, demand)
        try {
            val stream = preparation.extraction.stream.await()
            coroutineContext.ensureActive()
            // The resolved URLs outlive nothing: bail out when identity or
            // privacy moved while the read was in flight.
            if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session) return
            val requestContext = coroutineContext
            val checkActive = {
                requestContext.ensureActive()
                if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session ||
                    prefs?.isEnabled(PreferenceKeys.DATA_SAVER) == true) throw CancellationException("Prefetch stopped")
            }
            val formats = pickFormats(stream, demand)
            val transport = if (formats.isNotEmpty()) "dash" else "hls"
            val cached = if (formats.isNotEmpty()) cacheFormatHeads(formats, checkActive)
                else cacheHlsHead(videoId, stream, session, demand, checkActive)
            if (cached > 0) {
                synchronized(lock) { warmed[videoId] = cached }
                AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch",
                    mapOf("video_id" to videoId, "bytes" to cached, "formats" to formats.size, "transport" to transport))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Prefetch is best-effort; playback resolves its own media.
            AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch_skip",
                mapOf("video_id" to videoId, "reason" to "error", "kind" to error.javaClass.simpleName))
        } finally {
            preparation.cancel()
        }
    }

    private fun skip(videoId: String, reason: String, extra: Map<String, Any> = emptyMap()) {
        AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch_skip",
            mapOf("video_id" to videoId, "reason" to reason) + extra)
    }

    private suspend fun cacheFormatHeads(formats: List<Format>, checkActive: () -> Unit): Long {
        var remaining = TOTAL_BUDGET_BYTES
        var cached = 0L
        for (format in formats) {
            if (remaining <= 0) break
            val covered = networkPermit.withPermit {
                runInterruptible { head(format, remaining, checkActive) }
            }
            cached += covered
            remaining -= covered
        }
        return cached
    }

    /**
     * Warms the head of the next short over HLS: master playlist through the
     * manifest source (so the read lands in the same in-memory manifest cache
     * playback hits), then the startup variant's media playlist and the first
     * segment heads through the media source, whose spans key exactly as
     * playback's `hls:v1:` keys.
     */
    private suspend fun cacheHlsHead(videoId: String, stream: Stream, session: Long, demand: PlaybackDemand?, checkActive: () -> Unit): Long {
        val plan = stream.hlsRequestPlan ?: run { skip(videoId, "no_hls_plan"); return 0 }
        val masterUrl = stream.hlsUrl ?: run { skip(videoId, "no_hls_url"); return 0 }
        val parser = HlsPlaylistParser()
        val master = try {
            networkPermit.withPermit {
                runInterruptible { checkActive(); readPlaylist(dataSources.vodHls(plan).createDataSource(C.DATA_TYPE_MANIFEST), masterUrl) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            skip(videoId, "master_failed", mapOf("kind" to error.javaClass.simpleName)); return 0
        }
        coroutineContext.ensureActive()
        val multivariant = parser.parse(Uri.parse(masterUrl), master.inputStream()) as? HlsMultivariantPlaylist
            ?: run { skip(videoId, "master_unparsed"); return 0 }
        val maxHeight = demand?.height?.takeIf { it > 0 } ?: PlaybackStartup.MAX_START_HEIGHT
        val variant = pickHlsVariant(multivariant.variants, maxHeight)
            ?: run { skip(videoId, "no_variant", mapOf("variants" to multivariant.variants.size)); return 0 }
        val mediaUrl = variant.url.toString()
        val mediaSource = dataSources.vodHlsMedia(plan) ?: run { skip(videoId, "no_cache_source"); return 0 }
        try {
            val media = try {
                networkPermit.withPermit { runInterruptible { checkActive(); readPlaylist(mediaSource, mediaUrl) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                skip(videoId, "media_playlist_failed", mapOf("kind" to error.javaClass.simpleName)); return 0
            }
            coroutineContext.ensureActive()
            if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session) return 0
            val playlist = parser.parse(Uri.parse(mediaUrl), media.inputStream()) as? HlsMediaPlaylist
                ?: run { skip(videoId, "media_unparsed"); return 0 }
            var remaining = TOTAL_BUDGET_BYTES
            var cached = 0L
            val segments = playlist.segments.take(HLS_MAX_SEGMENTS)
            val init = segments.firstOrNull()?.initializationSegment
            for (segment in listOfNotNull(init) + segments) {
                if (remaining <= 0) break
                val covered = networkPermit.withPermit {
                    runInterruptible { segmentHead(mediaSource, playlist.baseUri, segment, remaining, checkActive) }
                }
                cached += covered
                remaining -= covered
            }
            if (cached <= 0) skip(videoId, "zero_bytes", mapOf("segments" to segments.size))
            return cached
        } finally {
            runCatching { mediaSource.close() }
        }
    }

    /**
     * Mirrors the resolver's adaptive pick: highest-bitrate original/default
     * audio plus the startup video rendition. Remote-DASH manifest streams
     * and plan-less formats are skipped — their requests cannot be replayed
     * against the byte cache reliably.
     */
    private fun pickFormats(stream: Stream, demand: PlaybackDemand?): List<Format> {
        val candidates = stream.formats.filter { it.requestPlan != null }
        if (candidates.none { it.hasDashRanges }) return emptyList()
        val videos = StreamSelection.preferPlayable(candidates.filter { it.videoOnly && it.hasDashRanges }, false)
        val audios = StreamSelection.preferPlayable(
            StreamSelection.audioCandidates(candidates.filter { it.audioOnly && it.hasDashRanges }), false)
        val height = demand?.height?.takeIf { it > 0 } ?: PlaybackStartup.MAX_START_HEIGHT
        val video = StreamSelection.selectVideo(StreamSelection.filterBest(videos), "${height}p")
        val audio = audios.maxByOrNull { it.bitrate }
        return listOfNotNull(audio, video).distinctBy { it.url }
    }

    /** Reads up to [budget] head bytes of [format] into the shared cache; returns bytes written. */
    private fun head(format: Format, budget: Long, checkActive: () -> Unit): Long {
        val plan = format.requestPlan ?: return 0
        val total = plan.resourceLength.takeIf { it > 0 }
            ?: com.hhst.youtubelite.downloader.net.DownloadBitrate.queryParam(format.url, "clen")?.toLongOrNull()
            ?: -1L
        val length = headBytes(format).coerceAtMost(budget).let { if (total > 0) minOf(it, total) else it }
        if (length <= 0) return 0
        val source = dataSources.formats(listOf(format), dash = true).createDataSource()
        if (source !is CacheDataSource) {
            runCatching { source.close() }
            return 0
        }
        return cacheHead(source, format.url, 0, length, checkActive)
    }

    /**
     * Reads up to [budget] head bytes of [segment] into the shared cache;
     * returns bytes written. Byterange segments are skipped unless the offset
     * is explicit: a span written at the wrong offset would serve playback
     * wrong bytes under the same cache key.
     */
    private fun segmentHead(source: CacheDataSource, baseUri: String, segment: HlsMediaPlaylist.Segment, budget: Long, checkActive: () -> Unit): Long {
        val ranged = segment.byteRangeLength >= 0
        if (ranged && segment.byteRangeOffset < 0) return 0
        val start = if (ranged) segment.byteRangeOffset else 0L
        val total = if (ranged) segment.byteRangeLength else -1L
        val length = budget.let { if (total > 0) minOf(it, total) else it }
        if (length <= 0) return 0
        return cacheHead(source, androidx.media3.common.util.UriUtil.resolve(baseUri, segment.url), start, length, checkActive)
    }

    /** Slices [length] head bytes from [position] into the cache; returns bytes written. */
    private fun cacheHead(source: CacheDataSource, url: String, position: Long, length: Long, checkActive: () -> Unit): Long {
        var written = 0L
        try {
            // CacheWriter runs blocking; slice the head so cancellation and
            // failure land between reads instead of mid-stream.
            while (written < length) {
                checkActive()
                val slice = minOf(length - written, PREFETCH_SLICE_BYTES)
                var cachedInSlice = 0L
                val spec = DataSpec.Builder()
                    .setUri(url)
                    .setPosition(position + written)
                    .setLength(slice)
                    .build()
                CacheWriter(source, spec, null) { _, bytesCached, _ ->
                    checkActive()
                    cachedInSlice = bytesCached
                }.cache()
                written += (if (cachedInSlice > 0) cachedInSlice else slice).coerceAtMost(slice)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Partially written spans stay in the cache and still help.
        }
        return written
    }

    /** Reads a playlist document (bounded); closes the source. */
    private fun readPlaylist(source: DataSource, url: String): ByteArray {
        try {
            source.open(DataSpec(Uri.parse(url)))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (out.size() < PLAYLIST_LIMIT_BYTES) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } finally {
            runCatching { source.close() }
        }
    }

    /** Init + index plus roughly [HEAD_SECONDS] of media, capped per format. */
    private fun headBytes(format: Format): Long {
        val head = (maxOf(format.initEnd, format.indexEnd) + 1).coerceAtLeast(0).toLong()
        val media = if (format.bitrate > 0) format.bitrate.toLong() / 8 * HEAD_SECONDS else DEFAULT_MEDIA_BYTES
        return (head + media).coerceAtMost(PER_FORMAT_BUDGET_BYTES)
    }

    companion object {
        /** Initial prefetch budget: about 2 seconds of media, ~4 MiB across formats. */
        const val HEAD_SECONDS = 2
        const val TOTAL_BUDGET_BYTES = 4L * 1024 * 1024
        const val PER_FORMAT_BUDGET_BYTES = 2L * 1024 * 1024
        const val PREFETCH_SLICE_BYTES = 1L * 1024 * 1024
        const val DEFAULT_MEDIA_BYTES = 256L * 1024
        const val PLAYLIST_LIMIT_BYTES = 512 * 1024
        const val HLS_MAX_SEGMENTS = 3
        const val MAX_WARMED = 8

        /**
         * Mirrors the resolver's startup pick over HLS variants: the largest
         * rendition at or under [maxHeight], tie-broken by bitrate.
         */
        internal fun pickHlsVariant(
            variants: List<HlsMultivariantPlaylist.Variant>,
            maxHeight: Int,
        ): HlsMultivariantPlaylist.Variant? {
            val withFormat = variants.filter { it.format != null }
            if (withFormat.isEmpty()) return variants.firstOrNull()
            val pool = withFormat.filter { it.format!!.height <= 0 || it.format!!.height <= maxHeight }
                .ifEmpty { withFormat }
            return pool.maxWithOrNull(compareBy({ it.format!!.height }, { it.format!!.bitrate }))
        }
    }
}
