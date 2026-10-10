package com.hhst.youtubelite.cast

import com.hhst.youtubelite.diagnostics.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.hhst.youtubelite.cast.protocol.CastV2Discovery
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.player.engine.CastSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

data class CastDevice(
    val id: String,
    val name: String,
    val selected: Boolean,
)

data class CastUiState(
    val available: Boolean = false,
    val chromecastSession: Boolean = false,
    val deviceName: String? = null,
    val devices: List<CastDevice> = emptyList(),
    val linkUrl: String? = null,
    val linkConnected: Boolean = false,
)

/** Open-source Cast V2 sender with the existing LAN DASH proxy. */
@UnstableApi
class CastController(
    private val appContext: Context,
    private val http: OkHttpClient,
    private val mediaRequests: YoutubeMediaRequests? = null,
    private val sessionFactory: (String, Int, CastV2Session.Listener) -> CastV2Session =
        { host, port, listener -> CastV2Session.forDefaultMediaReceiver(host, port, listener) },
) {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "CastSidx").apply { isDaemon = true } }
    private val listeningIo = MoreExecutors.listeningDecorator(io)
    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()
    @Volatile private var proxy: LocalStreamProxy? = null
    private var castPlayer: CastRemotePlayer? = null
    private var currentSession: CastV2Session? = null
    private var discovery: CastV2Discovery? = null
    private var discovering = false
    private data class Endpoint(val name: String, val host: String, val port: Int)
    private val endpoints = linkedMapOf<String, Endpoint>()
    private var selectedId: String? = null
    private var sessionGeneration = 0L
    private var diagnosticTrace: CastDiagnosticTrace? = null
    private val configureGen = AtomicLong()
    private val configureLock = Any()
    @Volatile private var proxyConfiguredVideoId: String? = null
    private var chromecastActive = false
    private var discoveryUserEnabled = false
    private var loadingUpgrade = false
    var urlRefresher: LocalStreamProxy.UrlRefresher? = null
        set(value) { field = value; proxy?.urlRefresher = value }
    var onSessionStarted: (() -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null
    var onSessionFailed: (() -> Unit)? = null

    fun initialize(): Boolean {
        if (discovery == null) discovery = CastV2Discovery(appContext)
        applyDiscovery()
        return true
    }
    fun remotePlayer(): Player? = if (chromecastActive) castPlayer else null
    fun setDiscoveryEnabled(enabled: Boolean) { discoveryUserEnabled = enabled; applyDiscovery() }
    private fun applyDiscovery() {
        val discovery = discovery ?: return
        val active = discoveryUserEnabled || chromecastActive
        if (active == discovering) return
        discovering = active
        AppLog.event(AppLog.Category.CAST, "discovery_state", mapOf("active" to active))
        if (active) {
            endpoints.clear()
            publishRoutes()
            discovery.start { name, host, port ->
                val id = "$host:$port"
                endpoints[id] = Endpoint(name, host, port)
                AppLog.event(AppLog.Category.CAST, "device_resolved", mapOf("device_name" to name, "host" to host, "port" to port))
                publishRoutes()
            }
        } else discovery.stop()
    }
    fun publishRoutes() {
        _state.update { it.copy(available = endpoints.isNotEmpty(), devices = endpoints.map { (id, e) ->
            CastDevice(id, e.name, id == selectedId)
        }) }
    }
    fun selectDevice(id: String) {
        val endpoint = endpoints[id] ?: return
        endSession()
        val generation = ++sessionGeneration
        val trace = CastDiagnosticTrace().also { diagnosticTrace = it }
        selectedId = id
        var connected = false
        lateinit var session: CastV2Session
        val listener = object : CastV2Session.Listener {
            override fun onConnected() { main.post {
                if (generation != sessionGeneration) return@post
                connected = true
                trace.phase("session_connected")
                currentSession = session
                castPlayer = CastRemotePlayer(session)
                _state.update { it.copy(chromecastSession = true, deviceName = endpoint.name) }
                publishRoutes()
                onSessionStarted?.invoke()
            } }
            override fun onMediaStatus(raw: String?, position: Long, duration: Long, idleReason: String?) { main.post {
                if (generation == sessionGeneration) castPlayer?.status(raw, position, duration, idleReason)
            } }
            override fun onLaunchError(reason: String) { trace.error("receiver_launch_failed", null); failed() }
            override fun onChannelError(reason: String) { trace.error("channel_failed", null); failed() }
            override fun onLoadFailed(type: String) { main.post {
                if (generation == sessionGeneration && !loadingUpgrade) finishSession(true, connected)
            } }
            override fun onClosed() { main.post {
                if (generation == sessionGeneration) finishSession(false, connected)
            } }
            private fun failed() { main.post {
                if (generation == sessionGeneration) finishSession(true, connected)
            } }
        }
        session = sessionFactory(endpoint.host, endpoint.port, listener)
        session.setDiagnostics(trace)
        currentSession = session
        publishRoutes()
        session.start()
    }
    private fun finishSession(failed: Boolean, hadSession: Boolean) {
        val session = currentSession
        sessionGeneration++
        currentSession = null
        selectedId = null
        _state.update { it.copy(chromecastSession = false, deviceName = null) }
        if (hadSession) onSessionEnded?.invoke() else if (failed) onSessionFailed?.invoke()
        stopCasting()
        castPlayer = null
        session?.close()
        publishRoutes()
    }
    fun endSession() {
        if (currentSession != null) finishSession(false, _state.value.chromecastSession)
    }
    fun startCasting(source: CastSource, positionMs: Long): Boolean {
        val player = castPlayer ?: return false
        val session = currentSession ?: return false
        chromecastActive = true
        applyDiscovery()
        val p = (proxy ?: freshProxy()) ?: run { stopCasting(); return false }
        if (!configureProxy(p, source)) { stopCasting(); return false }
        scheduleSidxUpgradeAndReceiverLoad(p, session, player, source, positionMs)
        return true
    }
    fun stopCasting(): Long {
        val position = castPlayer?.currentPosition ?: 0L
        chromecastActive = false
        configureGen.incrementAndGet()
        applyDiscovery()
        castPlayer?.reset()
        stopProxy()
        return position
    }

    fun ensureProxy(source: CastSource): String? {
        // A live cast session owns the proxy for its own video only; reconfiguring for another
        // source would retarget the receiver's segment requests (garbled bytes / 403). Refuse
        // link-cast during a session instead.
        if (chromecastActive) {
            val p = proxy ?: return null
            return if (proxyConfiguredVideoId == source.videoId) p.proxyUrl("/player") else null
        }
        val p = proxy ?: createProxy() ?: return null
        if (!configureProxy(p, source)) return null
        _state.update { it.copy(linkUrl = p.proxyUrl("/player"), linkConnected = p.isLinkPeerConnected()) }
        return p.proxyUrl("/player")
    }

    fun refreshLinkBanner() {
        val p = proxy ?: return
        _state.update { it.copy(linkConnected = p.isLinkPeerConnected(), linkUrl = p.proxyUrl("/player")) }
    }

    fun stopLink() = stopProxy()

    private fun stopProxy() {
        runCatching { proxy?.stop() }
        proxy = null
        proxyConfiguredVideoId = null
        _state.update { it.copy(linkUrl = null, linkConnected = false) }
    }

    /** Fresh proxy per cast session. Stored on [proxy] so teardown and the sidx identity check see it. */
    private fun freshProxy(): LocalStreamProxy? {
        stopProxy()
        return createProxy()
    }

    private fun createProxy(): LocalStreamProxy? {
        val host = LocalStreamProxy.resolveBindHost(appContext) ?: return null
        return runCatching {
            LocalStreamProxy(appContext, http, advertisedHost = host, mediaRequests = mediaRequests).also {
                it.urlRefresher = urlRefresher
                it.start()
                proxy = it
            }
        }.onFailure {
            AppLog.event(AppLog.Category.CAST, "proxy_start_failed", failure = it, context = diagnosticTrace?.context)
            proxy = null
        }.getOrNull()
    }

    private fun configureProxy(p: LocalStreamProxy, source: CastSource): Boolean {
        diagnosticTrace?.video(source.videoId, configureGen.get())
        p.diagnosticContext = diagnosticTrace?.context ?: DiagnosticContext(videoId = source.videoId)
        val generation = p.allocateGeneration()
        val manifest = CastManifest.build(
            video = source.video,
            audio = source.audio,
            durationMs = source.durationSec * 1000,
            proxyBase = p.proxyUrl(""),
            generation = generation,
        ) ?: return false
        synchronized(configureLock) {
            configureGen.incrementAndGet()
            applySource(p, manifest, source, generation)
        }
        return true
    }

    /** Single configure site: applies the manifest and stream metadata to [p]. */
    private fun applySource(
        p: LocalStreamProxy,
        manifestXml: String,
        source: CastSource,
        generation: Long,
    ) {
        p.configure(
            manifestXml = manifestXml,
            videoTitle = source.title,
            videoId = source.videoId,
            videoUrl = source.video?.url,
            videoMime = source.video?.mimeType,
            audioUrl = source.audio?.url,
            audioMime = source.audio?.mimeType,
            video = source.video?.ranges(),
            audio = source.audio?.ranges(),
            videoItag = source.video?.itag,
            audioItag = source.audio?.itag,
            generation = generation,
            videoRequestPlan = source.video?.requestPlan,
            audioRequestPlan = source.audio?.requestPlan,
        )
        proxyConfiguredVideoId = source.videoId
    }

    /**
     * Sidx wait → load → upgrade protocol. The default receiver
     * cannot parse sidx, so a SegmentList manifest is required for segment
     * addressing; SegmentBase loads immediately when sidx is slow so the TV
     * still starts, then gets reloaded once SegmentList becomes available.
     */
    private fun scheduleSidxUpgradeAndReceiverLoad(
        p: LocalStreamProxy,
        session: CastV2Session,
        player: CastRemotePlayer,
        source: CastSource,
        positionMs: Long,
    ) {
        val gen = configureGen.get()
        io.execute {
            val vFuture = listeningIo.submit(Callable { p.fetchChunkIndex("v") })
            val aFuture = listeningIo.submit(Callable { p.fetchChunkIndex("a") })
            var sidxReady = false
            try {
                Futures.whenAllComplete(vFuture, aFuture).call(Callable {
                    Futures.getDone(vFuture); Futures.getDone(aFuture)
                }, MoreExecutors.directExecutor()).get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                sidxReady = true
            } catch (_: TimeoutException) {
                Log.w(TAG, "sidx fetch timed out, loading SegmentBase first")
            } catch (e: Exception) {
                Log.w(TAG, "sidx fetch failed", e)
            }

            val vci = runCatching { Futures.getDone(vFuture) }.getOrNull()
            val aci = runCatching { Futures.getDone(aFuture) }.getOrNull()
            val firstLoadSegmentList = sidxReady && (vci != null || aci != null)
            main.post {
                if (this.proxy !== p || gen != configureGen.get()) return@post
                if (firstLoadSegmentList) {
                    upgradeToSegmentList(p, source, vci, aci, expectedGen = gen)
                }
                loadOnReceiver(session, player, p.proxyUrl("/manifest.mpd"), source, positionMs)
            }

            if (sidxReady) return@execute

            // SegmentBase was loaded; keep waiting and reload with SegmentList.
            try {
                Futures.whenAllComplete(vFuture, aFuture).call(Callable {
                    Futures.getDone(vFuture); Futures.getDone(aFuture)
                }, MoreExecutors.directExecutor()).get(SIDX_RELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                Log.w(TAG, "sidx reload timed out, staying on SegmentBase")
                return@execute
            } catch (e: ExecutionException) {
                Log.w(TAG, "sidx reload failed", e)
                return@execute
            }
            val vReload = runCatching { Futures.getDone(vFuture) }.getOrNull()
            val aReload = runCatching { Futures.getDone(aFuture) }.getOrNull()
            if (this.proxy !== p || gen != configureGen.get()) return@execute
            if (!upgradeToSegmentList(p, source, vReload, aReload, expectedGen = gen)) return@execute
            main.post {
                if (this.proxy !== p || gen != configureGen.get()) return@post
                val reloadPos = runCatching { player.currentPosition }.getOrDefault(positionMs)
                loadOnReceiver(
                    session, player, p.proxyUrl("/manifest.mpd"), source, reloadPos,
                    upgrade = true,
                )
            }
        }
    }

    /** Rebuilds the manifest with per-segment URLs and hot-swaps it in. */
    private fun upgradeToSegmentList(
        p: LocalStreamProxy,
        source: CastSource,
        vci: ChunkIndex?,
        aci: ChunkIndex?,
        expectedGen: Long,
    ): Boolean {
        if (vci == null && aci == null) return false
        // Default receiver cannot parse sidx: every track that exists must
        // have a SegmentList, otherwise the other half is silent.
        if (source.video != null && !CastManifest.usableSegmentIndex(vci)) return false
        if (source.audio != null && !CastManifest.usableSegmentIndex(aci)) return false
        val generation = p.currentConfigId()
        val manifest = CastManifest.build(
            video = source.video,
            audio = source.audio,
            durationMs = source.durationSec * 1000,
            proxyBase = p.proxyUrl(""),
            videoIndex = vci,
            audioIndex = aci,
            generation = generation,
        ) ?: return false
        // Re-check under the lock: a main-thread configureProxy may have
        // landed between the caller's gen check and this swap.
        synchronized(configureLock) {
            if (proxy !== p || expectedGen != configureGen.get()) return false
            applySource(p, manifest, source, generation)
        }
        return true
    }

    private fun loadOnReceiver(
        session: CastV2Session, player: CastRemotePlayer, manifestUrl: String,
        source: CastSource, positionMs: Long, upgrade: Boolean = false,
    ) {
        if (currentSession !== session || !chromecastActive) return
        loadingUpgrade = upgrade && player.playbackState == Player.STATE_READY
        player.loading(manifestUrl, source, positionMs)
        session.load(manifestUrl, MimeTypes.APPLICATION_MPD, source.title, source.thumbnailUrl, positionMs.coerceAtLeast(0), source.author)
    }

    private fun Format.ranges() = LocalStreamProxy.StreamRanges(
        indexStart = indexStart.toLong(),
        indexEnd = indexEnd.toLong(),
        // Byte length is unknown (approxDurationMs is milliseconds); -1 keeps
        // the proxy from emitting a bogus Content-Range total.
        contentLength = -1L,
    )

    companion object {
        private const val TAG = "CastController"
        private const val SIDX_FETCH_TIMEOUT_MS = 15_000L
        private const val SIDX_RELOAD_TIMEOUT_MS = 15_000L
    }
}
