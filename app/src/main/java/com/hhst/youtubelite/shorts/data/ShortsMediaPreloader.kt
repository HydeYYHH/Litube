@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Format as MediaFormat
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
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
import com.hhst.youtubelite.player.datasource.PrefetchDeadline
import com.hhst.youtubelite.player.datasource.StreamSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Warms the shared playback cache with the head of the next short so a swipe
 * lands on buffered bytes instead of a cold fetch. Reads go through the same
 * [PlayerDataSource] factories as playback so request plans and cache keys match;
 * prefetch is not viewing — nothing here touches history or watch reporting.
 */
class ShortsMediaPreloader(
    private val context: Context,
    private val extractor: Extractor,
    private val dataSources: PlayerDataSource,
    private val prefs: ExtensionManager? = null,
    private val demandFor: (String) -> PlaybackDemand? = { null },
) : ShortsPreloader {
    /** One audio head and one video head may overlap; retargets share this bound. */
    private val networkPermit = Semaphore(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    @Volatile private var target: String? = null
    private val warmed = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_WARMED
    }

    override fun preload(videoId: String?) {
        val id = videoId?.let(VideoId::parse) ?: run { cancel(); return }
        synchronized(lock) {
            if (id == target && (job?.isActive == true || warmed[id]?.let { it > 0 } == true)) return
            if (target != null && target != id) AppLog.detail(
                AppLog.Category.PLAYER, "shorts_prefetch_retarget",
                mapOf("video_id" to id, "superseded" to true))
            job?.cancel()
            target = id
            val demand = demandFor(id)
            job = scope.launch { run(id, demand) }
        }
    }

    override fun select(videoId: String) {
        synchronized(lock) {
            // Keep matching work until the engine claims it. PlaybackClaim then makes
            // byte warming yield while the player reuses the extraction and cached spans.
            if (target == videoId) return
            job?.cancel()
            job = null
            target = null
        }
    }

    override fun cancel() {
        synchronized(lock) {
            if (job?.isActive == true) AppLog.detail(
                AppLog.Category.PLAYER, "shorts_prefetch_cancelled", emptyMap())
            job?.cancel()
            job = null
            target = null
            warmed.clear()
        }
    }

    /** Bytes this session's prefetch wrote for [videoId]; telemetry uses it to tell a prefetch hit from a cold start. */
    fun prefetchedBytes(videoId: String): Long = synchronized(lock) { warmed[videoId] ?: 0L }

    private suspend fun run(videoId: String, demand: PlaybackDemand?) {
        val session = MediaSession.snapshot().generation
        if (MediaSession.privateBrowsing() || prefs?.isEnabled(PreferenceKeys.DATA_SAVER) == true) return
        coroutineContext.ensureActive()
        val preparation = extractor.prefetch(videoId, demand)
        try {
            val stream = preparation.extraction.stream.await()
            coroutineContext.ensureActive()
            // Resolved URLs outlive nothing: bail out when identity or privacy moved while the read was in flight.
            if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session) return
            val requestContext = coroutineContext
            val checkActive = {
                requestContext.ensureActive()
                // Playback owns this video now: its reads are the ones that matter, so the warm
                // stops instead of sharing the pipe with the first frame. Yield rather than
                // cancel — the resolved stream stays claimable by the player that just started.
                if (com.hhst.youtubelite.player.datasource.PlaybackClaim.blocks(videoId)) {
                    throw WarmingYield()
                }
                if (MediaSession.privateBrowsing() || MediaSession.snapshot().generation != session ||
                    prefs?.isEnabled(PreferenceKeys.DATA_SAVER) == true) throw CancellationException("Prefetch stopped")
            }
            val formats = pickFormats(stream, demand)
            val transport = if (formats.isNotEmpty()) "dash" else "hls"
            val deadline = SystemClock.elapsedRealtime() + WARM_TIME_MS
            val cached = if (formats.isNotEmpty()) cacheFormatHeads(videoId, formats, deadline, checkActive)
                else cacheHlsHead(videoId, stream, session, demand, checkActive)
            if (cached > 0) {
                AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch",
                    mapOf("video_id" to videoId, "bytes" to cached, "formats" to formats.size, "transport" to transport))
            }
        } catch (yield: WarmingYield) {
            // Playback claimed the video: stop reading bytes; the shared stream and cached spans
            // remain available to the foreground owner.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Prefetch is best-effort; playback resolves its own media.
            AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch_skip",
                mapOf("video_id" to videoId, "reason" to "error", "kind" to error.javaClass.simpleName))
        } finally {
            // Release this speculative owner. Extractor keeps an extraction already claimed
            // by playback alive independently of its prefetch owners.
            preparation.cancel()
        }
    }

    /** Foreground playback claimed the video: speculative reads end, the resolved stream is handed over intact — distinct from cancellation. */
    private class WarmingYield : CancellationException("Playback owns the pipe")

    private fun skip(videoId: String, reason: String, extra: Map<String, Any> = emptyMap()) {
        AppLog.event(AppLog.Category.PLAYER, "shorts_prefetch_skip",
            mapOf("video_id" to videoId, "reason" to reason) + extra)
    }

    private suspend fun cacheFormatHeads(videoId: String, formats: List<Format>, deadline: Long, checkActive: () -> Unit): Long = coroutineScope {
        // pickFormats selects at most one audio/video rendition. Separate per-format
        // budgets preserve the 4 MiB total without making video wait for audio's RTT.
        formats.take(2).map { format ->
            async {
                networkPermit.withPermit {
                    runInterruptible { head(videoId, format, PER_FORMAT_BUDGET_BYTES, deadline, checkActive) }
                }
            }
        }.awaitAll().sum()
    }

    /**
     * Warms the next short's HLS head through playback's own manifest/media
     * sources so reads land in the caches playback hits and spans key exactly
     * as playback's `hls:v1:` keys.
     */
    private suspend fun cacheHlsHead(videoId: String, stream: Stream, session: Long, demand: PlaybackDemand?, checkActive: () -> Unit): Long {
        val plan = stream.hlsRequestPlan ?: run { skip(videoId, "no_hls_plan"); return 0 }
        val masterUrl = stream.hlsUrl ?: run { skip(videoId, "no_hls_url"); return 0 }
        val parser = HlsPlaylistParser()
        // An unparsed playlist warms nothing; media bytes start their own clock once the playlist is in hand.
        val playlistDeadline = SystemClock.elapsedRealtime() + PLAYLIST_TIME_MS
        val master = try {
            networkPermit.withPermit {
                runInterruptible { readPlaylist(dataSources.vodHls(plan).createDataSource(C.DATA_TYPE_MANIFEST), masterUrl, playlistDeadline, checkActive) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            skip(videoId, "master_failed", mapOf("kind" to error.javaClass.simpleName)); return 0
        }
        coroutineContext.ensureActive()
        val multivariant = parser.parse(Uri.parse(masterUrl), master.inputStream()) as? HlsMultivariantPlaylist
            ?: run { skip(videoId, "master_unparsed"); return 0 }
        // Use playback's decoder-aware selector and shared bandwidth estimate. Highest height /
        // bitrate alone can warm another codec or frame rate, leaving the first frame cold.
        val maxHeight = demand?.height?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val parameters = TrackSelectionParameters.Builder(context).apply {
            if (maxHeight != Int.MAX_VALUE) {
                setMinVideoSize(0, maxHeight)
                setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            }
        }.build()
        val variant = pickHlsVariant(multivariant.variants, maxHeight) { formats ->
            PlaybackStartup.selectFormat(formats,
                DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate,
                parameters, PlaybackStartup.videoSupport(context))
        }
            ?: run { skip(videoId, "no_variant", mapOf("variants" to multivariant.variants.size)); return 0 }
        AppLog.detail(AppLog.Category.PLAYER, "shorts_prefetch_variant",
            mapOf("video_id" to videoId, "height" to variant.format.height, "bitrate" to variant.format.bitrate))
        val mediaUrl = variant.url.toString()
        val mediaSource = dataSources.vodHlsMedia(plan) ?: run { skip(videoId, "no_cache_source"); return 0 }
        try {
            val media = try {
                networkPermit.withPermit { runInterruptible {
                    checkActive()
                    readPlaylist(dataSources.vodHls(plan).createDataSource(C.DATA_TYPE_MANIFEST), mediaUrl, playlistDeadline, checkActive)
                } }
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
            val mediaDeadline = SystemClock.elapsedRealtime() + WARM_TIME_MS
            val segments = playlist.segments.take(HLS_MAX_SEGMENTS)
            val init = segments.firstOrNull()?.initializationSegment
            for (segment in listOfNotNull(init) + segments) {
                if (remaining <= 0 || SystemClock.elapsedRealtime() >= mediaDeadline) break
                val covered = networkPermit.withPermit {
                    runInterruptible { segmentHead(videoId, mediaSource, playlist.baseUri, segment, remaining, mediaDeadline, checkActive) }
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

    /** Mirrors the resolver's adaptive pick; plan-less and remote-DASH formats are skipped — their requests cannot be replayed against the byte cache reliably. */
    private fun pickFormats(stream: Stream, demand: PlaybackDemand?): List<Format> {
        val candidates = stream.formats.filter { it.requestPlan != null }
        if (candidates.none { it.hasDashRanges }) return emptyList()
        val videos = StreamSelection.preferPlayable(candidates.filter { it.videoOnly && it.hasDashRanges }, false)
        val audios = StreamSelection.preferPlayable(
            StreamSelection.audioCandidates(candidates.filter { it.audioOnly && it.hasDashRanges }), false)
        val pool = StreamSelection.filterBest(videos)
        val parameters = TrackSelectionParameters.Builder(context).apply {
            demand?.height?.takeIf { it > 0 }?.let {
                setMinVideoSize(0, it); setMaxVideoSize(Int.MAX_VALUE, it)
            }
        }.build()
        // Auto playback is no longer capped at 480p. Use its bandwidth/decoder-aware choice
        // here as well, otherwise all the warmed bytes can belong to an unused rendition.
        val mediaFormats = pool.map { format ->
            MediaFormat.Builder().setId(format.itag?.toString())
                .setWidth(format.width).setHeight(format.height).setFrameRate(format.fps.toFloat())
                .setAverageBitrate(format.bitrate).setCodecs(format.codec)
                .setContainerMimeType(format.mimeType)
                .setSampleMimeType(androidx.media3.common.MimeTypes.getVideoMediaMimeType(format.codec.orEmpty()))
                .build()
        }
        val selected = PlaybackStartup.selectFormat(mediaFormats,
            DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate,
            parameters, PlaybackStartup.videoSupport(context))
        val video = selected?.let { pool.getOrNull(mediaFormats.indexOf(it)) }
        val audio = audios.maxByOrNull { it.bitrate }
        return listOfNotNull(audio, video).distinctBy { it.url }
    }

    private fun head(videoId: String, format: Format, budget: Long, deadline: Long, checkActive: () -> Unit): Long {
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
        return cacheHead(videoId, source, format.url, 0, length, deadline, checkActive)
    }

    /** Byterange segments are skipped unless the offset is explicit: a wrong-offset span would serve playback wrong bytes under the same cache key. */
    private fun segmentHead(videoId: String, source: CacheDataSource, baseUri: String, segment: HlsMediaPlaylist.Segment, budget: Long, deadline: Long, checkActive: () -> Unit): Long {
        val ranged = segment.byteRangeLength >= 0
        if (ranged && segment.byteRangeOffset < 0) return 0
        val start = if (ranged) segment.byteRangeOffset else 0L
        val total = if (ranged) segment.byteRangeLength else -1L
        val length = budget.let { if (total > 0) minOf(it, total) else it }
        if (length <= 0) return 0
        return cacheHead(videoId, source, androidx.media3.common.util.UriUtil.resolve(baseUri, segment.url), start, length, deadline, checkActive)
    }

    /** One request per head; cancellation checks belong between reads, not new HTTP requests. */
    private fun cacheHead(videoId: String, source: CacheDataSource, url: String, position: Long, length: Long, deadline: Long, checkActive: () -> Unit): Long {
        var written = 0L
        try {
            checkActive()
            if (SystemClock.elapsedRealtime() >= deadline) return 0
            val spec = DataSpec.Builder().setUri(url).setPosition(position).setLength(length)
                .setCustomData(PrefetchDeadline(deadline)).build()
            CacheWriter(source, spec, ByteArray(PREFETCH_READ_BYTES)) { _, bytesCached, _ ->
                // Count before checking: a yield/timeout still leaves these bytes in the cache.
                written = bytesCached
                checkActive()
                if (SystemClock.elapsedRealtime() >= deadline) throw WarmingDeadline()
            }.cache()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Partially written spans stay in the cache and still help.
        } finally {
            runCatching { source.close() }
            if (written > 0) synchronized(lock) {
                if (target == videoId) warmed[videoId] = (warmed[videoId] ?: 0L) + written
            }
        }
        return written
    }

    private class WarmingDeadline : java.io.IOException("Prefetch budget exhausted")

    /** Time-bounded as well as size-bounded: a few-hundred-KB playlist on a slow link is seconds of speculative reading that could outlast the tap. */
    private fun readPlaylist(source: DataSource, url: String, deadline: Long, checkActive: () -> Unit): ByteArray {
        try {
            checkActive()
            if (SystemClock.elapsedRealtime() >= deadline) throw WarmingDeadline()
            source.open(DataSpec.Builder().setUri(url).setCustomData(PrefetchDeadline(deadline)).build())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (out.size() < PLAYLIST_LIMIT_BYTES) {
                checkActive()
                if (SystemClock.elapsedRealtime() >= deadline) throw WarmingDeadline()
                val read = source.read(buffer, 0, minOf(buffer.size, PLAYLIST_LIMIT_BYTES - out.size()))
                if (read == C.RESULT_END_OF_INPUT) return out.toByteArray()
                out.write(buffer, 0, read)
            }
            throw java.io.IOException("Prefetch playlist exceeds size budget")
        } finally {
            runCatching { source.close() }
        }
    }

    private fun headBytes(format: Format): Long {
        val head = (maxOf(format.initEnd, format.indexEnd) + 1).coerceAtLeast(0).toLong()
        val media = if (format.bitrate > 0) format.bitrate.toLong() / 8 * HEAD_SECONDS else DEFAULT_MEDIA_BYTES
        return (head + media).coerceAtMost(PER_FORMAT_BUDGET_BYTES)
    }

    companion object {
        /** Sized to cover the first rendered frames, not a fixed slice of media at any quality. */
        const val HEAD_SECONDS = 2
        /** Warming is time-bounded: a byte budget cannot fit every link speed, so the budget is how long warming may own the pipe. */
        const val WARM_TIME_MS = 2000L
        /** Playlist documents are not optional, so they get a wider bound than the media bytes. */
        const val PLAYLIST_TIME_MS = 4000L
        const val TOTAL_BUDGET_BYTES = 4L * 1024 * 1024
        const val PER_FORMAT_BUDGET_BYTES = 2L * 1024 * 1024
        /** Check the time budget and foreground handover after each read on the same connection. */
        const val PREFETCH_READ_BYTES = 32 * 1024
        const val DEFAULT_MEDIA_BYTES = 256L * 1024
        const val PLAYLIST_LIMIT_BYTES = com.hhst.youtubelite.player.datasource.VodManifestCache.MAX_DOCUMENT_BYTES
        const val HLS_MAX_SEGMENTS = 2
        const val MAX_WARMED = 8

        /** Mirrors the resolver's startup pick over HLS variants. */
        internal fun pickHlsVariant(
            variants: List<HlsMultivariantPlaylist.Variant>,
            maxHeight: Int,
            select: ((List<MediaFormat>) -> MediaFormat?)? = null,
        ): HlsMultivariantPlaylist.Variant? {
            select?.invoke(variants.map { it.format })?.let { chosen ->
                variants.firstOrNull { it.format === chosen }?.let { return it }
            }
            val pool = variants.filter { it.format.height <= 0 || it.format.height <= maxHeight }
                .ifEmpty { variants }
            return pool.maxWithOrNull(compareBy({ it.format.height }, { it.format.bitrate }))
        }
    }
}
