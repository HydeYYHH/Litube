package com.hhst.youtubelite.extractor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticNetwork
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.WebView
import com.google.common.util.concurrent.SettableFuture

class YoutubeExtractionHost(
    private val sessions: YoutubeSessionProvider,
    private val http: OkHttpClient,
    private val solver: SignatureSolver,
    private val tokens: PoTokenMinter,
    val diagnostics: ExtractionDiagnostics,
    val plans: YoutubeMediaRequests,
    private val browserResponses: BrowserPlayerResponses,
    private val clientOrder: AdaptiveClientOrder,
    private val playback: MscPlayback = MscPlayback(solver, tokens),
) : PlaybackHost {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "youtube-cancellation").apply { isDaemon = true } }
    private data class CachedStream(val stream: Stream, val expires: Long, val bytes: Int, val session: YoutubeWebSession)
    private val streams = LinkedHashMap<String, CachedStream>(16, .75f, true)
    private val json = Json
    private data class Refresh(val session: SettableFuture<YoutubeWebSession>, var at: Long)
    private val refreshes = LinkedHashMap<String, Refresh>()

    private fun refreshedSession(videoId: String, deadline: Long, current: () -> Boolean): YoutubeWebSession {
        val key = videoId + ":" + sessions.recoveryScope()
        var owner = false
        val refresh = synchronized(refreshes) {
            refreshes.entries.removeAll { it.value.session.isDone && System.currentTimeMillis() - it.value.at >= 10_000 }
            refreshes[key] ?: Refresh(SettableFuture.create(), System.currentTimeMillis()).also {
                if (refreshes.size >= 128) throw IOException("RECOVERY_CAPACITY")
                refreshes[key] = it; owner = true
            }
        }
        diagnostics.event("recovery", "ENGINE", if (owner) "FULL_REFRESH_OWNER" else "FULL_REFRESH_JOIN", 0, 0)
        if (owner) try {
            val snapshot = sessions.captureBounded(videoId, true, deadline, current)
            if (!sessions.isCurrent(snapshot)) throw IOException("SESSION_CHANGED_OR_CANCELLED")
            tokens.invalidateAll()
            refresh.session.set(snapshot)
        } catch (failure: Throwable) { refresh.session.setException(failure) }
        finally { synchronized(refreshes) { refresh.at = System.currentTimeMillis() } }
        while (true) {
            if (!current() || System.nanoTime() >= deadline) throw IOException("EXTRACTION_DEADLINE")
            try { return refresh.session.get(100, TimeUnit.MILLISECONDS).also { if (!sessions.isCurrent(it)) throw IOException("SESSION_CHANGED_OR_CANCELLED") } }
            catch (_: TimeoutException) { }
            catch (failure: ExecutionException) { throw (failure.cause as? IOException ?: IOException("SESSION_REFRESH_FAILED", failure.cause)) }
        }
    }
    override fun scopeHint(): String = sessions.captureStamp()
    override fun recoveryScope(): String = sessions.recoveryScope()
    override fun installBrowserCapture(view: WebView, generation: () -> Long) = browserResponses.install(view, generation)
    override fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean): MscPlayback.Resolved =
        playback.resolve(videoId, context, demand, fresh, deferredClients(videoId))
    override fun chapters(videoId: String, context: ExtractionContext): List<Chapter> = playback.chapters(videoId, context)
    override fun extractionDiagnostics(): String = diagnostics.export()
    override fun invalidateStream(videoId: String) = synchronized(streams) {
        streams.keys.removeAll { it.contains(":" + videoId + ":") }
        Unit
    }

    override fun deferredClients(videoId: String): Set<StreamClient> =
        clientOrder.refusedFor(videoId, sessions.scopeHint())

    override fun noteMediaRejected(videoId: String, url: String, reason: MediaRejection, diagnosticContext: DiagnosticContext?): StreamClient? {
        val plan = runCatching { plans.plan(url) }.getOrNull() ?: return null
        if (!sessions.isMediaCurrent(plan.session)) return null
        clientOrder.mediaForbidden(videoId, sessions.scopeHint(), plan.client)
        AppLog.event(AppLog.Category.EXTRACTOR,
            "client_media_rejected", mapOf("client" to plan.client.name, "reason" to reason.code,
                "defer_ms" to 30_000), context = diagnosticContext)
        return plan.client
    }

    private fun cachedSession(videoId: String, catalog: Boolean, demand: PlaybackDemand?): YoutubeWebSession? {
        val suffix = ":$videoId:$catalog:${demand?.cacheKey() ?: "0:null:false:"}"
        val candidate = synchronized(streams) {
            streams.entries.firstOrNull { it.key.endsWith(suffix) && it.value.expires > System.currentTimeMillis() }
                ?.value?.session
        }
        return candidate?.takeIf(sessions::isCurrent)
    }

    override fun context(videoId: String, fresh: Boolean, catalog: Boolean, demand: PlaybackDemand?, taskCurrent: () -> Boolean, deadlineNanos: Long, playbackPriority: Boolean, diagnosticContext: DiagnosticContext): ExtractionContext {
        val scopedDiagnostics = diagnostics.scoped(diagnosticContext)
        val deadline = minOf(deadlineNanos, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        if (deadline <= System.nanoTime() || !taskCurrent()) throw IOException("EXTRACTION_DEADLINE")
        val started = System.nanoTime()
        val cached = if (fresh) null else cachedSession(videoId, catalog, demand)
        val session = try { cached ?: if (fresh) refreshedSession(videoId, deadline, taskCurrent)
            else (if (!catalog) sessions.captureNative(deadline, taskCurrent) else null)
                ?: sessions.captureBounded(videoId, false, deadline, taskCurrent) } catch (failure: IOException) {
            scopedDiagnostics.event("page", "WEB", "CAPTURE_FAILED", (System.nanoTime() - started) / 1_000_000,
                (failure as? PageHttpException)?.status ?: 0)
            throw failure
        }
        scopedDiagnostics.event("session", session.account.name, if (cached != null) "stream-session-cache" else session.configurationSource, (System.nanoTime() - started) / 1_000_000, 200)
        val created = ExtractionContext(
            session = session,
            transport = transport(),
            live = false,
            catalog = catalog,
            playbackPriority = playbackPriority,
            demandKey = demand?.cacheKey() ?: "",
            deadlineNanos = deadline,
            current = { sessions.isCurrent(session) && taskCurrent() },
            diagnostics = scopedDiagnostics,
            webConfiguration = { original ->
                val prepared = sessions.captureBounded(videoId, false, original.deadlineNanos, original.current)
                if (prepared.account != WebAccount.ANONYMOUS) throw IOException("SESSION_ACCOUNT_CHANGED")
                original.withSession(prepared) { sessions.isCurrent(prepared) }.also {
                    diagnostics.bind(it, diagnosticContext)
                }
            },
        )
        diagnostics.bind(created, diagnosticContext)
        return created
    }

    private fun transport(): ExtractionTransport = object : ExtractionTransport {
        override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) =
            exchange(context, "GET", url, headers, null)

        override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray) =
            exchange(context, "POST", url, headers, body)

        private fun exchange(context: ExtractionContext, method: String, url: String, headers: Map<String, List<String>>, body: ByteArray?): PageResponse {
            context.check()
            if (method == "POST") {
                browserResponses.get(url, method, body?.toString(Charsets.UTF_8), context.session)?.let {
                    diagnostics.forContext(context).event("player", "WEB", "browser-response-cache", 0, 200)
                    return PageResponse(200, emptyMap(), it, url)
                }
            }
            val builder = okhttp3.Request.Builder().url(url)
            headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
            if (body != null) {
                val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
                builder.post(body.toRequestBody(type?.let { runCatching { it.toMediaType() }.getOrNull() }))
            } else builder.get()
            val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val request = builder.build()
            val call = client.newCall(ExtractionDiagnostics.diagnosticContext(context)
                ?.let { DiagnosticNetwork.tag(request, it) } ?: request)
            val requestStarted = System.nanoTime()
            val remaining = minOf(context.remainingMillis().coerceAtLeast(0), 10_000)
            if (remaining <= 0) throw IOException("REQUEST_DEADLINE")
            call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
            val cancel = watchdog.scheduleAtFixedRate({
                if (System.nanoTime() >= context.deadlineNanos || runCatching { context.check() }.isFailure) call.cancel()
            }, 100, 100, TimeUnit.MILLISECONDS)
            try {
                return call.execute().use { response ->
                    val source = response.body?.source() ?: throw IOException("EMPTY_RESPONSE")
                    val target = URI(url)
                    val isPage = target.scheme == "https" && target.host == "www.youtube.com" && target.path in setOf("/", "/watch")
                    val limit = if (url.contains("/s/player/") || isPage) 8L * 1024 * 1024 else 2L * 1024 * 1024
                    if (source.request(limit + 1)) throw IOException("RESPONSE_TOO_LARGE")
                    val text = source.readUtf8()
                    context.check()
                    val path = target.path.orEmpty()
                    val kind = when {
                        path.startsWith("/s/player/") -> "player-source"
                        path == "/watch" || path == "/" -> "page"
                        path.startsWith("/youtubei/") -> "innertube"
                        else -> "token-transport"
                    }
                    diagnostics.forContext(context).event("request", kind, method, (System.nanoTime() - requestStarted) / 1_000_000, response.code)
                    PageResponse(response.code, response.headers.toMultimap(), text, response.request.url.toString())
                }
            } finally { cancel.cancel(false) }
        }
    }

    private val initializer = Executors.newSingleThreadExecutor { Thread(it, "youtube-init").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val initQueued = AtomicBoolean()
    private val warmQueued = AtomicBoolean()
    @Volatile private var initialized = ""
    @Volatile private var initFailed = ""
    @Volatile private var initFailedAt = 0L

    init { sessions.onBrowserConfiguration = ::scheduleInitialize }

    /** Coalesces browser configuration bursts into one background initialization. */
    fun scheduleInitialize() {
        if (initQueued.compareAndSet(false, true)) initializer.execute {
            initQueued.set(false)
            runCatching { initialize() }
        }
    }

    override fun initialize(): Boolean {
        val session = sessions.captureCached() ?: return false
        val playerUrl = session.playerUrl ?: return false
        val now = System.currentTimeMillis()
        val key = session.key + playerUrl
        if (initialized == key) return true
        // A failed speculative compile must not repeat on every browser configuration message.
        if (initFailed == key && now - initFailedAt < INIT_RETRY_MS) return false
        val context = context(videoId = "", fresh = false, catalog = true, demand = null,
            taskCurrent = { true }, deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20),
            playbackPriority = false, diagnosticContext = DiagnosticContext(videoId = ""))
        return solver.initialize(playerUrl, context).also {
            if (it) initialized = key else { initFailed = key; initFailedAt = System.currentTimeMillis() }
        }
    }

    /**
     * Captures this identity's web configuration while browsing. Direct clients need
     * no player script; fallbacks init their solver on demand, not at startup.
     */
    override fun warm(videoId: String) {
        if (videoId.isBlank()) return
        if (!warmQueued.compareAndSet(false, true)) return
        initializer.execute {
            try {
                sessions.capture(videoId)
            } catch (_: Throwable) {
                // Speculative only: playback retries the same capture on the critical path.
            } finally {
                warmQueued.set(false)
            }
        }
    }

    /** Cache view scoped to the task's session, account and demand. */
    override fun cache(inner: Cache, context: () -> ExtractionContext): Cache = object : Cache {
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
        private fun streamKey(id: String) = key(id) + ":" + context().catalog + ":" + context().demandKey.ifEmpty { "0:null:false:" }
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
                    diagnostics.forContext(active).event("stream-cache", "ENGINE", if (it == null) "MISS" else "HIT", 0, 0)
                }
            }
        }
        override fun putStream(videoId: String, stream: Stream) {
            val active = context()
            val key = streamKey(videoId)
            synchronized(streams) {
                active.check()
                val now = System.currentTimeMillis()
                // Signed URLs carry their own `expire`, the real reuse limit: a cached stream
                // dies exactly when its media URLs stop working. A flat two-minute ceiling
                // discarded still-valid streams and forced full re-resolution.
                val signed = (stream.formats.mapNotNull { it.expiresAtMillis ?: urlExpiry(it.url) } +
                    stream.manifests.mapNotNull { urlExpiry(it.url) } +
                    listOfNotNull(stream.hlsUrl?.let(::urlExpiry), stream.dashUrl?.let(::urlExpiry)))
                    .minOrNull()
                val expiration = signed ?: (now + STREAM_FALLBACK_MS)
                val bytes = json.encodeToString(stream).toByteArray().size +
                    json.encodeToString(stream.manifests).toByteArray().size
                streams[key] = CachedStream(stream, expiration - 30_000, bytes, active.session)
                streams.entries.removeAll { it.value.expires <= now }
                var totalBytes = streams.values.sumOf { it.bytes }
                while (totalBytes > 8 * 1024 * 1024 && streams.isNotEmpty()) {
                    val first = streams.keys.first()
                    totalBytes -= streams.remove(first)!!.bytes
                }
            }
        }
        private fun urlExpiry(url: String?): Long? = url
            ?.let { com.hhst.youtubelite.downloader.net.DownloadBitrate.queryParam(it, "expire") }
            ?.toLongOrNull()
            ?.let { it * 1000L }

        override fun invalidateStream(videoId: String) {
            val key = streamKey(videoId)
            synchronized(streams) { streams.remove(key) }
        }
    }

    private companion object {
        /** Only for streams whose URLs expose no `expire`: previously the ceiling for every entry. */
        const val STREAM_FALLBACK_MS = 120_000L
        const val INIT_RETRY_MS = 10 * 60_000L
    }
}
