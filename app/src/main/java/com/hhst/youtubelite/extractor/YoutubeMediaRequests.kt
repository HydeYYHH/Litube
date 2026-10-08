package com.hhst.youtubelite.extractor

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import com.hhst.youtubelite.downloader.net.DownloadRangeParser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import java.io.IOException

/** Shared media request construction and bounded compatibility feedback, injected into consumers. */
class YoutubeMediaRequests(
    private val current: (YoutubeSession) -> Boolean,
    private val diagnostics: ExtractionDiagnostics,
) {
    // Issued CDN URLs survive page-configuration refreshes; only account or route changes end a read.
    constructor(sessions: YoutubeSessionProvider, diagnostics: ExtractionDiagnostics) : this(sessions::isMediaCurrent, diagnostics)
    private val plans = LinkedHashMap<String, RequestPlan>(128, .75f, true)
    private val post = LinkedHashSet<String>()
    private val cancellation = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "youtube-media-cancellation").apply { isDaemon = true }
    }

    @Synchronized fun register(url: String, plan: RequestPlan) {
        plans[normalized(url)] = plan
        while (plans.size > 512) { val first = plans.keys.first(); plans.remove(first); post.remove(first) }
    }
    @Synchronized fun plan(url: String): RequestPlan? = plans[normalized(url)]
    fun isCurrent(plan: RequestPlan): Boolean = current(plan.session)
    private fun normalized(url: String) = url.toHttpUrl().newBuilder().removeAllQueryParameters("range").removeAllQueryParameters("rn").build().toString()

    fun build(url: String, plan: RequestPlan, position: Long = 0, length: Long = -1,
              extraHeaders: Map<String, String> = emptyMap(), range: RequestPlan.Range = plan.range): Request {
        if (!current(plan.session)) throw IOException("MEDIA_SESSION_CHANGED")
        if (plan.expiresAtMillis <= System.currentTimeMillis()) throw IOException("MEDIA_URL_EXPIRED")
        if (position < 0 || length == 0L) throw IOException("MEDIA_RANGE_INVALID")
        var limit = if (length < 0) plan.chunkLimit else minOf(length, plan.chunkLimit)
        if (plan.resourceLength > 0) limit = minOf(limit, (plan.resourceLength - position).coerceAtLeast(0))
        if (range != RequestPlan.Range.NONE && limit <= 0) throw IOException("MEDIA_READ_POSITION_OUT_OF_RANGE")
        val builder = Request.Builder().url(url).tag(RequestPlan::class.java, plan)
        plan.headers.forEach { (name, value) -> builder.header(name, value) }
        extraHeaders.filterKeys { it.lowercase() !in setOf("authorization", "cookie", "user-agent", "range") }
            .forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", plan.userAgent).header("Accept-Encoding", "identity")
        val cookies = plan.cookies(url)
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies)
        if (range != RequestPlan.Range.NONE && limit > 0) {
            val end = position + limit - 1
            if (end < position) throw IOException("MEDIA_RANGE_OVERFLOW")
            if (range == RequestPlan.Range.QUERY) {
                builder.url(url.toHttpUrl().newBuilder().removeAllQueryParameters("range")
                    .addQueryParameter("range", "$position-$end").build())
            } else builder.header("Range", "bytes=$position-$end")
        }
        val learnedPost = synchronized(this) { normalized(url) in post }
        return if (learnedPost && plan.allowPostFallback) builder.post(byteArrayOf(0x78, 0).toRequestBody()).build()
            else builder.get().build()
    }

    fun interceptor() = Interceptor { chain ->
        val initial = chain.request()
        val plan = initial.tag(RequestPlan::class.java) ?: plan(initial.url.toString()) ?: return@Interceptor chain.proceed(initial)
        if (!current(plan.session)) throw IOException("MEDIA_SESSION_CHANGED")
        var request = initial.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
            .header("User-Agent", plan.userAgent).apply {
                plan.headers.forEach { (k, v) -> header(k, v) }
                val cookie = plan.cookies(initial.url.toString())
                if (cookie.isNotEmpty()) header("Cookie", cookie)
            }.build()
        var redirects = 0
        fun exchange(first: Request): Response {
            request = first
            while (true) {
                if (!current(plan.session)) throw IOException("MEDIA_SESSION_CHANGED")
                val response = chain.proceed(request)
                if (response.code !in setOf(301, 302, 303, 307, 308)) return response
                var target = response.header("Location")?.let { response.request.url.resolve(it) }
                val requestedRange = request.url.queryParameter("range")
                if (requestedRange != null && target?.queryParameter("range") == null && target?.encodedPath?.startsWith("/videoplayback") == true) target = target.newBuilder().addQueryParameter("range", requestedRange).build()
                response.close()
                if (++redirects > 5 || target == null) throw IOException("MEDIA_REDIRECT_LIMIT")
                if (!target.isHttps) throw IOException("MEDIA_INSECURE_REDIRECT")
                request = request.newBuilder().url(target).removeHeader("Cookie").removeHeader("Authorization")
                    .apply { val cookie = plan.cookies(target.toString()); if (cookie.isNotEmpty()) header("Cookie", cookie) }.build()
            }
        }
        val started = System.nanoTime()
        var response = exchange(request)
        if (response.code == 403 && request.method == "GET" && plan.allowPostFallback && request.url.encodedPath.startsWith("/videoplayback")) {
            response.close()
            response = exchange(request.newBuilder().post(byteArrayOf(0x78, 0).toRequestBody()).build())
            if (response.isSuccessful) synchronized(this) { post.add(normalized(initial.url.toString())) }
            diagnostics.event("media", plan.profile.name, "GET_POST_COMPATIBILITY", (System.nanoTime() - started) / 1_000_000, response.code)
        }
        if (!current(plan.session)) { response.close(); throw IOException("MEDIA_SESSION_CHANGED") }
        diagnostics.event("media", plan.profile.name, plan.protocol.name, (System.nanoTime() - started) / 1_000_000, response.code)
        val mediaType = response.header("Content-Type").orEmpty().lowercase()
        if (response.isSuccessful && (mediaType.startsWith("text/html") || mediaType.startsWith("application/json"))) {
            response.close(); throw IOException("MEDIA_ERROR_BODY")
        }
        if (!response.isSuccessful) return@Interceptor response
        val window = try { window(response, plan) } catch (failure: IOException) { response.close(); throw failure }
        val body = response.body ?: return@Interceptor response
        val changed = AtomicBoolean()
        val abort = cancellation.scheduleAtFixedRate({
            if (!current(plan.session)) { changed.set(true); chain.call().cancel() }
        }, 100, 100, TimeUnit.MILLISECONDS)
        val guarded = object : ResponseBody() {
            private val input = object : ForwardingSource(body.source()) {
                private var received = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (changed.get()) throw IOException("MEDIA_SESSION_CHANGED")
                    val count = try { super.read(sink, byteCount) } catch (failure: IOException) {
                        if (changed.get()) throw IOException("MEDIA_SESSION_CHANGED", failure)
                        throw failure
                    }
                    if (changed.get()) throw IOException("MEDIA_SESSION_CHANGED")
                    if (count < 0 && window.expectedBytes >= 0 && received != window.expectedBytes) throw IOException("MEDIA_EARLY_EOF")
                    if (count > 0) {
                        if (received == 0L) diagnostics.event("read", plan.profile.name, "FIRST_BYTES", (System.nanoTime() - started) / 1_000_000, response.code)
                        received += count
                        if (window.expectedBytes >= 0 && received > window.expectedBytes) throw IOException("MEDIA_LENGTH_MISMATCH")
                    }
                    return count
                }
                override fun close() { try { super.close() } finally { abort.cancel(false) } }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = input
        }
        response.newBuilder().body(guarded).build()
    }

    data class Window(val expectedBytes: Long, val total: Long?)

    /** Shared range interpretation for player, download and proxy, including query-cropped views. */
    fun window(response: Response, plan: RequestPlan): Window {
        val query = response.request.url.queryParameter("range")
        val raw = query ?: response.request.header("Range")?.removePrefix("bytes=")
            ?: if (plan.range == RequestPlan.Range.NONE) return Window(response.body?.contentLength() ?: -1, null)
            else throw IOException("MEDIA_RANGE_MISSING")
        val bounds = raw.split('-')
        val start = bounds.getOrNull(0)?.toLongOrNull() ?: throw IOException("MEDIA_RANGE_INVALID")
        val end = bounds.getOrNull(1)?.toLongOrNull() ?: throw IOException("MEDIA_RANGE_INVALID")
        val wanted = end - start + 1
        if (wanted <= 0 || wanted > plan.chunkLimit) throw IOException("MEDIA_RANGE_INVALID")
        val cr = DownloadRangeParser.parse(response.header("Content-Range"))
        val length = response.body?.contentLength() ?: -1
        val cropped = query != null && cr?.start == 0L && cr.total == cr.end + 1 && cr.total <= wanted
        if (response.code == 206 && cr == null) throw IOException("MEDIA_RANGE_MISSING")
        if (cr != null && !cropped && (cr.start != start || cr.end < cr.start || cr.end > end)) throw IOException("MEDIA_RANGE_MISMATCH")
        val count = cr?.let { it.end - it.start + 1 } ?: length
        // Range.NONE names the whole object. A player-added Range header is optional, so a 200
        // without Content-Range is that object and the caller skips to the requested offset.
        val wholeObject = response.code == 200 && query == null && cr == null && plan.range == RequestPlan.Range.NONE
        if (count == 0L || (!wholeObject && (count > wanted || length > wanted))) throw IOException("MEDIA_RANGE_IGNORED")
        if (response.code == 200 && query == null && cr == null && plan.resourceLength > 0 && length != plan.resourceLength) throw IOException("MEDIA_OBJECT_CHANGED")
        if (!wholeObject && response.code == 200 && query == null && cr == null && (start != 0L || length < 0 || length > wanted)) throw IOException("MEDIA_RANGE_IGNORED")
        if (cr != null && length >= 0 && length != count) throw IOException("MEDIA_LENGTH_MISMATCH")
        val total = if (cropped) null else cr?.total
        if (plan.resourceLength > 0 && total != null && total != plan.resourceLength) throw IOException("MEDIA_OBJECT_CHANGED")
        return Window(count, plan.resourceLength.takeIf { it > 0 } ?: total)
    }
}
