package com.hhst.youtubelite.cast

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ChunkIndex
import com.hhst.youtubelite.cast.LocalStreamProxy.Companion.LENGTH_UNSET
import com.hhst.youtubelite.cast.LocalStreamProxy.Companion.resolveBindHost
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.core.Markup
import com.hhst.youtubelite.player.datasource.YoutubeHttpDataSource
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * LAN HTTP proxy: DASH manifest + YouTube byte-range segments for Chromecast / dash.js.
 */
@UnstableApi
class LocalStreamProxy(
    context: Context,
    http: OkHttpClient,
    port: Int = 0,
    /** LAN IPv4 to bind; resolved by the caller via [resolveBindHost]. */
    private val advertisedHost: String,
) : NanoHTTPD(advertisedHost, port) {

    fun interface UrlRefresher {
        fun refreshUrl(token: String, videoId: String?, itag: Int?): String?
    }

    private data class StreamInfo(
        val youtubeUrl: String,
        /** Container MIME for the stream response Content-Type ("audio/webm" etc.). */
        val mimeType: String = "",
        val indexStart: Long,
        val indexEnd: Long,
        val contentLength: Long,
        /** Identity of the [configure] generation this entry belongs to. */
        val configId: Long,
        val publishedVideoId: String? = null,
        val itag: Int? = null,
    )

    private val appContext = context.applicationContext
    private val dataSourceFactory = YoutubeHttpDataSource.Factory(
        http,
        Constants.USER_AGENT,
    )
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(10_000)
        .setRangeParameterEnabled(true)
        .setRnParameterEnabled(true)

    /** Token → stream info. Published by atomic reference swap; readers snapshot it once. */
    @Volatile private var streamInfo: Map<String, StreamInfo> = emptyMap()
    /** Guards the read-modify-write swaps on [streamInfo] across NanoHTTPD threads. */
    private val streamInfoLock = Any()
    private val lastRefreshMs = ConcurrentHashMap<String, Long>()
    /** Generation counter bumped by every [configure]; see [StreamInfo.configId]. */
    private val configGen = AtomicLong()

    @Volatile var urlRefresher: UrlRefresher? = null
    @Volatile private var manifestXml: String? = null
    @Volatile private var videoTitle: String? = null
    @Volatile private var currentVideoId: String? = null
    @Volatile private var lastLinkPeerSeenMs = 0L
    /** Unlisted path prefix so LAN clients need the shared URL, not just the port. */
    private val accessKey = UUID.randomUUID().toString().replace("-", "")

    fun isLinkPeerConnected(): Boolean {
        val last = lastLinkPeerSeenMs
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last < LINK_PEER_TIMEOUT_MS
    }

    fun allocateGeneration(): Long = configGen.incrementAndGet()

    fun currentConfigId(): Long = configGen.get()

    fun configure(
        manifestXml: String?,
        videoTitle: String,
        videoId: String?,
        videoUrl: String?,
        videoMime: String?,
        audioUrl: String?,
        audioMime: String?,
        video: StreamRanges?,
        audio: StreamRanges?,
        videoItag: Int? = null,
        audioItag: Int? = null,
        generation: Long? = null,
    ) {
        val configId = generation ?: configGen.incrementAndGet()
        val tokens = mutableMapOf<String, StreamInfo>()
        if (!videoUrl.isNullOrBlank() && video != null) {
            tokens["v"] = StreamInfo(
                youtubeUrl = videoUrl,
                mimeType = videoMime.orEmpty(),
                indexStart = video.indexStart,
                indexEnd = video.indexEnd,
                contentLength = video.contentLength,
                configId = configId,
                publishedVideoId = videoId,
                itag = videoItag,
            )
        }
        if (!audioUrl.isNullOrBlank() && audio != null) {
            tokens["a"] = StreamInfo(
                youtubeUrl = audioUrl,
                mimeType = audioMime.orEmpty(),
                indexStart = audio.indexStart,
                indexEnd = audio.indexEnd,
                contentLength = audio.contentLength,
                configId = configId,
                publishedVideoId = videoId,
                itag = audioItag,
            )
        }
        this.videoTitle = videoTitle
        this.currentVideoId = videoId
        // Publish the token table before the manifest: a concurrent serveManifest
        // then never exposes a manifest whose /stream tokens are not registered
        // yet. The swap itself must take [streamInfoLock]: serveStream's 403
        // refresh and the content-length backfill rebuild the table under that
        // lock, and an unlocked full-table replace here could interleave with
        // them and revert a just-refreshed entry (or resurrect the previous
        // video's tokens).
        synchronized(streamInfoLock) {
            // New generation: the previous video's refresh cooldowns must
            // not mute this config's first 403 refresh.
            lastRefreshMs.clear()
            this.streamInfo = tokens
        }
        this.manifestXml = manifestXml
    }

    data class StreamRanges(
        val indexStart: Long,
        val indexEnd: Long,
        val contentLength: Long,
    )

    fun proxyUrl(path: String): String {
        val suffix = when {
            path.isEmpty() -> ""
            path.startsWith("/") -> path
            else -> "/$path"
        }
        return "http://$advertisedHost:$listeningPort/$accessKey$suffix"
    }

    fun fetchChunkIndex(token: String): ChunkIndex? {
        val info = streamInfo[token] ?: return null
        if (info.indexStart < 0 || info.indexEnd <= info.indexStart) return null
        val length = info.indexEnd - info.indexStart + 1
        return fetchSidx(info, info.indexStart, length)
            // The from-0 fallback scan is capped by readFully's 512 KiB window:
            // past that the index can never be found and the probe would only
            // burn an upstream request.
            ?: SIDX_READ_CAP_BYTES.takeIf { info.indexEnd + 1 <= it }
                ?.let { fetchSidx(info, 0L, info.indexEnd + 1) }
    }

    private fun fetchSidx(info: StreamInfo, start: Long, length: Long): ChunkIndex? {
        val source = dataSourceFactory.createDataSource()
        return try {
            val spec = DataSpec.Builder()
                .setUri(info.youtubeUrl.toUri())
                .setPosition(start)
                .setLength(length)
                .build()
            source.open(spec)
            val bytes = readFully(source, length.coerceAtMost(SIDX_READ_CAP_BYTES).toInt())
            SidxParser.parse(bytes, start)
        } catch (t: Throwable) {
            Log.w(TAG, "fetchSidx failed", t)
            null
        } finally {
            runCatching { source.close() }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val raw = session.uri ?: return text(Response.Status.NOT_FOUND, "Not found")
        val prefix = "/$accessKey"
        if (!raw.startsWith(prefix)) return text(Response.Status.FORBIDDEN, "Forbidden")
        val uri = raw.substring(prefix.length).ifEmpty { "/" }
        if (session.method == Method.OPTIONS) {
            return corsPreflight(header(session, "user-agent"))
        }
        // NanoHTTPD would still write a body for HEAD unless we hand it an
        // empty stream. Keep GET's status, mime, Content-Length and CORS
        // headers so HEAD describes the same resource.
        if (session.method == Method.HEAD) {
            val get = serveGet(session, uri)
            return try {
                asHeadResponse(get)
            } finally {
                runCatching { get.close() }
            }
        }
        return serveGet(session, uri)
    }

    private fun serveGet(session: IHTTPSession, uri: String): Response = when {
        uri == "/player" -> servePlayer(header(session, "user-agent"))
        uri == "/dash.all.min.js" -> serveAsset("cast/dash.all.min.js", "application/javascript")
        uri == "/manifest.mpd" -> serveManifest(session)
        uri.startsWith("/stream/") -> {
            // NanoHTTPD's session.uri never carries the query string.
            val token = uri.removePrefix("/stream/")
            if (token != "v" && token != "a") {
                text(Response.Status.NOT_FOUND, "Bad stream token")
            } else {
                serveStream(session, token)
            }
        }
        else -> text(Response.Status.NOT_FOUND, "Not found")
    }

    private fun serveManifest(session: IHTTPSession): Response {
        val ua = header(session, "user-agent")
        // A browser-UA manifest fetch is the link-cast liveness signal (the
        // page's probe re-fetches it every 15 s); curl/scanner probes must
        // not light the "link connected" banner up.
        if (!isReceiverUa(ua) && isLinkPeerUa(ua)) lastLinkPeerSeenMs = SystemClock.elapsedRealtime()
        var xml = manifestXml ?: return text(Response.Status.NOT_FOUND, "No manifest")
        val vid = currentVideoId
        if (!vid.isNullOrBlank()) {
            // Hex-encode: XML comments must not contain "--", and YouTube ids
            // legitimately can. The receiver only compares the marker for
            // equality (player.html probeManifest), so the encoding is
            // transparent there.
            val encoded = vid.toByteArray(Charsets.US_ASCII)
                .joinToString("") { "%02x".format(it) }
            val marker = "<!-- vid:$encoded -->"
            if (!xml.contains(marker)) {
                val decl = xml.indexOf("?>")
                xml = if (decl >= 0) {
                    xml.substring(0, decl + 2) + marker + xml.substring(decl + 2)
                } else {
                    marker + xml
                }
            }
        }
        val bytes = xml.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK, "application/dash+xml", ByteArrayInputStream(bytes), bytes.size.toLong(),
        ).also { addCors(it, ua) }
    }

    private fun servePlayer(ua: String?): Response {
        // Same liveness gate as [serveManifest]: the connected banner must not
        // light up for a scanner that merely found the unlisted /player URL.
        if (!isReceiverUa(ua) && isLinkPeerUa(ua)) lastLinkPeerSeenMs = SystemClock.elapsedRealtime()
        return try {
            val raw = appContext.assets.open("cast/player.html").use { it.readBytes() }
            var html = String(raw, Charsets.UTF_8)
            html = html.replace("__VIDEO_TITLE__", Markup.html(videoTitle ?: "Player"))
                .replace("__MANIFEST_URL__", Markup.jsString(proxyUrl("/manifest.mpd")))
            val bytes = html.toByteArray(Charsets.UTF_8)
            newFixedLengthResponse(
                Response.Status.OK, "text/html", ByteArrayInputStream(bytes), bytes.size.toLong(),
            )
        } catch (_: Exception) {
            text(Response.Status.INTERNAL_ERROR, "Failed to serve player page")
        }
    }

    private fun serveAsset(path: String, mime: String): Response {
        return try {
            val bytes = appContext.assets.open(path).use { it.readBytes() }
            newFixedLengthResponse(
                Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong(),
            )
        } catch (_: Exception) {
            text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun serveStream(session: IHTTPSession, token: String): Response {
        val info = streamInfo[token] ?: return text(Response.Status.NOT_FOUND, "Unknown token")
        val reqGen = query(session, "g")?.toLongOrNull()
        if (!acceptsGeneration(reqGen, info.configId)) {
            return text(Response.Status.FORBIDDEN, "Stale generation")
        }
        val ua = header(session, "user-agent")
        val chunkMax = chunkMaxBytes(ua)
        var rangeStart = 0L
        var rangeEnd = if (info.contentLength > 0) info.contentLength - 1 else LENGTH_UNSET
        var hasRange = false
        var fromSeg = false
        val seg = query(session, "seg")
        if (seg != null && '-' in seg) {
            val parts = seg.split('-', limit = 2)
            // A non-numeric start is malformed, not an implicit 0.
            rangeStart = parts[0].toLongOrNull()
                ?: return text(Response.Status.BAD_REQUEST, "Bad segment")
            rangeEnd = parts.getOrNull(1)?.toLongOrNull() ?: LENGTH_UNSET
            hasRange = true
            fromSeg = true
        } else {
            val rangeHeader = header(session, "range")
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                hasRange = true
                val parsed = parseRange(rangeHeader, info.contentLength) ?: return text(
                    Response.Status.RANGE_NOT_SATISFIABLE, "Bad range",
                )
                rangeStart = parsed[0]
                rangeEnd = if (parsed[1] != LENGTH_UNSET) parsed[1]
                else if (info.contentLength > 0) info.contentLength - 1
                else LENGTH_UNSET
            }
        }
        if (rangeStart < 0 || (rangeEnd != LENGTH_UNSET && rangeEnd < rangeStart)) {
            // A malformed ?seg= is a bad request, not an unsatisfiable byte range.
            return if (fromSeg) text(Response.Status.BAD_REQUEST, "Bad segment")
            else text(Response.Status.RANGE_NOT_SATISFIABLE, "Bad range")
        }
        if (fromSeg) {
            val segLen = if (rangeEnd != LENGTH_UNSET) rangeEnd - rangeStart + 1 else LENGTH_UNSET
            if (segLen == LENGTH_UNSET || segLen <= 0L || segLen > MAX_SEG_BYTES) {
                return text(Response.Status.BAD_REQUEST, "Bad segment")
            }
        }
        if (info.contentLength > 0 && rangeStart >= info.contentLength) {
            return text(Response.Status.RANGE_NOT_SATISFIABLE, "Range beyond end of stream")
        }
        val requestLength = if (rangeEnd != LENGTH_UNSET) rangeEnd - rangeStart + 1 else LENGTH_UNSET
        val chunkStart = rangeStart
        val totalCap = if (info.contentLength > 0) info.contentLength - 1 else Long.MAX_VALUE
        // Open-ended requests ("bytes=X-" or no Range at all) get a chunk end of
        // our choosing; a known total caps it at the last byte of the stream.
        val openEnded = !fromSeg && requestLength == LENGTH_UNSET
        // A known total caps every request at the last byte of the stream: a
        // stale segment index must not promise bytes the upstream will never
        // deliver (the body would then end short of the claimed length).
        val servedLength = when {
            fromSeg -> if (info.contentLength > 0) {
                minOf(requestLength, info.contentLength - rangeStart)
            } else {
                requestLength
            }
            else -> {
                // A bare GET (no Range header) asks for the whole
                // representation: chunk-capping it would truncate the 200 body
                // for any RFC-compliant client. Receivers and dash.js always
                // send Range or ?seg=, so the chunk cap is ranged-only.
                if (!hasRange) {
                    if (info.contentLength > 0) info.contentLength - rangeStart else LENGTH_UNSET
                } else {
                    val end = minOf(
                        if (requestLength != LENGTH_UNSET) rangeEnd else rangeStart + chunkMax - 1,
                        rangeStart + chunkMax - 1,
                        totalCap,
                    )
                    end - chunkStart + 1
                }
            }
        }
        val totalSize = if (info.contentLength > 0) info.contentLength else LENGTH_UNSET
        return try {
            streamUpstream(token, info, chunkStart, servedLength, hasRange, fromSeg, totalSize, ua, openEnded)
        } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 403) {
                val now = SystemClock.elapsedRealtime()
                val last = lastRefreshMs[token] ?: 0L
                if (now - last >= REFRESH_COOLDOWN_MS) {
                    lastRefreshMs[token] = now
                    val fresh = urlRefresher?.refreshUrl(
                        token, info.publishedVideoId, info.itag,
                    )
                    if (fresh != null) {
                        // Re-read + swap under the lock: NanoHTTPD serves
                        // requests on multiple threads, and a concurrent
                        // content-length backfill must not be lost to this
                        // stale snapshot.
                        val refreshed = synchronized(streamInfoLock) {
                            val current = streamInfo[token]
                                ?: return text(Response.Status.FORBIDDEN, "Stale token")
                            // A reconfigure raced this request: the table now belongs
                            // to another video, and writing the fresh URL would corrupt
                            // its entry. Skip the write and the retry.
                            if (current.configId != info.configId) {
                                return text(Response.Status.FORBIDDEN, "Stale token")
                            }
                            val updated = current.copy(youtubeUrl = fresh)
                            streamInfo = streamInfo + (token to updated)
                            updated
                        }
                        return try {
                            streamUpstream(token, refreshed, chunkStart, servedLength, hasRange, fromSeg, totalSize, ua, openEnded)
                        } catch (t: Throwable) {
                            Log.w(TAG, "stream retry failed", t)
                            text(Response.Status.INTERNAL_ERROR, "Proxy error")
                        }
                    }
                }
            }
            // Only a real upstream 403 answers 403; other codes map to the
            // closest HTTP semantic so the receiver can calibrate (416) or
            // retry (5xx) instead of treating every failure as forbidden.
            val status = when (e.responseCode) {
                416 -> Response.Status.RANGE_NOT_SATISFIABLE
                in 500..599 -> Response.Status.SERVICE_UNAVAILABLE
                else -> Response.Status.FORBIDDEN
            }
            text(status, "Upstream ${e.responseCode}")
        } catch (t: Throwable) {
            Log.w(TAG, "stream fetch failed", t)
            text(Response.Status.INTERNAL_ERROR, "Proxy error")
        }
    }

    private fun streamUpstream(
        token: String,
        info: StreamInfo,
        chunkStart: Long,
        servedLength: Long,
        hasRange: Boolean,
        fromSeg: Boolean,
        totalSize: Long,
        ua: String?,
        openEnded: Boolean,
    ): Response {
        // An open-ended request with no known total leaves the upstream length
        // unset: googlevideo clips its view to the &range= window, so the
        // resolved response length reveals the true remaining size (see
        // [maybeBackfillContentLength]). Everything else asks for exactly the
        // bytes the client will be served.
        val upstreamUnbounded = openEnded && info.contentLength <= 0
        val specBuilder = DataSpec.Builder()
            .setUri(info.youtubeUrl.toUri())
            .setPosition(chunkStart)
        if (!upstreamUnbounded) specBuilder.setLength(servedLength)
        val source = dataSourceFactory.createDataSource()
        val resolved = source.open(specBuilder.build())
        if (upstreamUnbounded) {
            if (resolved == 0L) {
                runCatching { source.close() }
                return text(Response.Status.RANGE_NOT_SATISFIABLE, "Range beyond end of stream")
            }
            maybeBackfillContentLength(token, info, chunkStart, resolved)
        }
        // The upstream may end inside the requested chunk when the stream is
        // shorter than the chunk; never claim more bytes than it will deliver.
        // A bare GET with no known total leaves [servedLength] unset — the
        // resolved length IS the remaining body.
        val bodyLength = when {
            upstreamUnbounded && servedLength == LENGTH_UNSET -> resolved
            upstreamUnbounded && resolved in 1 until servedLength -> resolved
            else -> servedLength
        }
        val total = when {
            totalSize > 0 -> totalSize
            upstreamUnbounded && resolved > 0 -> chunkStart + resolved
            else -> LENGTH_UNSET
        }
        val input = DataSourceStream(source, bodyLength)
        val mime = if (token == "a") {
            info.mimeType.ifBlank { "audio/mp4" }
        } else {
            info.mimeType.ifBlank { "video/mp4" }
        }
        val response: Response
        if (fromSeg) {
            // ?seg=START-END: a standalone segment resource (its own URL in the
            // manifest's SegmentList). 200 OK — the URL identifies a complete
            // segment, not a byte range of a larger resource; 206 + Content-Range
            // here confuses the cast receiver.
            response = newFixedLengthResponse(Response.Status.OK, mime, input, bodyLength)
        } else if (hasRange) {
            response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT, mime, input, bodyLength,
            )
            // Content-Range is only meaningful for a byte-range response.
            response.addHeader(
                "Content-Range",
                "bytes $chunkStart-${chunkStart + bodyLength - 1}/${if (total > 0) total.toString() else "*"}",
            )
        } else {
            // No Range header: 200 OK with the whole remaining representation
            // (chunked chunk caps are ranged-request behavior only).
            response = newFixedLengthResponse(Response.Status.OK, mime, input, bodyLength)
        }
        response.addHeader("Accept-Ranges", "bytes")
        addCors(response, ua)
        return response
    }

    /**
     * googlevideo clips its view to the &range= window: an open-ended window
     * runs to the true end of file, so the resolved response length reveals
     * the total size. Backfilling it lets later requests serve precise chunk
     * ends and Content-Range totals instead of over-claiming past EOF. An
     * overestimate is harmless (clamping then rarely binds), so this trusts
     * the first resolved length only for a token that has no total yet.
     */
    private fun maybeBackfillContentLength(token: String, info: StreamInfo, chunkStart: Long, resolved: Long) {
        if (resolved <= 0L) return
        synchronized(streamInfoLock) {
            val current = streamInfo[token] ?: return
            // The request raced a reconfigure: this resolved length belongs to
            // the previous video's URL, not the current entry.
            if (current.configId != info.configId) return
            if (current.contentLength > 0) return
            streamInfo = streamInfo + (token to current.copy(contentLength = chunkStart + resolved))
        }
    }

    private fun header(session: IHTTPSession, name: String): String? =
        // NanoHTTPD lowercases header keys on decode.
        session.headers?.get(name.lowercase())

    private fun query(session: IHTTPSession, name: String): String? =
        session.parameters[name]?.firstOrNull()

    private fun corsPreflight(ua: String?): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain", "").also { addCors(it, ua) }

    /**
     * Chromecast's default receiver is a cross-origin page and needs CORS.
     * Link-cast `player.html` is same-origin. Do not advertise `*` for arbitrary
     * browser UAs — a leaked proxy URL would otherwise be readable via fetch().
     */
    private fun addCors(response: Response, ua: String?) {
        if (!isReceiverUa(ua)) return
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
    }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body)

    private fun readFully(source: YoutubeHttpDataSource, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = source.read(buf, 0, minOf(buf.size, max - total))
            // A 0 return (media3 permits it for non-blocking sources) would
            // spin forever without counting as progress or EOF.
            if (n == 0) break
            if (n == C.RESULT_END_OF_INPUT || n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private class DataSourceStream(
        private val source: YoutubeHttpDataSource,
        private val length: Long,
    ) : InputStream() {
        private var remaining = length

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n == C.RESULT_END_OF_INPUT || n < 0) {
                remaining = 0
                return -1
            }
            remaining -= n
            return n
        }
        override fun close() {
            closeSource()
        }

        /**
         * NanoHTTPD 2.3.1's Response.send() swallows IOException without closing
         * the response InputStream (bytecode: the IOException catch block only
         * logs "Could not send response to the client" and never calls
         * safeClose(data); HTTPSession never calls Response.close at all), so a
         * client abort or an upstream mid-read failure would leave [source] —
         * and its OkHttp connection to googlevideo — open until GC. The
         * finalizer is the last-resort reclaim; java.lang.ref.Cleaner needs
         * API 33 and minSdk is 26. The normal path stays NanoHTTPD's
         * success-path close plus [close].
         */
        @Suppress("deprecation")
        fun finalize() {
            closeSource()
        }

        private fun closeSource() {
            runCatching { source.close() }
        }
    }

    companion object {
        private const val TAG = "YTLCastProxy"
        private const val LOOPBACK = "127.0.0.1"
        /** media3's C.LENGTH_UNSET is the int -1; this is the long form used by DataSpec lengths. */
        private const val LENGTH_UNSET: Long = C.LENGTH_UNSET.toLong()
        private const val DEFAULT_CHUNK = 256 * 1024
        private const val LINK_PEER_CHUNK = 2 * 1024 * 1024
        /** Upper bound for a ?seg= byte span (DASH media segments, not the whole file). */
        private const val MAX_SEG_BYTES = 16L * 1024 * 1024
        /** sidx fetches never read more than this (single seek-index read). */
        private const val SIDX_READ_CAP_BYTES = 512L * 1024
        private const val REFRESH_COOLDOWN_MS = 30_000L
        private const val LINK_PEER_TIMEOUT_MS = 30_000L
        private val RECEIVER_UAS = arrayOf("chromecast", "crkey", "castsdk")

        internal fun isReceiverUa(ua: String?): Boolean {
            if (ua == null) return false
            val lower = ua.lowercase()
            return RECEIVER_UAS.any { lower.contains(it) }
        }

        /** Loose browser check: dash.js link-cast receivers always send one. */
        internal fun isLinkPeerUa(ua: String?): Boolean =
            ua?.lowercase()?.contains("mozilla") == true

        /**
         * Stream URLs stamp `g=` with the published config id. A missing or
         * mismatched generation is an old manifest and must not be served as
         * the current video's ranges.
         */
        internal fun acceptsGeneration(requestGeneration: Long?, configId: Long): Boolean =
            requestGeneration != null && requestGeneration == configId

        /**
         * Chromecast receivers stall on large 200 OK bodies, so they stay on
         * the small default chunk. Browser link-cast needs bigger slices —
         * 256 KiB round-trips through the phone underrun ("play, stall, play").
         */
        internal fun chunkMaxBytes(ua: String?): Int =
            if (isReceiverUa(ua)) DEFAULT_CHUNK else LINK_PEER_CHUNK

        /**
         * HEAD of [get]: empty body, same status/mime/length/headers.
         * GET's Content-Length is the resource size, not the empty HEAD body.
         */
        internal fun asHeadResponse(get: Response): Response {
            val length = nanoContentLength(get)
            val head = newFixedLengthResponse(
                get.status,
                get.mimeType,
                ByteArrayInputStream(ByteArray(0)),
                length,
            )
            @Suppress("UNCHECKED_CAST")
            val headers = nanoField(get, "header") as? Map<*, *> ?: emptyMap<Any, Any>()
            headers.forEach { (name, value) ->
                if (name is String && value is String &&
                    !name.equals("content-length", ignoreCase = true)
                ) {
                    head.addHeader(name, value)
                }
            }
            return head
        }

        internal fun nanoContentLength(response: Response): Long {
            val raw = nanoField(response, "contentLength") as? Number ?: return 0L
            return raw.toLong()
        }

        private fun nanoField(target: Any, name: String): Any? {
            val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
            return field.get(target)
        }

        /**
         * RFC 7233 `bytes=start-end`. Suffix ranges (`bytes=-N`) need a known
         * content length; without one they are unsatisfiable rather than silently
         * turned into `0-N`. An open-ended end is reported as [LENGTH_UNSET];
         * range sanity (end >= start) is the caller's job.
         */
        internal fun parseRange(header: String, contentLength: Long): LongArray? {
            val spec = header.removePrefix("bytes=").trim()
            // Multi-range specs are rejected outright (the caller answers 416);
            // naively taking the first dash would fake an open-ended 206.
            if (spec.isEmpty() || spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startRaw = spec.substring(0, dash)
            val endRaw = spec.substring(dash + 1)
            if (startRaw.isEmpty()) {
                val suffix = endRaw.toLongOrNull() ?: return null
                if (suffix <= 0L || contentLength <= 0L) return null
                val start = (contentLength - suffix).coerceAtLeast(0L)
                return longArrayOf(start, contentLength - 1)
            }
            val start = startRaw.toLongOrNull() ?: return null
            val end = endRaw.toLongOrNull() ?: LENGTH_UNSET
            return longArrayOf(start, end)
        }

        /**
         * LAN IPv4 for bind, or null when no usable LAN address exists. There
         * is deliberately NO loopback fallback: a 127.0.0.1-advertised URL is
         * unreachable from the receiver, so the caller (CastController)
         * refuses link-cast instead of handing out a dead URL.
         */
        fun resolveBindHost(context: Context): String? =
            detectLanIp(context).takeIf { it != LOOPBACK }

        private fun detectLanIp(context: Context): String {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val ifaceName = cm?.getLinkProperties(cm.activeNetwork)?.interfaceName
                if (ifaceName != null && !isTunnel(ifaceName)) {
                    ipv4Of(ifaceName)?.let { return it }
                }
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return LOOPBACK
                var fallback: String? = null
                while (ifaces.hasMoreElements()) {
                    val network = ifaces.nextElement()
                    if (!network.isUp || network.isLoopback) continue
                    val name = network.displayName ?: continue
                    if (isTunnel(name)) continue
                    val addrs = network.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (name.contains("wlan", true) || name.contains("eth", true) ||
                                name.startsWith("ap") || name.startsWith("en")
                            ) {
                                return ip
                            }
                            if (fallback == null) fallback = ip
                        }
                    }
                }
                return fallback ?: LOOPBACK
            } catch (_: Exception) {
                return LOOPBACK
            }
        }

        private fun ipv4Of(ifaceName: String): String? {
            val network = NetworkInterface.getByName(ifaceName) ?: return null
            val addrs = network.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
            }
            return null
        }

        private fun isTunnel(name: String): Boolean {
            val n = name.lowercase()
            // "tap" too: TAP interfaces are virtual (VPN bridges), not LAN adapters.
            return n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp") ||
                n.startsWith("wg") || n.contains("vpn") || n.startsWith("rmnet")
        }
    }
}
