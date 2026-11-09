package com.hhst.youtubelite.cast

import com.hhst.youtubelite.diagnostics.*

import android.content.Context
import android.view.View
import com.hhst.youtubelite.R
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.ChunkIndex
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.core.Markup
import androidx.core.os.ConfigurationCompat
import com.hhst.youtubelite.extractor.MediaRequestPlan
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
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

@UnstableApi
class LocalStreamProxy(
    context: Context,
    http: OkHttpClient,
    port: Int = 0,
    private val advertisedHost: String,
    private val mediaRequests: YoutubeMediaRequests? = null,
) : NanoHTTPD(advertisedHost, port) {
    init { setAsyncRunner(BoundedProxyRunner()) }

    @Volatile var diagnosticContext = DiagnosticContext(castSessionId = java.util.UUID.randomUUID().toString())

    fun interface UrlRefresher {
        fun refreshUrl(token: String, videoId: String?, itag: Int?): String?
        fun backupUrl(token: String, videoId: String?, itag: Int?, failedProfile: String?): String? = null
    }

    private data class StreamInfo(
        val youtubeUrl: String,
        val mimeType: String = "",
        val indexStart: Long,
        val indexEnd: Long,
        val contentLength: Long,
        val configId: Long,
        val publishedVideoId: String? = null,
        val itag: Int? = null,
        val requestPlan: MediaRequestPlan? = null,
        val diagnostic: DiagnosticContext = DiagnosticContext(),
    )

    private val httpClient = http
    private val appContext = context.applicationContext
    private val dataSourceFactory = YoutubeHttpDataSource.Factory(
        http,
        Constants.userAgent(),
    )
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(10_000)
        .setRangeParameterEnabled(true)
        .setRnParameterEnabled(true)
        .setMediaRequests(mediaRequests)

    /** Atomic swap; readers snapshot the map once. */
    @Volatile private var streamInfo: Map<String, StreamInfo> = emptyMap()
    /** Serializes read-modify-write swaps on [streamInfo] across NanoHTTPD threads. */
    private val streamInfoLock = Any()
    private val lastRefreshMs = ConcurrentHashMap<String, Long>()
    private val configGen = AtomicLong()

    @Volatile var urlRefresher: UrlRefresher? = null
    private data class PublishedManifest(val xml: String, val videoId: String?, val marker: String)
    @Volatile private var manifest: PublishedManifest? = null
    @Volatile private var videoTitle: String? = null
    @Volatile private var lastLinkPeerSeenMs = 0L
    /** Unlisted path prefix. LAN clients must have the shared URL; the port alone is not sufficient. */
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
        videoRequestPlan: MediaRequestPlan? = null,
        audioRequestPlan: MediaRequestPlan? = null,
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
                requestPlan = videoRequestPlan,
                diagnostic = diagnosticContext.copy(videoId = videoId, generation = configId),
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
                requestPlan = audioRequestPlan,
                diagnostic = diagnosticContext.copy(videoId = videoId, generation = configId),
            )
        }
        this.videoTitle = videoTitle
        // Publish tokens under the lock before the manifest so concurrent serveManifest never
        // exposes unregistered /stream tokens; an unlocked replace could interleave with 403
        // refresh / content-length backfill and revert a just-refreshed entry.
        synchronized(streamInfoLock) {
            // New generation: previous video's cooldowns must not mute this config's first 403 refresh.
            lastRefreshMs.clear()
            this.streamInfo = tokens
            val marker = manifest?.takeIf { it.videoId == videoId }?.marker
                ?: UUID.randomUUID().toString()
            this.manifest = manifestXml?.let { PublishedManifest(it, videoId, marker) }
        }
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
            // Past readFully's 512 KiB window the index can never be found; don't burn an upstream request.
            ?: SIDX_READ_CAP_BYTES.takeIf { info.indexEnd + 1 <= it }
                ?.let { fetchSidx(info, 0L, info.indexEnd + 1) }
    }

    private fun fetchSidx(info: StreamInfo, start: Long, length: Long): ChunkIndex? {
        val operation = AppLog.operation(AppLog.Category.CAST, "sidx", info.diagnostic.child(), mapOf("itag" to info.itag, "range_start" to start, "length" to length))
        val source = DiagnosticDataSource((info.requestPlan?.let { plan ->
            YoutubeHttpDataSource.Factory(httpClient, plan.userAgent)
                .setMediaRequests(mediaRequests, mediaRequests?.plan(info.youtubeUrl) ?: plan)
                .setRangeParameterEnabled(true)
        } ?: dataSourceFactory).createDataSource(), operation.context, category = AppLog.Category.CAST)
        return try {
            val spec = DataSpec.Builder()
                .setUri(info.youtubeUrl.toUri())
                .setPosition(start)
                .setLength(length)
                .build()
            source.open(spec)
            val bytes = readFully(source, length.coerceAtMost(SIDX_READ_CAP_BYTES).toInt())
            SidxParser.parse(bytes, start).also { operation.finish(if (it != null) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILURE, if (it != null) "parsed" else "sidx_unavailable") }
        } catch (t: Throwable) {
            operation.finish(DiagnosticOutcome.FAILURE, "sidx_failed", t)
            null
        } finally {
            runCatching { source.close() }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val expectedHost = if (listeningPort == 80) advertisedHost else "$advertisedHost:$listeningPort"
        val host = header(session, "host")?.trim()
        if (!expectedHost.equals(host, ignoreCase = true) &&
            !(listeningPort == 80 && "$advertisedHost:80".equals(host, ignoreCase = true))
        ) return text(Response.Status.FORBIDDEN, "Forbidden")
        AppLog.detail(AppLog.Category.CAST, "proxy_request", mapOf("method" to session.method.name), diagnosticContext)
        val raw = session.uri ?: return text(Response.Status.NOT_FOUND, "Not found")
        val prefix = "/$accessKey"
        if (!raw.startsWith(prefix)) return text(Response.Status.FORBIDDEN, "Forbidden")
        val uri = raw.substring(prefix.length).ifEmpty { "/" }
        if (session.method == Method.OPTIONS) {
            return corsPreflight(header(session, "user-agent"))
        }
        // NanoHTTPD writes a body for HEAD unless given an empty stream; keep GET's status/mime/length/CORS.
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
        // Browser-UA manifest fetch is the link-cast liveness probe; curl/scanner probes must not light the banner.
        if (!isReceiverUa(ua) && isLinkPeerUa(ua)) lastLinkPeerSeenMs = SystemClock.elapsedRealtime()
        val published = manifest ?: return text(Response.Status.NOT_FOUND, "No manifest")
        var xml = published.xml
        if (!published.videoId.isNullOrBlank()) {
            val marker = "<!-- vid:${published.marker} -->"
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
        // Same liveness gate as [serveManifest]: finding the unlisted /player URL alone must not light the banner.
        if (!isReceiverUa(ua) && isLinkPeerUa(ua)) lastLinkPeerSeenMs = SystemClock.elapsedRealtime()
        return try {
            val raw = appContext.assets.open("cast/player.html").use { it.readBytes() }
            var html = String(raw, Charsets.UTF_8)
            val locale = ConfigurationCompat.getLocales(appContext.resources.configuration)[0]?.toLanguageTag() ?: "en"
            val direction = if (appContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
            html = html.replace("__VIDEO_TITLE__", Markup.html(videoTitle ?: appContext.getString(R.string.player)))
                .replace("__LANGUAGE__", Markup.html(locale))
                .replace("__DIRECTION__", direction)
                .replace("__SOURCE_ERROR__", Markup.jsString(appContext.getString(R.string.web_source_unavailable)))
                .replace("__PLAYBACK_ERROR__", Markup.jsString(appContext.getString(R.string.player_error)))
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
        var openEndedRange = false
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
                openEndedRange = parsed[1] == LENGTH_UNSET
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
        val openEnded = !fromSeg && (openEndedRange || requestLength == LENGTH_UNSET)
        // A known total caps every request at the last byte: a stale segment index must not
        // promise bytes the upstream will never deliver (body would end short of Content-Length).
        val servedLength = when {
            fromSeg -> if (info.contentLength > 0) {
                minOf(requestLength, info.contentLength - rangeStart)
            } else {
                requestLength
            }
            else -> {
                // Bare GET / open-ended Range: whole remaining representation — Kodi treats an
                // artificially capped body as EOF. Explicit finite ranges retain the chunk cap.
                if (!hasRange || openEnded) {
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
        } catch (e: InvalidResponseCodeException) {
            if (e.responseCode == 403) {
                val now = SystemClock.elapsedRealtime()
                val last = lastRefreshMs[token] ?: 0L
                if (now - last >= REFRESH_COOLDOWN_MS) {
                    lastRefreshMs[token] = now
                    val fresh = urlRefresher?.refreshUrl(
                        token, info.publishedVideoId, info.itag,
                    )
                    if (fresh != null) {
                        // Re-read + swap under the lock: concurrent content-length backfill must not be lost to this stale snapshot.
                        val refreshed = synchronized(streamInfoLock) {
                            val current = streamInfo[token]
                                ?: return text(Response.Status.FORBIDDEN, "Stale token")
                            // Reconfigure raced this request: the table belongs to another video; don't corrupt it.
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
                            if (recoverable(t)) {
                                val failedPlan = mediaRequests?.plan(refreshed.youtubeUrl) ?: refreshed.requestPlan
                                val backup = urlRefresher?.backupUrl(token, info.publishedVideoId, info.itag, failedPlan?.client?.name)
                                if (backup != null) {
                                    val alternate = synchronized(streamInfoLock) {
                                        val latest = streamInfo[token]?.takeIf { it.configId == info.configId }
                                            ?: return text(Response.Status.FORBIDDEN, "Stale token")
                                        latest.copy(youtubeUrl = backup, requestPlan = mediaRequests?.plan(backup) ?: latest.requestPlan).also { streamInfo = streamInfo + (token to it) }
                                    }
                                    return try { streamUpstream(token, alternate, chunkStart, servedLength, hasRange, fromSeg, totalSize, ua, openEnded) }
                                    catch (_: Throwable) { text(Response.Status.FORBIDDEN, "Recovery exhausted") }
                                }
                            }
                            AppLog.event(AppLog.Category.CAST, "proxy_retry_failed", mapOf("itag" to info.itag, "range_start" to chunkStart), t, context = info.diagnostic)
                            text(Response.Status.INTERNAL_ERROR, "Proxy error")
                        }
                    }
                }
            }
            // Only a real upstream 403 answers 403; other codes map to the closest HTTP semantic
            // so the receiver can calibrate (416) or retry (5xx).
            if (e.responseCode == 429) {
                val limited = newFixedLengthResponse(object : Response.IStatus {
                    override fun getRequestStatus() = 429
                    override fun getDescription() = "429 Too Many Requests"
                }, MIME_PLAINTEXT, "Upstream rate limit")
                e.headerFields.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                    ?.let { limited.addHeader("Retry-After", it) }
                return limited
            }
            val status = when (e.responseCode) {
                416 -> Response.Status.RANGE_NOT_SATISFIABLE
                in 500..599 -> Response.Status.SERVICE_UNAVAILABLE
                else -> Response.Status.FORBIDDEN
            }
            text(status, "Upstream ${e.responseCode}")
        } catch (t: Throwable) {
            if (recoverable(t)) {
                val fresh = urlRefresher?.refreshUrl(token, info.publishedVideoId, info.itag)
                if (fresh != null) {
                    val updated = synchronized(streamInfoLock) {
                        val latest = streamInfo[token]?.takeIf { it.configId == info.configId }
                            ?: return text(Response.Status.FORBIDDEN, "Stale token")
                        latest.copy(youtubeUrl = fresh, requestPlan = mediaRequests?.plan(fresh) ?: latest.requestPlan).also { streamInfo = streamInfo + (token to it) }
                    }
                    return try { streamUpstream(token, updated, chunkStart, servedLength, hasRange, fromSeg, totalSize, ua, openEnded) }
                    catch (_: Throwable) { text(Response.Status.FORBIDDEN, "Recovery exhausted") }
                }
            }
            AppLog.event(AppLog.Category.CAST, "proxy_fetch_failed", mapOf("itag" to info.itag, "range_start" to chunkStart), t, context = info.diagnostic)
            text(Response.Status.INTERNAL_ERROR, "Proxy error")
        }
    }

    private fun recoverable(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any {
        it is InvalidResponseCodeException && it.responseCode == 403
            || it.message in setOf("MEDIA_SESSION_CHANGED", "MEDIA_OBJECT_CHANGED", "MEDIA_URL_EXPIRED")
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
        // Open-ended, no known total: leave upstream length unset — googlevideo clips its view
        // to the &range= window, so the resolved length reveals the true remaining size.
        val upstreamUnbounded = openEnded && info.contentLength <= 0
        val specBuilder = DataSpec.Builder()
            .setUri(info.youtubeUrl.toUri())
            .setPosition(chunkStart)
        if (!upstreamUnbounded) specBuilder.setLength(servedLength)
        val source = DiagnosticDataSource((info.requestPlan?.let { plan ->
            YoutubeHttpDataSource.Factory(httpClient, plan.userAgent)
                .setMediaRequests(mediaRequests, mediaRequests?.plan(info.youtubeUrl) ?: plan)
                .setRangeParameterEnabled(true)
        } ?: dataSourceFactory).createDataSource(), info.diagnostic, category = AppLog.Category.CAST)
        val resolved = source.open(specBuilder.build())
        val rangeTotal = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
            ?.value?.firstOrNull()?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > chunkStart }
        if (info.contentLength <= 0 && rangeTotal != null) {
            maybeBackfillContentLength(token, info, 0, rangeTotal)
        }
        if (upstreamUnbounded) {
            if (resolved == 0L) {
                runCatching { source.close() }
                return text(Response.Status.RANGE_NOT_SATISFIABLE, "Range beyond end of stream")
            }
            maybeBackfillContentLength(token, info, chunkStart, resolved)
        }
        // Never claim more bytes than upstream delivers; a bare GET with no total uses the resolved length.
        val bodyLength = when {
            upstreamUnbounded && servedLength == LENGTH_UNSET -> resolved
            upstreamUnbounded && resolved in 1 until servedLength -> resolved
            else -> servedLength
        }
        val total = when {
            totalSize > 0 -> totalSize
            rangeTotal != null -> rangeTotal
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
            // ?seg= URLs are standalone SegmentList resources: 200 OK — 206 + Content-Range confuses the receiver.
            response = newFixedLengthResponse(Response.Status.OK, mime, input, bodyLength)
        } else if (hasRange) {
            response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT, mime, input, bodyLength,
            )
            response.addHeader(
                "Content-Range",
                "bytes $chunkStart-${chunkStart + bodyLength - 1}/${if (total > 0) total.toString() else "*"}",
            )
        } else {
            response = newFixedLengthResponse(Response.Status.OK, mime, input, bodyLength)
        }
        response.addHeader("Accept-Ranges", "bytes")
        addCors(response, ua)
        return response
    }

    /**
     * googlevideo clips its view to the &range= window, so an open-ended window's resolved
     * length reveals the true total. Backfilling lets later requests serve precise chunk ends
     * and Content-Range totals. Trust the first resolved length only for a token with no total.
     */
    private fun maybeBackfillContentLength(token: String, info: StreamInfo, chunkStart: Long, resolved: Long) {
        if (resolved <= 0L) return
        synchronized(streamInfoLock) {
            val current = streamInfo[token] ?: return
            // Raced a reconfigure: this length belongs to the previous video's URL.
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
     * Chromecast default receiver is cross-origin and needs CORS. Do not advertise `*` for
     * arbitrary browser UAs — a leaked proxy URL would otherwise be readable via fetch().
     */
    private fun addCors(response: Response, ua: String?) {
        if (!isReceiverUa(ua)) return
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
    }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body)

    private fun readFully(source: androidx.media3.datasource.DataSource, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = source.read(buf, 0, minOf(buf.size, max - total))
            // A 0 return (media3 non-blocking sources) would spin forever, neither progress nor EOF.
            if (n == 0) break
            if (n == C.RESULT_END_OF_INPUT || n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private class DataSourceStream(
        private val source: androidx.media3.datasource.DataSource,
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
         * NanoHTTPD 2.3.1 Response.send() swallows IOException without closing the response
         * InputStream, leaving [source] — and its OkHttp connection — open until GC. Finalizer
         * is last-resort reclaim (Cleaner needs API 33, minSdk 26).
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
        private const val TAG = "LocalStreamProxy"
        private const val LOOPBACK = "127.0.0.1"
        // media3's C.LENGTH_UNSET is int -1; DataSpec lengths use the long form.
        private const val LENGTH_UNSET: Long = C.LENGTH_UNSET.toLong()
        private const val DEFAULT_CHUNK = 256 * 1024
        private const val LINK_PEER_CHUNK = 2 * 1024 * 1024
        /** DASH media-segment span, not the whole file. */
        private const val MAX_SEG_BYTES = 16L * 1024 * 1024
        private const val SIDX_READ_CAP_BYTES = 512L * 1024
        private const val REFRESH_COOLDOWN_MS = 30_000L
        private const val LINK_PEER_TIMEOUT_MS = 30_000L
        private val RECEIVER_UAS = arrayOf("chromecast", "crkey", "castsdk")

        private fun isReceiverUa(ua: String?): Boolean {
            if (ua == null) return false
            val lower = ua.lowercase()
            return RECEIVER_UAS.any { lower.contains(it) }
        }

        /** Loose browser check: dash.js link-cast receivers always send one. */
        private fun isLinkPeerUa(ua: String?): Boolean =
            ua?.lowercase()?.contains("mozilla") == true

        /** Stream URLs stamp `g=` with the config id; a mismatch is an old manifest and must not be served. */
        internal fun acceptsGeneration(requestGeneration: Long?, configId: Long): Boolean =
            requestGeneration != null && requestGeneration == configId

        /** Cast receivers stall on large 200 OK bodies; browser link-cast needs larger slices (256 KiB underruns). */
        internal fun chunkMaxBytes(ua: String?): Int =
            if (isReceiverUa(ua)) DEFAULT_CHUNK else LINK_PEER_CHUNK

        /** HEAD: empty body, same status/mime/length/headers — GET's Content-Length is the resource size, not the empty body. */
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
         * RFC 7233 `bytes=start-end`. Suffix ranges need a known content length; without one
         * they are unsatisfiable, not silently turned into `0-N`. Open-ended end is [LENGTH_UNSET].
         */
        internal fun parseRange(header: String, contentLength: Long): LongArray? {
            val spec = header.removePrefix("bytes=").trim()
            // Multi-range specs are rejected outright (caller answers 416); taking the first dash would fake an open-ended 206.
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
         * LAN IPv4 to bind, or null when none exists. Loopback is not a fallback: a 127.0.0.1
         * URL is unreachable from the receiver, so the caller refuses link-cast instead.
         */
        fun resolveBindHost(context: Context, receiverHost: String? = null): String? =
            detectLanIp(context, receiverHost).takeIf { it != LOOPBACK }

        private fun detectLanIp(context: Context, receiverHost: String?): String {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val localNetwork = LocalCastNetwork.select(cm, receiverHost)
                cm?.getLinkProperties(localNetwork)?.linkAddresses?.firstOrNull {
                    it.address is Inet4Address && !it.address.isLoopbackAddress
                }?.address?.hostAddress?.let { return it }
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
