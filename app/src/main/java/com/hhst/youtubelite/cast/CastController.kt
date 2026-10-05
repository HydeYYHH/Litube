package com.hhst.youtubelite.cast

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.common.images.WebImage
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.player.engine.CastSource
import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

/**
 * Chromecast + LAN link-cast.
 *
 * The receiver load protocol:
 * 1. Start the proxy and apply a SegmentBase manifest synchronously (no
 *    network — it only needs the extractor's init/index ranges).
 * 2. Fetch both sidx boxes off-thread (15 s budget).
 * 3. When ready (or timed out), load on the receiver via RemoteMediaClient,
 *    preferring a SegmentList manifest (the Chromecast default receiver
 *    cannot parse sidx itself).
 * 4. If the first load used SegmentBase and sidx lands later (another 15 s),
 *    rebuild as SegmentList and reload the receiver at its live position.
 */
@UnstableApi
class CastController(
    private val appContext: Context,
    private val http: OkHttpClient,
    private val mediaRequests: YoutubeMediaRequests? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "CastSidx").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    @Volatile private var proxy: LocalStreamProxy? = null
    private var castPlayer: CastPlayer? = null
    private var currentSession: CastSession? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null
    private var castStateListener: CastStateListener? = null
    private var routerCallback: MediaRouter.Callback? = null
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null

    /** Monotonic configure generation: superseded manifest builds are dropped. */
    private val configureGen = AtomicLong()
    /**
     * Serializes gen-bump + manifest application. Without it the background
     * sidx-upgrade path's gen check (io thread) and its [applySource] are two
     * steps a main-thread [configureProxy] can slip between, letting the OLD
     * source's SegmentList overwrite the newer configuration.
     */
    private val configureLock = Any()
    /** Video id the current proxy was last configured with; see [ensureProxy]. */
    @Volatile private var proxyConfiguredVideoId: String? = null
    private var chromecastActive = false
    /** User request from the cast dialog; a live session always keeps discovery on. */
    @Volatile private var discoveryUserEnabled = false

    var urlRefresher: LocalStreamProxy.UrlRefresher? = null
        set(value) {
            field = value
            proxy?.urlRefresher = value
        }

    var onSessionStarted: (() -> Unit)? = null
    /** TV disconnected after a live session — hand its position back to local. */
    var onSessionEnded: (() -> Unit)? = null
    /** Start/resume never established a receiver position; do not seek local playback. */
    var onSessionFailed: (() -> Unit)? = null

    fun initialize(activity: Activity): Boolean {
        // Idempotent: the screen calls this on every activity recreate.
        if (castPlayer != null) return _state.value.available
        val ok = runCatching {
            val ctx = CastContext.getSharedInstance(activity)
            val player = CastPlayer.Builder(appContext).build()
            player.addListener(object : Player.Listener {
                override fun onPlayerErrorChanged(error: PlaybackException?) {
                    if (error != null) {
                        Log.w(TAG, "cast player error: ${error.errorCodeName}", error)
                    }
                }
            })
            castPlayer = player
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    setSession(session)
                    onSessionStarted?.invoke()
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    currentSession = null
                    _state.update { it.copy(chromecastSession = false, deviceName = null) }
                    // Always run the teardown path; the VM captures the last
                    // position and resumes local playback.
                    onSessionEnded?.invoke()
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    setSession(session)
                }
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    Log.w(TAG, "onSessionStartFailed error=$error")
                    onCastFailed()
                }
                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    Log.w(TAG, "onSessionResumeFailed error=$error")
                    onCastFailed()
                }
                override fun onSessionStarting(session: CastSession) = Unit
                override fun onSessionEnding(session: CastSession) = Unit
                override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            }
            sessionListener = listener
            ctx.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            // The Cast route provider only populates MediaRouter routes once
            // its first discovery scan completes (several seconds); the state
            // listener fires exactly then, so device lists appear promptly.
            val stateListener = CastStateListener { publishRoutes() }
            castStateListener = stateListener
            ctx.addCastStateListener(stateListener)
            startDiscovery()
            true
        }.getOrDefault(false)
        _state.update { it.copy(available = ok) }
        return ok
    }

    private fun setSession(session: CastSession) {
        currentSession = session
        _state.update {
            it.copy(chromecastSession = true, deviceName = session.castDevice?.friendlyName)
        }
    }

    private fun onCastFailed() {
        // A dead session must not survive as [currentSession]: the next
        // startCasting would run against it, its remoteMediaClient is null,
        // and playback black-holes behind the CastPlayer fallback.
        currentSession = null
        _state.update { it.copy(chromecastSession = false, deviceName = null) }
        onSessionFailed?.invoke()
    }

    // -- link casting (browser via LAN URL) --

    fun ensureProxy(source: CastSource): String? {
        // A live Chromecast session owns the proxy for its own video ONLY.
        // Reconfiguring for a different source would retarget the receiver's
        // segment requests (garbled bytes / 403) while the UI still reports
        // the previous cast; link-cast during a session is refused instead.
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

    // -- chromecast session --

    fun startCasting(source: CastSource, positionMs: Long): Boolean {
        val player = castPlayer ?: return false
        val session = currentSession ?: return false
        chromecastActive = true
        applyDiscovery()
        val p = (proxy ?: freshProxy()) ?: run {
            stopCasting()
            return false
        }
        if (!configureProxy(p, source)) {
            stopCasting()
            return false
        }
        scheduleSidxUpgradeAndReceiverLoad(
            proxy = p,
            session = session,
            player = player,
            source = source,
            positionMs = positionMs,
        )
        return true
    }

    fun stopCasting(): Long {
        chromecastActive = false
        applyDiscovery()
        val position = runCatching { castPlayer?.currentPosition ?: 0L }.getOrDefault(0L)
        runCatching {
            castPlayer?.clearMediaItems()
        }
        stopProxy()
        return position
    }

    /** Ends the Cast session; onSessionEnded performs the teardown. */
    fun endSession() {
        runCatching {
            CastContext.getSharedInstance(appContext).sessionManager.endCurrentSession(true)
        }
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
            Log.w(TAG, "createProxy failed", it)
            proxy = null
        }.getOrNull()
    }

    fun remotePlayer(): Player? = if (chromecastActive) castPlayer else null

    /**
     * Lifecycle contract with the UI: enable while the cast dialog is open
     * (`enabled = true`) and disable again when it closes. Registration without
     * the REQUEST_DISCOVERY flag is passive, so background audio playback never
     * keeps the router scanning. Idempotent; call from the main thread.
     */
    fun setDiscoveryEnabled(enabled: Boolean) {
        discoveryUserEnabled = enabled
        applyDiscovery()
        publishRoutes()
    }

    /** Re-registers the router callback with or without active discovery. */
    private fun applyDiscovery() {
        val router = mediaRouter ?: return
        val callback = routerCallback ?: return
        val selector = routeSelector ?: return
        // A live cast session keeps discovery on even if the dialog closed.
        val active = discoveryUserEnabled || chromecastActive
        router.removeCallback(callback)
        if (active) {
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        } else {
            router.addCallback(selector, callback)
        }
    }

    fun selectDevice(id: String) {
        val router = mediaRouter ?: return
        val route = router.routes.firstOrNull { it.id == id } ?: return
        router.selectRoute(route)
    }

    // -- manifest / proxy configuration --

    private fun configureProxy(p: LocalStreamProxy, source: CastSource): Boolean {
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
        proxy: LocalStreamProxy,
        session: CastSession,
        player: CastPlayer,
        source: CastSource,
        positionMs: Long,
    ) {
        val gen = configureGen.get()
        val p = proxy
        CompletableFuture.runAsync({
            val vFuture = CompletableFuture.supplyAsync({ p.fetchChunkIndex("v") }, io)
            val aFuture = CompletableFuture.supplyAsync({ p.fetchChunkIndex("a") }, io)
            var sidxReady = false
            try {
                CompletableFuture.allOf(vFuture, aFuture).get(SIDX_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                sidxReady = true
            } catch (_: TimeoutException) {
                Log.w(TAG, "sidx fetch timed out, loading SegmentBase first")
            } catch (e: Exception) {
                Log.w(TAG, "sidx fetch failed", e)
            }

            val vci = runCatching { vFuture.getNow(null) }.getOrNull()
            val aci = runCatching { aFuture.getNow(null) }.getOrNull()
            val firstLoadSegmentList = sidxReady && (vci != null || aci != null)
            main.post {
                if (this.proxy !== p || gen != configureGen.get()) return@post
                if (firstLoadSegmentList) {
                    upgradeToSegmentList(p, source, vci, aci, expectedGen = gen)
                }
                loadOnReceiver(session, player, p.proxyUrl("/manifest.mpd"), source, positionMs)
            }

            if (sidxReady) return@runAsync

            // SegmentBase was loaded; keep waiting and reload with SegmentList.
            try {
                CompletableFuture.allOf(vFuture, aFuture).get(SIDX_RELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                Log.w(TAG, "sidx reload timed out, staying on SegmentBase")
                return@runAsync
            } catch (e: ExecutionException) {
                Log.w(TAG, "sidx reload failed", e)
                return@runAsync
            }
            val vReload = runCatching { vFuture.getNow(null) }.getOrNull()
            val aReload = runCatching { aFuture.getNow(null) }.getOrNull()
            if (this.proxy !== p || gen != configureGen.get()) return@runAsync
            if (!upgradeToSegmentList(p, source, vReload, aReload, expectedGen = gen)) return@runAsync
            main.post {
                if (this.proxy !== p || gen != configureGen.get()) return@post
                val reloadPos = runCatching { player.currentPosition }.getOrDefault(positionMs)
                loadOnReceiver(
                    session, player, p.proxyUrl("/manifest.mpd"), source, reloadPos,
                    upgrade = true,
                )
            }
        }, io)
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

    /**
     * RemoteMediaClient is the canonical load path (CastPlayer.setMediaItem
     * routes through it too, but loses the author/subtitle metadata); the
     * CastPlayer fallback still works when the client is not yet attached.
     */
    private fun loadOnReceiver(
        session: CastSession,
        player: CastPlayer,
        manifestUrl: String,
        source: CastSource,
        positionMs: Long,
        upgrade: Boolean = false,
    ) {
        // Ownership: captured at entry (main-thread gen-guarded posts call
        // this), so the result callback can tell its load from a newer one.
        val gen = configureGen.get()
        val client: RemoteMediaClient? = runCatching { session.remoteMediaClient }.getOrNull()
        if (client == null) {
            Log.w(TAG, "RemoteMediaClient null, falling back to CastPlayer")
            val item = MediaItem.Builder()
                .setUri(manifestUrl)
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(source.title)
                        .setArtist(source.author)
                        .build(),
                )
                .build()
            // Unlike the RemoteMediaClient path below, a failed fallback load
            // has no result callback: unwind through a listener instead, or a
            // dead receiver keeps "casting" with the local player paused.
            val errorListener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) player.removeListener(this)
                }

                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    main.post {
                        if (chromecastActive && gen == configureGen.get()) endSession()
                    }
                }
            }
            player.addListener(errorListener)
            player.setMediaItem(item, positionMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true
            return
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, source.title)
            source.author?.takeIf { it.isNotBlank() }?.let {
                putString(MediaMetadata.KEY_SUBTITLE, it)
            }
            // Public i.ytimg.com URL: the default receiver fetches artwork
            // itself, no proxying or CORS involved.
            source.thumbnailUrl?.let { url ->
                runCatching { addImage(WebImage(Uri.parse(url))) }
            }
        }
        val mediaInfo = MediaInfo.Builder(manifestUrl)
            .setContentType(MimeTypes.APPLICATION_MPD)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .build()
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(positionMs.coerceAtLeast(0L))
                .build(),
        )
            .setResultCallback { result ->
                if (!result.status.isSuccess) {
                    Log.w(TAG, "RemoteMediaClient.load FAILED code=${result.status.statusCode}")
                    // A failed first load must unwind; a SegmentList upgrade
                    // failure must not kill an already-playing SegmentBase session.
                    if (!upgrade) {
                        main.post {
                            if (chromecastActive && gen == configureGen.get()) endSession()
                        }
                    }
                }
            }
    }

    private fun Format.ranges() = LocalStreamProxy.StreamRanges(
        indexStart = indexStart.toLong(),
        indexEnd = indexEnd.toLong(),
        // Byte length is unknown (approxDurationMs is milliseconds); -1 keeps
        // the proxy from emitting a bogus Content-Range total.
        contentLength = -1L,
    )

    // -- discovery --

    private fun startDiscovery() {
        val router = MediaRouter.getInstance(appContext)
        mediaRouter = router
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()
        routeSelector = selector
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
                publishRoutes()

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) =
                publishRoutes()

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) =
                publishRoutes()

            override fun onRouteSelected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishRoutes()
        }
        routerCallback = callback
        // Passive registration: no REQUEST_DISCOVERY flag, so a background
        // process never keeps the router scanning. [setDiscoveryEnabled]
        // upgrades to active scanning while the cast dialog is open;
        // applyDiscovery() also honors an enable request that arrived before
        // initialization.
        router.addCallback(selector, callback)
        applyDiscovery()
        publishRoutes()
    }

    fun publishRoutes() {
        val router = mediaRouter ?: return
        val selector = routeSelector
        val selected = router.selectedRoute
        // Only REMOTE cast routes: MediaRouter
        // lists Bluetooth A2DP and wired routes too, and selecting one is not
        // casting.
        val devices = router.routes
            .filter {
                it.isEnabled && !it.isDefault &&
                    it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE &&
                    (selector == null || it.matchesSelector(selector))
            }
            .map { CastDevice(id = it.id, name = it.name, selected = it.id == selected.id) }
        val castState = runCatching {
            CastContext.getSharedInstance(appContext).castState
        }.getOrDefault(CastState.NO_DEVICES_AVAILABLE)
        _state.update {
            it.copy(
                devices = devices,
                available = castState != CastState.NO_DEVICES_AVAILABLE || devices.isNotEmpty(),
            )
        }
    }

    companion object {
        private const val TAG = "CastController"
        private const val SIDX_FETCH_TIMEOUT_MS = 15_000L
        private const val SIDX_RELOAD_TIMEOUT_MS = 15_000L
    }
}
