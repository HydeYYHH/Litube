package com.hhst.youtubelite.extractor

import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture
import java.net.URI
import android.webkit.WebView
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import org.schabi.newpipe.extractor.services.youtube.streams.StreamHttpException
import org.schabi.newpipe.extractor.services.youtube.streams.StreamDemand
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Host scheduling, scoped caches, bounded transport and cancellation. */
class YoutubeExtractionHost(
    private val sessions: YoutubeSessionProvider,
    private val http: OkHttpClient,
    private val solver: AndroidChallengeSolver,
    private val tokens: PoTokenProvider,
    val diagnostics: ExtractionDiagnostics,
    val plans: YoutubeMediaRequests,
    private val browserResponses: BrowserPlayerResponses,
) {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "youtube-cancellation").apply { isDaemon = true } }
    private data class CachedStream(val stream: Stream, val expires: Long, val bytes: Int)
    private val streams = LinkedHashMap<String, CachedStream>(16, .75f, true)
    private val gson = Gson()
    private data class Refresh(val session: CompletableFuture<YoutubeSession>, var at: Long)
    private val refreshes = LinkedHashMap<String, Refresh>()
    private val clientOrder = AdaptiveClientOrder()

    private fun refreshedSession(videoId: String, deadline: Long, current: () -> Boolean): YoutubeSession {
        val key = videoId + ":" + sessions.recoveryScope()
        var owner = false
        val refresh = synchronized(refreshes) {
            refreshes.entries.removeAll { it.value.session.isDone && System.currentTimeMillis() - it.value.at >= 10_000 }
            refreshes[key] ?: Refresh(CompletableFuture(), System.currentTimeMillis()).also {
                if (refreshes.size >= 128) throw IOException("RECOVERY_CAPACITY")
                refreshes[key] = it; owner = true
            }
        }
        diagnostics.event("recovery", "ENGINE", if (owner) "FULL_REFRESH_OWNER" else "FULL_REFRESH_JOIN", 0, 0)
        if (owner) try {
            val snapshot = sessions.captureBounded(videoId, true, deadline, current)
            if (!sessions.isCurrent(snapshot)) throw IOException("SESSION_CHANGED_OR_CANCELLED")
            tokens.invalidateAll()
            refresh.session.complete(snapshot)
        } catch (failure: Throwable) { refresh.session.completeExceptionally(failure) }
        finally { synchronized(refreshes) { refresh.at = System.currentTimeMillis() } }
        while (true) {
            if (!current() || System.nanoTime() >= deadline) throw IOException("EXTRACTION_DEADLINE")
            try { return refresh.session.get(100, TimeUnit.MILLISECONDS).also { if (!sessions.isCurrent(it)) throw IOException("SESSION_CHANGED_OR_CANCELLED") } }
            catch (_: TimeoutException) { }
            catch (failure: ExecutionException) { throw (failure.cause as? IOException ?: IOException("SESSION_REFRESH_FAILED", failure.cause)) }
        }
    }
    fun scopeHint(): String = sessions.captureStamp()
    fun recoveryScope(): String = sessions.recoveryScope()
    fun installBrowserCapture(view: WebView, generation: () -> Long) = browserResponses.install(view, generation)
    fun invalidateStream(videoId: String) = synchronized(streams) {
        streams.keys.removeAll { it.contains(":" + videoId + ":") }
        Unit
    }

    private fun cachedSession(videoId: String, catalog: Boolean, demand: StreamDemand?): YoutubeSession? {
        val suffix = ":$videoId:$catalog:${demand?.cacheKey() ?: "0:null:false:"}"
        val candidate = synchronized(streams) {
            streams.entries.firstOrNull { it.key.endsWith(suffix) && it.value.expires > System.currentTimeMillis() }
                ?.value?.stream?.let { stream -> stream.formats.firstNotNullOfOrNull { it.requestPlan?.session }
                    ?: stream.manifests.firstOrNull()?.requestPlan?.session }
        }
        return candidate?.takeIf(sessions::isCurrent)
    }

    fun context(videoId: String, fresh: Boolean, catalog: Boolean = false, demand: StreamDemand? = null, taskCurrent: () -> Boolean = { true }, deadlineNanos: Long = Long.MAX_VALUE, playbackPriority: Boolean = !catalog): ExtractionContext {
        val deadline = minOf(deadlineNanos, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        if (deadline <= System.nanoTime() || !taskCurrent()) throw IOException("EXTRACTION_DEADLINE")
        val started = System.nanoTime()
        val cached = if (fresh) null else cachedSession(videoId, catalog, demand)
        val session = try { cached ?: if (fresh) refreshedSession(videoId, deadline, taskCurrent) else sessions.captureBounded(videoId, false, deadline, taskCurrent) } catch (failure: IOException) {
            diagnostics.event("page", "WEB", "CAPTURE_FAILED", (System.nanoTime() - started) / 1_000_000,
                (failure as? StreamHttpException)?.status ?: 0)
            throw failure
        }
        diagnostics.event("session", session.account.name, if (cached != null) "stream-session-cache" else session.configurationSource, (System.nanoTime() - started) / 1_000_000, 200)
        lateinit var context: ExtractionContext
        val transport = transport(fresh) { context }
        val configuration = session.configuration()
        // Page content hints apply only to that video; the common account/player
        // configuration can safely serve another video in the same session.
        val live = configuration.getString("_VIDEO_ID") == videoId && configuration.getBoolean("_IS_LIVE", false)
        context = ExtractionContext(session, transport, solver, tokens, live, catalog, deadline,
            { sessions.isCurrent(session) && taskCurrent() }, diagnostics)
            .withPlaybackPriority(playbackPriority).withClientOrder(clientOrder)
        if (demand != null) context = context.withDemand(demand)
        return context
    }

    private val initializer = Executors.newSingleThreadExecutor { Thread(it, "youtube-init").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val initQueued = AtomicBoolean()
    @Volatile private var initialized = ""
    @Volatile private var initFailed = ""
    @Volatile private var initFailedAt = 0L
    @Volatile private var preconnectedAt = 0L

    init { sessions.onBrowserConfiguration = ::scheduleInitialize }

    /** Coalesces browser configuration bursts into one background initialization. */
    fun scheduleInitialize() {
        if (initQueued.compareAndSet(false, true)) initializer.execute {
            initQueued.set(false)
            runCatching { initialize() }
        }
    }

    /**
     * Compiles the session's player solver and opens the YouTube and HLS manifest connections, so the
     * first selected video skips both. Uses only an already captured configuration; never reads a page.
     */
    fun initialize(): Boolean {
        val session = sessions.captureCached() ?: return false
        val playerUrl = session.playerUrl ?: return false
        val now = System.currentTimeMillis()
        if (now - preconnectedAt >= PRECONNECT_INTERVAL_MS) {
            preconnectedAt = now
            PRECONNECT_ORIGINS.forEach { origin -> runCatching { preconnect(origin, session.userAgent) } }
        }
        val key = session.key + playerUrl
        if (initialized == key) return true
        // A failed speculative compile must not repeat on every browser configuration message.
        if (initFailed == key && now - initFailedAt < INIT_RETRY_MS) return false
        lateinit var context: ExtractionContext
        context = ExtractionContext(session, transport(false) { context }, solver, tokens, false, true,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(20), { sessions.isCurrent(session) }, diagnostics)
            .withPlaybackPriority(false)
        return solver.initialize(playerUrl, context).also {
            if (it) initialized = key else { initFailed = key; initFailedAt = System.currentTimeMillis() }
        }
    }

    private fun preconnect(origin: String, userAgent: String) {
        val started = System.nanoTime()
        val call = http.newCall(okhttp3.Request.Builder().url("$origin/generate_204").head().header("User-Agent", userAgent).build())
        call.timeout().timeout(5, TimeUnit.SECONDS)
        call.execute().use { diagnostics.event("request", "preconnect", origin.substringAfter("//"), (System.nanoTime() - started) / 1_000_000, it.code) }
    }

    private fun transport(fresh: Boolean, contextOf: () -> ExtractionContext): Downloader = object : Downloader() {
        override fun execute(request: Request): Response {
            val context = contextOf()
            context.check()
            (if (fresh) null else browserResponses.get(request, context))?.let {
                diagnostics.event("player", "WEB", "browser-response-cache", 0, 200)
                return Response(200, "OK", emptyMap(), it, request.url())
            }
            val builder = okhttp3.Request.Builder().url(request.url())
            request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
            builder.method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val call = client.newCall(builder.build())
            val requestStarted = System.nanoTime()
            val remaining = minOf(context.remainingMillis(), (request.executionDeadlineNanos() - System.nanoTime()) / 1_000_000)
            if (remaining <= 0) throw IOException("REQUEST_DEADLINE")
            call.timeout().timeout(minOf(10_000, remaining), TimeUnit.MILLISECONDS)
            val cancel = watchdog.scheduleAtFixedRate({
                if (System.nanoTime() >= request.executionDeadlineNanos() || runCatching { context.check() }.isFailure) call.cancel()
            }, 100, 100, TimeUnit.MILLISECONDS)
            try {
                return call.execute().use { response ->
                    val source = response.body?.source() ?: throw IOException("EMPTY_RESPONSE")
                    val target = URI(request.url())
                    val isPage = target.scheme == "https" && target.host == "www.youtube.com" && target.path in setOf("/", "/watch")
                    val limit = if (request.url().contains("/s/player/") || isPage) 8L * 1024 * 1024 else 2L * 1024 * 1024
                    if (source.request(limit + 1)) throw IOException("RESPONSE_TOO_LARGE")
                    val body = source.readUtf8()
                    context.check()
                    val path = target.path.orEmpty()
                    val kind = when {
                        path.startsWith("/s/player/") -> "player-source"
                        path == "/watch" || path == "/" -> "page"
                        path.startsWith("/youtubei/") -> "innertube"
                        else -> "token-transport"
                    }
                    diagnostics.event("request", kind, request.httpMethod(), (System.nanoTime() - requestStarted) / 1_000_000, response.code)
                    Response(response.code, response.message, response.headers.toMultimap(), body, response.request.url.toString())
                }
            } finally { cancel.cancel(false) }
        }
    }

    fun cache(inner: Cache, context: () -> ExtractionContext): Cache = object : Cache {
        private fun key(id: String) = context().session.scope() + ":" + id
        private var accountSession = ""
        private var accountKey = ""
        @Synchronized private fun metadataKey(id: String): String {
            val session = context().session
            if (accountSession != session.key) {
                accountKey = sessions.accountScope(session)
                accountSession = session.key
            }
            return "$accountKey:$id"
        }
        private fun streamKey(id: String) = key(id) + ":" + context().catalog + ":" + context().demand.cacheKey()
        override fun getMetadata(videoId: String) = inner.getMetadata(metadataKey(videoId))
        override fun putMetadata(videoId: String, metadata: Metadata) { context().check(); inner.putMetadata(metadataKey(videoId), metadata) }
        override fun getChapters(videoId: String) = inner.getChapters(metadataKey(videoId))
        override fun putChapters(videoId: String, chapters: ChapterList) { context().check(); inner.putChapters(metadataKey(videoId), chapters) }
        override fun getStream(videoId: String): Stream? {
            // Lazy context creation itself checks this cache. Never wait for it while
            // holding the stream monitor: metadata may own the lazy initializer.
            val active = context()
            val key = streamKey(videoId)
            return synchronized(streams) {
                active.check()
                streams[key]?.takeIf { it.expires > System.currentTimeMillis() }?.stream.also {
                    diagnostics.event("stream-cache", "ENGINE", if (it == null) "MISS" else "HIT", 0, 0)
                }
            }
        }
        override fun putStream(videoId: String, stream: Stream) {
            val active = context()
            val key = streamKey(videoId)
            synchronized(streams) {
                active.check()
                val now = System.currentTimeMillis()
                val expiration = (stream.formats.mapNotNull { it.expiresAtMillis }
                    + stream.manifests.map { it.expiresAtMillis }).minOrNull() ?: now + 120_000
                val bytes = gson.toJson(stream).toByteArray().size + stream.manifests.sumOf { it.url.toByteArray().size }
                streams[key] = CachedStream(stream, minOf(now + 120_000, expiration - 30_000), bytes)
                streams.entries.removeAll { it.value.expires <= now }
                var totalBytes = streams.values.sumOf { it.bytes }
                while (totalBytes > 8 * 1024 * 1024 && streams.isNotEmpty()) {
                    val first = streams.keys.first()
                    totalBytes -= streams.remove(first)!!.bytes
                }
            }
        }
        override fun invalidateStream(videoId: String) {
            val key = streamKey(videoId)
            synchronized(streams) { streams.remove(key) }
        }
    }

    private companion object {
        val PRECONNECT_ORIGINS = listOf("https://www.youtube.com", "https://manifest.googlevideo.com")
        /** Below OkHttp's five-minute idle eviction, so a preconnected connection is still pooled. */
        const val PRECONNECT_INTERVAL_MS = 4 * 60_000L
        const val INIT_RETRY_MS = 10 * 60_000L
    }
}
