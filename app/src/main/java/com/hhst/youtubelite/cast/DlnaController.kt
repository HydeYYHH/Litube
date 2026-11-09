package com.hhst.youtubelite.cast

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.player.engine.CastSource
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class DlnaUiState(
    val available: Boolean = false,
    val session: Boolean = false,
    val deviceName: String? = null,
    val devices: List<CastDevice> = emptyList(),
    val failure: CastFailureReport? = null,
)

interface DlnaEngine {
    suspend fun search(timeoutMs: Long): List<DLNACast.Device>
    suspend fun cast(device: DLNACast.Device, url: String, title: String, mimeType: String): Boolean
    /** Confirm the newly opened media before publishing controls or sending a seek/pause. */
    suspend fun awaitMediaReady(url: String) = Unit
    suspend fun play(): Boolean
    suspend fun pause(): Boolean
    suspend fun stop(): Boolean
    suspend fun seek(positionMs: Long): Boolean
    suspend fun progress(): Pair<Long, Long>?
    suspend fun transportState(): DLNACast.PlaybackState
    fun clearProgressCache()
}

@UnstableApi
class DlnaController(
    private val appContext: Context,
    engine: DlnaEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val initLibrary: () -> Unit = { DLNACast.init(appContext) },
    private val playerFactory: (CoroutineScope, DlnaEngine, (Long) -> Unit) -> DlnaPlayerDelegate? =
        { s, e, reload -> DlnaRemotePlayer(s, e, reload) },
) {
    // Built lazily so the network-change callback can reach this instance.
    private val engine: DlnaEngine = engine ?: RealDlnaEngine(appContext) { onLocalNetworkChanged() }
    private val _state = MutableStateFlow(DlnaUiState())
    val state: StateFlow<DlnaUiState> = _state.asStateFlow()

    private val endpoints = linkedMapOf<String, DLNACast.Device>()
    private var selectedId: String? = null
    private var discoveryEnabled = false
    private var discoveryJob: Job? = null
    private var pollJob: Job? = null
    private var loadJob: Deferred<Boolean>? = null
    private val commands = Mutex()
    private var player: DlnaPlayerDelegate? = null
    private var sessionGeneration = 0L
    private var initialized = false
    private var lastSource: CastSource? = null
    private var lastStreamUrl: String? = null
    private var lastDevice: DLNACast.Device? = null
    private var lastRemotePosition = 0L
    private var transportPending = false
    var onSessionEnded: (() -> Unit)? = null
    var onSessionFailed: (() -> Unit)? = null

    fun initialize() {
        if (initialized) return
        runCatching { initLibrary() }
            .onSuccess { initialized = true }
            .onFailure { AppLog.event(AppLog.Category.CAST, "dlna_init_failed", failure = it) }
    }

    fun remotePlayer(): Player? = if (_state.value.session) player as? Player else null

    fun selectedHost(): String? = endpoints[selectedId]?.id?.let { DlnaDescription.location(it)?.host }

    /** DLNA discovery is SSDP: search bursts only while the picker is open. */
    fun setDiscoveryEnabled(enabled: Boolean) {
        discoveryEnabled = enabled
        if (enabled && discoveryJob?.isActive != true) {
            discoveryJob = scope.launch { searchLoop() }
        } else if (!enabled) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
    }

    private suspend fun searchLoop() {
        while (coroutineContext.isActive) {
            val devices = attempt { engine.search(SEARCH_TIMEOUT_MS) }
                .onFailure { AppLog.event(AppLog.Category.CAST, "dlna_search_failed", failure = it) }
                .getOrNull()
            if (devices != null) publishDevices(devices)
            delay(SEARCH_INTERVAL_MS)
        }
    }

    private fun publishDevices(devices: List<DLNACast.Device>) {
        val selected = endpoints[selectedId]
        endpoints.clear()
        if (_state.value.session && selected != null) endpoints[routeId(selected)] = selected
        devices.forEach { endpoints[routeId(it)] = it }
        _state.update {
            it.copy(
                available = endpoints.isNotEmpty(),
                devices = endpoints.map { (id, d) ->
                    CastDevice(id, d.name, id == selectedId, CastDevice.KIND_DLNA)
                },
            )
        }
    }

    private fun routeId(device: DLNACast.Device) = ID_PREFIX + device.id

    fun selectDevice(id: String) {
        if (endpoints[id] == null) return
        selectedId = id
        _state.update { s -> s.copy(failure = null, devices = s.devices.map { it.copy(selected = it.id == id) }) }
    }

    /**
     * DLNA has no session handshake — the cast itself is the connection, so [selectDevice]
     * must run first. A [positionMs] resume lands as a follow-up seek because
     * SetAVTransportURI always starts at 0.
     */
    suspend fun startCasting(source: CastSource, positionMs: Long, streamUrl: String, autoplay: Boolean = true): Boolean {
        initialize()
        val device = endpoints[selectedId]
        if (device == null) {
            reportFailure(IllegalStateException("The selected renderer is no longer available"), source.videoId, "selection")
            AppLog.event(AppLog.Category.CAST, "dlna_cast_refused",
                fields = mapOf("reason" to "no_device"), context = DiagnosticContext(videoId = source.videoId))
            return false
        }
        val generation = ++sessionGeneration
        loadJob?.cancel()
        pollJob?.cancel()
        // Fence commands from the previous media before the first transport suspension.
        // A queued Pause can otherwise return "old session" while a quality reload is in
        // flight and incorrectly fail the still-old remote player's generation.
        val pendingPlayer = player
        pendingPlayer?.loading(source, positionMs.coerceAtLeast(0), autoplay)
        lastRemotePosition = positionMs.coerceAtLeast(0)
        var stage = "load"
        val started = attempt {
            coroutineScope {
                val pending = async {
                    commands.withLock {
                        if (generation != sessionGeneration) return@withLock false
                        engine.clearProgressCache()
                        transportPending = true
                        val ok = withTimeout(LOAD_TIMEOUT_MS) {
                            engine.cast(device, streamUrl, source.title,
                                source.video?.mimeType?.takeIf(String::isNotBlank) ?: "video/mp4")
                        }
                        if (ok && generation == sessionGeneration) {
                            stage = "media_ready"
                            withTimeout(READY_TIMEOUT_MS) { engine.awaitMediaReady(streamUrl) }
                        }
                        if (ok && generation == sessionGeneration && positionMs > 0) {
                            stage = "resume_seek"
                            withTimeout(COMMAND_TIMEOUT_MS) {
                                check(engine.seek(positionMs)) { "Renderer rejected resume position" }
                            }
                        }
                        if (ok && generation == sessionGeneration && !autoplay) {
                            stage = "pause"
                            withTimeout(COMMAND_TIMEOUT_MS) {
                                check(engine.pause()) { "Renderer rejected paused playback" }
                            }
                        }
                        ok && generation == sessionGeneration
                    }
                }
                loadJob = pending
                try { pending.await() } finally { if (loadJob === pending) loadJob = null }
            }
        }.onFailure {
            reportFailure(it, source.videoId, stage)
            AppLog.event(AppLog.Category.CAST, "dlna_cast_failed",
                failure = it, context = DiagnosticContext(videoId = source.videoId))
        }.getOrDefault(false)
        if (generation != sessionGeneration) return false
        if (!started) {
            if (_state.value.failure == null) reportFailure(IllegalStateException("Renderer rejected media"), source.videoId, "start")
            AppLog.event(AppLog.Category.CAST, "dlna_cast_rejected",
                context = DiagnosticContext(videoId = source.videoId))
            return false
        }
        lastSource = source
        lastStreamUrl = streamUrl
        lastDevice = device
        val p = player ?: playerFactory(scope, sessionEngine(), ::reloadMedia)
        player = p
        if (p !== pendingPlayer) p?.loading(source, positionMs.coerceAtLeast(0), autoplay)
        _state.update { it.copy(session = true, deviceName = device.name) }
        startPolling(generation, p)
        AppLog.event(AppLog.Category.CAST, "dlna_cast_started",
            fields = mapOf(
                "device_name" to device.name,
                "video_id" to source.videoId,
                "position_ms" to positionMs.coerceAtLeast(0)),
            context = DiagnosticContext(videoId = source.videoId))
        return true
    }

    private fun startPolling(generation: Long, p: DlnaPlayerDelegate?) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var observedPlayback = false
            var inactivePolls = 0
            var lastPollFailure: Throwable? = null
            while (coroutineContext.isActive && generation == sessionGeneration) {
                delay(POLL_INTERVAL_MS)
                if (generation != sessionGeneration) return@launch
                val progress = attempt { withTimeout(COMMAND_TIMEOUT_MS) { commands.withLock { engine.progress() } } }.getOrNull()
                val transport = attempt { withTimeout(COMMAND_TIMEOUT_MS) { commands.withLock { engine.transportState() } } }
                    .onFailure { if (generation == sessionGeneration) lastPollFailure = it }.getOrNull()
                if (generation != sessionGeneration) return@launch
                p?.status(transport, progress?.first, progress?.second)
                p?.position()?.let { lastRemotePosition = it }
                if (transport == DLNACast.PlaybackState.PLAYING || transport == DLNACast.PlaybackState.PAUSED) observedPlayback = true
                inactivePolls = if (transport == null || !observedPlayback) inactivePolls + 1 else 0
                if (inactivePolls >= MAX_INACTIVE_POLLS) {
                    reportFailure(lastPollFailure ?: IllegalStateException("Renderer did not start playback"), lastSource?.videoId, "status")
                    endRemoteSession(generation, failed = true)
                    return@launch
                }
                val duration = progress?.second ?: (lastSource?.durationSec ?: 0) * 1000
                val reachedEnd = duration > 0 && lastRemotePosition >= duration - 2_000
                if (observedPlayback && !reachedEnd && p?.expectsPlayback() == true && (transport == DLNACast.PlaybackState.STOPPED ||
                        transport == DLNACast.PlaybackState.IDLE)) {
                    endRemoteSession(generation, failed = false)
                    return@launch
                }
                if (transport == DLNACast.PlaybackState.ERROR) {
                    reportFailure(IllegalStateException("Renderer reported an unknown or failed transport state"), lastSource?.videoId, "status")
                    endRemoteSession(generation, failed = true)
                    return@launch
                }
            }
        }
    }

    private fun endRemoteSession(generation: Long, failed: Boolean) {
        if (generation != sessionGeneration) return
        sessionGeneration++
        if (failed) stopTransport()
        transportPending = false
        lastRemotePosition = player?.position() ?: lastRemotePosition
        player?.reset()
        player = null
        _state.update { it.copy(session = false, deviceName = null) }
        if (failed) onSessionFailed?.invoke() else onSessionEnded?.invoke()
    }

    /** Stops the renderer and returns the last known remote position (session callbacks fire
     *  only via the poll's echo path). */
    fun stopCasting(): Long {
        val hadSession = transportPending || player != null || loadJob != null
        transportPending = false
        sessionGeneration++
        loadJob?.cancel()
        loadJob = null
        pollJob?.cancel()
        pollJob = null
        val p = player
        val position = (p?.position() ?: lastRemotePosition).coerceAtLeast(0)
        lastRemotePosition = position
        p?.reset()
        player = null
        if (hadSession) stopTransport()
        _state.update { it.copy(session = false, deviceName = null) }
        return position
    }

    private fun stopTransport() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            attempt { withTimeout(COMMAND_TIMEOUT_MS) { commands.withLock { engine.stop() } } }
        }
    }

    /** Clears selection + session state without callbacks (explicit VM teardown). */
    fun endSession() {
        sessionGeneration++
        transportPending = false
        loadJob?.cancel()
        loadJob = null
        pollJob?.cancel()
        pollJob = null
        player?.reset()
        player = null
        selectedId = null
        lastSource = null
        lastStreamUrl = null
        lastDevice = null
        _state.update {
            it.copy(
                session = false,
                deviceName = null,
                devices = it.devices.map { d -> d.copy(selected = false) },
            )
        }
    }

    /** Repeat-one / play-after-ended: restart the transport, then resume [startMs]. */
    private fun reloadMedia(startMs: Long) {
        val source = lastSource ?: return
        val url = lastStreamUrl ?: return
        val device = lastDevice ?: return
        val generation = sessionGeneration
        scope.launch {
            var stage = "reload"
            val ok = attempt {
                commands.withLock {
                    if (generation != sessionGeneration) return@withLock false
                    engine.clearProgressCache()
                    val started = withTimeout(LOAD_TIMEOUT_MS) {
                        engine.cast(device, url, source.title,
                            source.video?.mimeType?.takeIf(String::isNotBlank) ?: "video/mp4")
                    }
                    if (started && generation == sessionGeneration) {
                        stage = "media_ready"
                        withTimeout(READY_TIMEOUT_MS) { engine.awaitMediaReady(url) }
                    }
                    if (generation != sessionGeneration) return@withLock false
                    if (started && startMs > 0) {
                        stage = "resume_seek"
                        withTimeout(COMMAND_TIMEOUT_MS) { check(engine.seek(startMs)) }
                    }
                    started
                }
            }.onFailure {
                reportFailure(it, source.videoId, stage)
                AppLog.event(AppLog.Category.CAST, "dlna_reload_failed",
                    failure = it, context = DiagnosticContext(videoId = source.videoId))
            }.getOrDefault(false)
            if (generation != sessionGeneration) return@launch
            if (ok) {
                player?.loading(source, startMs.coerceAtLeast(0))
                startPolling(generation, player)
            } else endRemoteSession(generation, failed = true)
        }
    }

    private fun sessionEngine(): DlnaEngine = object : DlnaEngine by engine {
        private suspend fun command(action: suspend () -> Boolean): Boolean {
            val generation = sessionGeneration
            return try {
                withTimeout(COMMAND_TIMEOUT_MS) { commands.withLock {
                    if (!_state.value.session || generation != sessionGeneration) false else action()
                } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == sessionGeneration) reportFailure(error, lastSource?.videoId, "command")
                throw error
            }
        }
        override suspend fun play() = command { engine.play() }
        override suspend fun pause() = command { engine.pause() }
        override suspend fun seek(positionMs: Long) = command { engine.seek(positionMs) }
        override suspend fun stop() = command { engine.stop() }
    }

    /** A local-network change invalidates the socket factory and usually the renderer address:
     *  end the in-flight session (with a report) instead of continuing on a dead connection. */
    internal fun onLocalNetworkChanged() {
        scope.launch {
            val generation = sessionGeneration
            if (!_state.value.session && !transportPending) return@launch
            reportFailure(java.io.IOException("Local network changed during casting"), lastSource?.videoId, "network")
            endRemoteSession(generation, failed = true)
        }
    }

    fun clearFailure() { _state.update { it.copy(failure = null) } }

    private fun reportFailure(error: Throwable, videoId: String?, stage: String) {
        val device = _state.value.devices.firstOrNull { it.id == selectedId }
        val report = CastFailureReport.create("DLNA", device, videoId, stage, error)
        _state.update { it.copy(failure = report) }
        AppLog.event(AppLog.Category.CAST, "dlna_request_failed", failure = error,
            fields = mapOf("report_id" to report.id, "stage" to stage), context = DiagnosticContext(videoId = videoId))
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (timeout: TimeoutCancellationException) {
        coroutineContext.ensureActive()
        Result.failure(timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    companion object {
        /** Route-id prefix so the merged picker can route by kind. */
        const val ID_PREFIX = "dlna:"
        private const val SEARCH_TIMEOUT_MS = 4_000L
        private const val SEARCH_INTERVAL_MS = 15_000L
        private const val POLL_INTERVAL_MS = 1_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L
        // Description, SetURI and Play each have their own HTTP deadline. A slow renderer
        // must not spend the entire resume/pause budget merely opening its media.
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val READY_TIMEOUT_MS = 25_000L
        private const val MAX_INACTIVE_POLLS = 30

        /** Test timing seam: poll cadence used by DlnaControllerTest. */
        internal const val POLL_FOR_TEST_MS = POLL_INTERVAL_MS
    }
}
