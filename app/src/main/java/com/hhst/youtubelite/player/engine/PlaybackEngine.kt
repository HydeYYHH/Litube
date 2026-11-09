package com.hhst.youtubelite.player.engine
import com.hhst.youtubelite.player.datasource.PlaybackRecovery
import com.hhst.youtubelite.player.datasource.PlaybackClaim
import com.hhst.youtubelite.player.datasource.PlaybackRecovery.Reason as RecoveryReason

import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticOperation
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import com.hhst.youtubelite.diagnostics.DiagnosticProgressWatchdog
import com.hhst.youtubelite.diagnostics.DiagnosticNetwork
import com.hhst.youtubelite.diagnostics.PlaybackTelemetry

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.PageKind
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.PlaybackFailureReport
import com.hhst.youtubelite.diagnostics.AndroidDiagnosticState
import com.hhst.youtubelite.diagnostics.DiagnosticRedaction
import com.hhst.youtubelite.BuildConfig
import android.os.Build
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.PlaybackDemand
import com.hhst.youtubelite.extractor.PlaybackResolutionException
import com.hhst.youtubelite.extractor.StreamClient
import com.hhst.youtubelite.extractor.OEmbedTitleFetcher
import com.hhst.youtubelite.extractor.Stream
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.extractor.InvalidMediaResponseException
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTracking
import com.hhst.youtubelite.player.PREF_TTL_MS
import com.hhst.youtubelite.player.datasource.AudioTrackChoice
import com.hhst.youtubelite.player.datasource.AudioTrackIdentity
import com.hhst.youtubelite.player.datasource.AudioCatalogSupplement
import com.hhst.youtubelite.player.datasource.CastAudioSelection
import com.hhst.youtubelite.player.datasource.CodecCapabilities
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.StreamSelection
import com.hhst.youtubelite.player.datasource.SubtitleSelection
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.player.sponsor.SponsorSkipController
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Playback modes with stable persisted values. */
enum class LoopMode(val persistedValue: Int, val repeatMode: Int) {
    QUEUE_NEXT(0, Player.REPEAT_MODE_OFF),
    LOOP_ONE(1, Player.REPEAT_MODE_ONE),
    PAUSE_AT_END(2, Player.REPEAT_MODE_OFF),
    QUEUE_RANDOM(3, Player.REPEAT_MODE_OFF);

    companion object {
        fun fromPersisted(value: Int): LoopMode =
            entries.firstOrNull { it.persistedValue == value } ?: QUEUE_NEXT
    }
}

data class SubtitleTrack(val language: String, val autoGenerated: Boolean) {
    val key: String get() = SubtitleSelection.key(language, autoGenerated)
}

enum class PlaybackNotice { PAYMENT_REQUIRED }

data class PlaybackSnapshot(
    val videoId: String? = null,
    val url: String? = null,
    val title: String = "",
    val author: String? = null,
    val isLive: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Last explicit play/pause intent; independent of [isPlaying] while buffering. */
    val userWantsPlay: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val loopMode: LoopMode = LoopMode.QUEUE_NEXT,
    val qualityLabel: String? = null,
    val qualities: List<String> = emptyList(),
    val subtitleKey: String? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrackChoice> = emptyList(),
    val audioTrackKey: String? = null,
    val sponsorSegments: List<SponsorBlockManager.Segment> = emptyList(),
    val subtitleCues: List<String> = emptyList(),
    val error: String? = null,
    val notice: PlaybackNotice? = null,
    val failureReport: PlaybackFailureReport? = null,
    val prepared: Boolean = false,
    /** Whether the current output surface has received a frame. */
    val firstFrameRendered: Boolean = false,
    /** True after this load renders a frame; survives surface handoffs until the next load. */
    val hasRenderedVideoFrame: Boolean = false,
    val ended: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    /** Quality actually rendering (from the selected track); null = unknown. */
    val activeQuality: String? = null,
    val subtitleEnabled: Boolean = false,
    val videoFormat: Format? = null,
    val audioFormat: Format? = null,
    val sponsorCountdownSec: Int? = null,
    /** Manual skip chip visible while inside a suppressed segment. */
    val sponsorChip: Boolean = false,
    /** True when [sponsorChip] targets a highlight point (chip jumps TO it). */
    val sponsorChipHighlight: Boolean = false,
    /** Bumped on every user seek; watch reporting re-anchors its interval on change. */
    val discontinuityEpoch: Long = 0,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
)

interface PlaybackApi {
    fun preparePlayback(urlOrId: String) { }
    fun prefetchDemand(videoId: String): PlaybackDemand? = null
    /** Loads a watch source; an attached receiver owns its playback controls. */
    fun play(urlOrId: String)
    /** Re-extract and reload the current video after a failure. */
    fun retry()
    fun stop()
    fun setVideoVisible(visible: Boolean) {}
    fun setAudioOnly(enabled: Boolean) {}
    fun setVideoSurface(surface: SurfaceView?)
    fun playOrPause()
    fun play()
    fun pause()
    /** Pauses only the local player — never the cast receiver. */
    fun pauseLocal()
    /** Receiver identity after a successful recast (queue advance mid-session). */
    fun markReceiverIdentity(videoId: String, title: String, author: String?)
    /** Publish the formats selected for the receiver, independently of the paused local tracks. */
    fun markReceiverSource(source: CastSource) = markReceiverIdentity(source.videoId, source.title, source.author)
    fun seekTo(positionMs: Long)
    fun seekRelative(offsetMs: Long)
    fun setSpeed(speed: Float)
    /** True while a long-press 2x hold owns speed (must not persist as the user rate). */
    fun setSpeedHold(active: Boolean)
    fun setLoopMode(mode: LoopMode)
    /** Shorts keep their playback order independently of watch playback. */
    fun setShortsMode(enabled: Boolean) {}
    fun setQuality(label: String?)
    /** False when [trackKey] has no parseable track; the caller keeps its UI state. */
    fun setSubtitle(trackKey: String?): Boolean
    fun setAudioTrack(trackKey: String?)
    /** The audio menu opened: start the bounded catalog supplement when the current source looks incomplete. */
    fun onAudioMenuOpened() { }
    /** Adaptive video/audio pair currently loaded, for Cast / link projection. */
    fun currentCastSource(): CastSource?
    /** Materialize file formats only when a receiver actually needs them. */
    suspend fun prepareCastSource(): CastSource? = currentCastSource()
    /** Best muxed source for DLNA renderers (they cannot take a separate A/V pair); null when none. */
    fun currentMuxedCastSource(): CastSource?
    suspend fun prepareMuxedCastSource(): CastSource? = currentMuxedCastSource()
    /** Re-extracts and returns a fresh URL for a published cast track. */
    fun refreshCastUrl(token: String, videoId: String? = null, itag: Int? = null): String?
    fun backupCastUrl(token: String, videoId: String?, itag: Int?, failedProfile: String?): String? = null
    /** Commands/snapshot follow [delegate] while non-null; null detaches. Returns true when local media already diverged from the receiver (recast in flight) — the caller must not seek/play local to the receiver's position. */
    fun attachCastPlayer(delegate: Player?): Boolean
    fun setQueueNavigation(hasNext: Boolean, hasPrevious: Boolean)
    /** User cancelled the pending sponsor skip (suppress that segment). */
    fun cancelSponsorSkip()
    fun skipSponsorNow()
    fun skipSponsorChipSegment()
    /** Drops in-flight load/rebuild work so a close cannot be undone by a late prepare. Does not clear current media ([stop] does); [pausePlayback] false keeps the active player rolling (Cast TV) while still fencing late local loads. */
    fun cancelPending(pausePlayback: Boolean = true)
    /** Called when the current item ends (drives queue/playlist auto-advance). */
    var onEnded: (() -> Unit)?
    /** Receiver hit a playback error while delegating: engine already detached and resumed local playback; the host must end the Cast session and surface the failure hint. */
    var onCastError: (() -> Unit)?
    /** Local quality/audio rebuild produced a Cast-incompatible published track set: the host should load a new manifest on the receiver. */
    var onCastManifestReload: (() -> Unit)?
    /** A successful playback response's short-lived reporting endpoints (watch-time URLs) belong to the session owner, not the UI. */
    var onWatchContext: ((videoId: String, tracking: PlayerTracking?, sessionGeneration: Long) -> Unit)?
    var onWatchBoundary: ((PlaybackSnapshot, Long) -> Unit)?
        get() = null
        set(_) {}
    val snapshot: StateFlow<PlaybackSnapshot>
}

/** Saved playback position; duration comes from the current media. */
@kotlinx.serialization.Serializable
data class ResumePoint(val positionMs: Long = 0L)

/** Formats + metadata the Cast proxy needs to synthesize a LAN DASH manifest. */
data class CastSource(
    val videoId: String,
    val title: String,
    val durationSec: Long,
    val video: Format?,
    val audio: Format?,
    /** Receiver OSD subtitle line; null keeps it title-only. */
    val author: String? = null,
    /** Receiver OSD artwork (public i.ytimg.com URL, fetchable by the TV). */
    val thumbnailUrl: String? = null,
)

@UnstableApi
class PlaybackEngine(
    context: Context,
    private val extractor: Extractor,
    private val resolver: MediaSourceResolver,
    private val cache: JsonCache,
    private val prefs: ExtensionManager,
    private val sponsorBlock: SponsorBlockManager,
    private val titleFetcher: OEmbedTitleFetcher? = null,
    private val remoteResumePosition: (suspend (String) -> Long?)? = null,
) : PlaybackApi {

    private val appContext = context.applicationContext
    private val trackSelector = DefaultTrackSelector(context)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(PlaybackRenderersFactory(context).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        .setLoadControl(defaultLoadControl())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
            // LOCAL (not NETWORK): steady playback renders locally, and the
            // screen-off background-play path needs the CPU held through it.
            .setWakeMode(C.WAKE_MODE_LOCAL)
        .setHandleAudioBecomingNoisy(true)
        .setUsePlatformDiagnostics(false)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playJob: Job? = null
    private var chapterJob: Job? = null
    private var firstMediaReady: CompletableDeferred<Unit>? = null
    private var rebuildJob: Job? = null
    private var fallbackPreparation: Job? = null
    /** Lifecycle counters for diagnostics and instrumented regression checks. */
    private val mediaPrepares = AtomicInteger()
    private val surfaceSwitches = AtomicInteger()
    val mediaPrepareCount: Int get() = mediaPrepares.get()
    val surfaceSwitchCount: Int get() = surfaceSwitches.get()
    /**
     * Last explicit play/pause from the user (or a play() load). Not inferred
     * from [Player.isPlaying]: a pause during prepare must survive the later
     * playWhenReady assignment.
     */
    @Volatile private var userWantsPlay = false
    private val playbackGeneration = AtomicLong()
    private val discontinuityEpoch = AtomicLong()
    /** Generation of the media currently attached to [player]. */
    @Volatile private var attachedGeneration = 0L
    /** Explicit start of the newest fresh load request, held until that load
        attaches: a first-load failure must retry at "t=" (0 included), not at
        remembered progress. */
    @Volatile private var pendingExplicitStartMs: Long? = null
    private val refreshCastMutex = Mutex()

    // refreshCastUrl (NanoHTTPD thread) reads both the fields above and the
    // flag pair below: everything crossing that thread boundary is @Volatile.
    @Volatile private var currentStream: Stream? = null
    @Volatile private var currentMetadata: Metadata? = null
    private var playbackAttempt: PlaybackAttempt? = null

    @Volatile private var muxedFallbackActive = false
    /** Audio format of the attached source; the match key for cast switching and track recovery. */
    private var lastAudioFormat: Format? = null
    /** Video formats of the current adaptive manifest; empty when non-adaptive. */
    private var adaptiveVideoPool: List<Format> = emptyList()
    /** The user's quality pin (menu pick); null = "Auto". */
    private var pinnedQuality: String? = null
    /** Cast delegate: while non-null every command/state read goes to it. */
    private var castPlayer: Player? = null
    private var castListener: Player.Listener? = null
    /** Video the receiver is actually playing; may differ from [PlaybackSnapshot.videoId]. */
    private var receiverVideoId: String? = null
    /** True across the seek this engine issued for a sponsor skip. */
    private var skipSeekInProgress = false
    private val sponsorSkip = SponsorSkipController()

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    override var onEnded: (() -> Unit)? = null

    private var shortsMode = false

    override var onCastError: (() -> Unit)? = null

    override var onCastManifestReload: (() -> Unit)? = null

    override var onWatchContext: ((videoId: String, tracking: PlayerTracking?, sessionGeneration: Long) -> Unit)? = null
    override var onWatchBoundary: ((PlaybackSnapshot, Long) -> Unit)? = null

    /** Drives the playback notification service; injected to avoid a hard dep. */
    var notificationController: PlaybackNotificationController? = null

    private var videoVisible = true
    private var videoSurface: SurfaceView? = null
    private val surfaceLifecycle = object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            if (videoSurface?.holder === holder) {
                invalidateFirstFrame()
                playerLog("surface_created", mapOf("valid" to holder.surface.isValid))
            }
        }
        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            if (videoSurface?.holder === holder) {
                invalidateFirstFrame()
                playerLog("surface_destroyed")
            }
        }
        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    }
    private fun invalidateFirstFrame() {
        _snapshot.value = _snapshot.value.copy(firstFrameRendered = false)
    }
    private var outputWasVisible = false
    private var loadStartedAt = 0L
    private var firstFrameMs: Long? = null
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    @Volatile private var diagnosticContext: DiagnosticContext? = null
    @Volatile private var attachedContext: DiagnosticContext? = null
    private var playbackOperation: DiagnosticOperation? = null
    private val progressWatchdog = DiagnosticProgressWatchdog(10_000, { state, duration ->
        playerLog("playback_progress.$state", mapOf("reason" to "no_playback_progress", "duration_ms" to duration), critical = state == "stalled")
    }, 15_000, { diagnosticContext?.let(AppLog::playbackThreads) })
    private fun beginDiagnostics(videoId: String, generation: Long): DiagnosticContext {
        progressWatchdog.reset("superseded")
        loadStartedAt = elapsed(); firstFrameMs = null; videoDecoderName = null; audioDecoderName = null
        performance.reset(loadStartedAt)
        playbackOperation?.finish(DiagnosticOutcome.SUPERSEDED, "source_replaced")
        val attempt = playbackAttempt?.takeIf { it.videoId == videoId }
            ?: PlaybackAttempt(videoId).also { playbackAttempt = it }
        val context = attempt.diagnosticRoot.child().copy(generation = generation)
        diagnosticContext = context
        playbackOperation = AppLog.operation(AppLog.Category.PLAYER, "playback", context)
        return context
    }
    private val performance = PlaybackPerformanceMonitor { kind, state, fields ->
        playerLog("performance_$kind", fields + mapOf("state" to state), critical = state == "start" || state == "ongoing")
    }
    private fun playerLog(event: String, fields: Map<String, Any?> = emptyMap(), failure: Throwable? = null, critical: Boolean = failure != null) {
        AppLog.event(AppLog.Category.PLAYER, event,
            fields, failure, critical, diagnosticContext)
    }
    private fun elapsed() = SystemClock.elapsedRealtime()
    private val diagnosticTicker = object : Runnable {
        override fun run() {
            runCatching { sampleDiagnostics() }
            if (_snapshot.value.videoId != null) handler.postDelayed(this, 1000)
        }
    }
    private fun sampleDiagnostics() {
        val now = elapsed()
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val dropped = counters?.droppedBufferCount ?: 0
        val video = player.videoFormat; val audio = player.audioFormat
        val nominal = video?.frameRate?.takeIf { it > 0 }
        val refresh = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        val expected = PlaybackPerformanceMonitor.expectedFps(nominal, player.playbackParameters.speed, refresh)
        val buffering = castPlayer == null && userWantsPlay &&
            (player.playbackState == Player.STATE_BUFFERING || !_snapshot.value.prepared)
        val visibleOutput = videoVisible && videoSurface?.let {
            it.isShown && it.windowVisibility == View.VISIBLE && it.holder.surface.isValid
        } == true
        if (visibleOutput != outputWasVisible) {
            outputWasVisible = visibleOutput
            performance.stabilize(now)
        }
        performance.sample(now, rendered, dropped, expected,
            visibleOutput && castPlayer == null && userWantsPlay &&
                player.playbackState == Player.STATE_READY && video != null && _snapshot.value.prepared,
            buffering)
        val diagnostics = PlaybackDiagnostics(
            nominalFps = nominal, renderedFps = performance.fps, expectedFps = expected,
            videoCodec = video?.codecs ?: video?.sampleMimeType, audioCodec = audio?.codecs ?: audio?.sampleMimeType,
            videoDecoder = videoDecoderName, audioDecoder = audioDecoderName,
            width = video?.width?.takeIf { it > 0 }, height = video?.height?.takeIf { it > 0 },
            videoBitrate = video?.bitrate?.takeIf { it > 0 }, audioBitrate = audio?.bitrate?.takeIf { it > 0 },
            renderedFrames = rendered, droppedFrames = dropped,
            droppedRatio = (rendered + dropped).takeIf { it > 0 }?.let { dropped.toFloat() / it },
            firstFrameMs = firstFrameMs, bufferingMs = performance.bufferDuration, bufferCount = performance.bufferCount,
            playbackState = when { castPlayer != null -> "cast"; buffering -> "buffering"; player.isPlaying -> "playing"; player.playbackState == Player.STATE_ENDED -> "ended"; else -> "paused" }, sampledAt = now)
        _snapshot.value = _snapshot.value.copy(diagnostics = diagnostics)
        val sourceAttached = attachedContext == diagnosticContext
        AppLog.snapshot(AppLog.Category.PLAYER, diagnosticContext, mapOf(
            "phase" to when { !sourceAttached || !_snapshot.value.prepared -> "extraction"; firstFrameMs == null && video != null -> "awaiting_first_frame"; else -> "playback" },
            "state" to player.playbackState, "play_when_ready" to player.playWhenReady, "user_wants_play" to userWantsPlay,
            "suppression_reason" to player.playbackSuppressionReason, "position_ms" to player.currentPosition.takeIf { sourceAttached },
            "buffered_position_ms" to player.bufferedPosition.takeIf { sourceAttached }, "buffer_duration_ms" to diagnostics.bufferingMs,
            "rendered_frames" to counters?.renderedOutputBufferCount?.takeIf { sourceAttached }, "dropped_frames" to counters?.droppedBufferCount?.takeIf { sourceAttached },
            "video_decoder" to videoDecoderName, "audio_decoder" to audioDecoderName, "first_frame_ms" to firstFrameMs,
            "surface_attached" to (videoSurface != null), "surface_valid" to videoSurface?.holder?.surface?.isValid,
            "surface_visible" to visibleOutput, "first_frame_rendered" to _snapshot.value.firstFrameRendered,
            "pending_requests" to diagnosticContext?.let(DiagnosticNetwork::pending)))
        val shouldAdvance = castPlayer == null && userWantsPlay && player.playerError == null &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            player.playbackState != Player.STATE_ENDED && (_snapshot.value.isBuffering || _snapshot.value.prepared)
        val progress = if (!sourceAttached) null else if (video != null && videoVisible) counters?.renderedOutputBufferCount?.toLong() else player.currentPosition
        progressWatchdog.sample(now, progress, shouldAdvance)
    }

    init {
        val telemetry = PlaybackTelemetry()
        player.addAnalyticsListener(telemetry)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (telemetry.context(eventTime) != diagnosticContext || attachedContext != diagnosticContext) return
                videoDecoderName = decoderName; performance.stabilize(elapsed())
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (telemetry.context(eventTime) == diagnosticContext && attachedContext == diagnosticContext) audioDecoderName = decoderName
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                val rejection = when {
                    telemetry.context(eventTime) != diagnosticContext || attachedContext != diagnosticContext -> "source_changed"
                    attachedGeneration != playbackGeneration.get() -> "generation_changed"
                    output != videoSurface?.holder?.surface || videoSurface?.holder?.surface?.isValid != true -> "output_changed"
                    else -> null
                }
                if (rejection != null) {
                    playerLog("video_output_frame_ignored", mapOf("reason" to rejection,
                        "attached_generation" to attachedGeneration, "request_generation" to playbackGeneration.get()))
                    return
                }
                _snapshot.value = _snapshot.value.copy(firstFrameRendered = true, hasRenderedVideoFrame = true)
                if (firstFrameMs == null && loadStartedAt > 0) {
                    firstFrameMs = elapsed() - loadStartedAt
                    performance.stabilize(elapsed())
                }
            }
        })
        resolver.configureStartupTrackSelection { trackSelector.parameters }
        resolver.configureSubtitleLoadError { context, error ->
            if (context != null && context == diagnosticContext && attachedContext == context &&
                attachedGeneration == playbackGeneration.get() && _snapshot.value.subtitleKey != null) {
                // Source-bound identity also works when the caption's analytics timeline is empty.
                // A/V is still healthy, so disabling captions must not rebuild or interrupt it.
                playerLog("subtitle_disabled", mapOf("reason" to "caption_load_failed"), error)
                applySubtitle(null)
                _snapshot.value = _snapshot.value.copy(subtitleCues = emptyList())
            }
        }
        // No tunneling: it is a decoder-pair constraint that
        // can silently veto track-selection changes on some devices.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                publish()
                if (playbackState == Player.STATE_ENDED) handleEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && attachedGeneration == playbackGeneration.get()) {
                    // Video can render while audio is still opening. Keep the bounded
                    // fallback preparation until all selected tracks can play.
                    fallbackPreparation?.cancel()
                    firstMediaReady?.complete(Unit)
                }
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.post(ticker)
                else persistPosition()
                publish()
                notifyTransport(isPlaying)
            }

            override fun onRenderedFirstFrame() {
                if (attachedGeneration == playbackGeneration.get()) {
                    firstMediaReady?.complete(Unit)
                    // First frame proves the explicit start was applied; a later
                    // retry must follow the playback position, not the request.
                    pendingExplicitStartMs = null
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                val texts = cueGroup.cues.mapNotNull { it.text?.toString() }
                    .filter { it.isNotBlank() }
                if (texts != _snapshot.value.subtitleCues) {
                    _snapshot.value = _snapshot.value.copy(subtitleCues = texts)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                performance.stabilize(elapsed())
                // Track truth drives the quality menu/badge (and re-applies
                // a pin the source swap dropped). Skipped while casting: the
                // cast player owns the visible stream then.
                if (castPlayer == null) {
                    updateQualityFromTracks()
                    publishRenditionAudio()
                }
                publish()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _snapshot.value = _snapshot.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // Publish paused seeks too — the ticker only runs while playing.
                // A seek the engine did NOT issue is deliberate: landing inside
                // a sponsor segment opts out of auto-skipping it.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    performance.stabilize(elapsed())
                    discontinuityEpoch.incrementAndGet()
                    if (skipSeekInProgress) {
                        skipSeekInProgress = false
                    } else {
                        sponsorSkip.onUserSeek(
                            newPosition.positionMs.coerceAtLeast(0L),
                            sponsorBlock.currentSegments,
                        )
                    }
                }
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
        sponsorBlock.onSegmentsLoaded = { segments ->
            // Fired from the manager's IO scope; snapshot writes are main-confined.
            handler.post {
                _snapshot.value = _snapshot.value.copy(sponsorSegments = segments)
            }
        }
    }

    private val ticker = object : Runnable {
        private var persistTick = 0
        override fun run() {
            // Load fence: between the snapshot reset (loadInternal) and the
            // media swap the player still holds the PREVIOUS video, so running
            // skip checks against the new video's segments on the old timeline
            // would issue a bogus seek.
            if (_snapshot.value.prepared) {
                // No skipping while casting, by design: the receiver owns the
                // timeline and its seeks cannot be mirrored back with sample
                // accuracy, so SponsorBlock stays local-only.
                if (activePlayer() === player) {
                    val p = player
                    val skipTo = sponsorSkip.tick(
                        p.currentPosition, sponsorBlock.currentSegments,
                        prefs.isEnabled(PreferenceKeys.SPONSOR_COUNTDOWN),
                    )
                    if (skipTo != null) {
                        skipSeekInProgress = true
                        p.seekTo(skipTo)
                    }
                }
            }
            // Steady playback emits no listener events; without this poll the
            // time bar would freeze between buffering transitions.
            publish()
            persistTick++
            if (persistTick % 4 == 0) persistPosition()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun play(urlOrId: String) {
        if (PageKind.isShorts(urlOrId)) return
        val videoId = VideoId.parse(urlOrId) ?: return
        PlaybackClaim.videoId = videoId
        val requestUrl = VideoId.watchUrl(videoId)
        val explicitStart = VideoId.startPositionMs(urlOrId)
        userWantsPlay = true
        if (videoId == _snapshot.value.videoId && _snapshot.value.prepared) {
            _snapshot.value = _snapshot.value.copy(url = requestUrl)
            if (explicitStart != null) {
                seekTo(PlaybackProgress.clampStartMs(explicitStart, activePlayer().duration))
            }
            resumePlayback(activePlayer())
            return
        }
        reportWatchBoundary()
        persistPosition()
        playbackAttempt = PlaybackAttempt(videoId)
        nextPlaybackGeneration()
        playJob?.cancel()
        rebuildJob?.cancel()
        fallbackPreparation?.cancel(); fallbackPreparation = null
        pendingExplicitStartMs = explicitStart
        resetAudioSupplement()
        playJob = scope.launch {
            loadInternal(
                videoId,
                reExtract = false,
                requestUrl = requestUrl,
                startPositionMs = explicitStart,
            )
        }
    }

    override fun retry() {
        val videoId = _snapshot.value.videoId ?: return
        extractor.resetRecovery(videoId)
        playbackAttempt = PlaybackAttempt(videoId)
        val position = player.currentPosition.takeIf {
            currentMetadata?.id == videoId && attachedGeneration == playbackGeneration.get() &&
                firstMediaReady?.isCompleted == true
        }
            // The failed load never reached first frame: its request's explicit
            // start (0 included) must survive the retry instead of falling back
            // to remembered progress.
            ?: pendingExplicitStartMs
        userWantsPlay = true
        nextPlaybackGeneration()
        playJob?.cancel()
        rebuildJob?.cancel()
        fallbackPreparation?.cancel(); fallbackPreparation = null
        muxedFallbackActive = false
        playJob = scope.launch {
            loadInternal(videoId, reExtract = true, requestUrl = _snapshot.value.url, startPositionMs = position)
        }
    }

    private var audioPreference: String? = null

    /** Chapters belong to one attached source, independently of source submission. */
    private fun nextPlaybackGeneration(): Long {
        chapterJob?.cancel()
        chapterJob = null
        return playbackGeneration.incrementAndGet()
    }

    /** Menu-open catalog probe: one bounded fresh read per video; never blocks playback. */
    private var audioSupplementJob: Job? = null
    @Volatile private var audioSupplement: Pair<Metadata, Stream>? = null
    @Volatile private var audioSupplementVideo: String? = null

    private suspend fun loadInternal(
        videoId: String,
        reExtract: Boolean,
        requestUrl: String? = null,
        startPositionMs: Long? = null,
    ) {
        val generation = playbackGeneration.get()
        val reportingGeneration = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation
        val context = beginDiagnostics(videoId, generation)
        val preparing = AppLog.operation(AppLog.Category.PLAYER, "prepare", context.child(), mapOf("reextract" to reExtract))
        val remoteResume = if (startPositionMs == null && prefs.isEnabled(PreferenceKeys.REMEMBER_LAST_POSITION) &&
            !com.hhst.youtubelite.data.media.MediaSession.privateBrowsing()) {
            remoteResumePosition?.let { load -> scope.async {
                try {
                    withTimeoutOrNull(3_000) { load(videoId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.detail(AppLog.Category.PLAYER, "resume.read_failed", mapOf("reason" to error.javaClass.simpleName), context)
                    null
                }
            } }
        } else null
        if (videoId != _snapshot.value.videoId) {
            audioPreference = null
            pinnedQuality = rememberedQuality()
        }
        handler.removeCallbacks(diagnosticTicker); handler.post(diagnosticTicker)
        val speed = rememberedSpeed()
        val loop = rememberedLoopMode()
        // Selector constraints and hold state are per-video: a pin left over
        // from the previous video (or its interrupted speed hold) must not
        // apply here. The remembered quality is re-applied after resolve.
        speedHoldActive = false
        skipSeekInProgress = false
        sponsorSkip.reset()
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(null)
            .build()
        val previous = _snapshot.value
        val sameVideo = previous.videoId == videoId
        val pendingPosition = (startPositionMs ?: previous.positionMs).coerceAtLeast(0L)
        _snapshot.value = (if (sameVideo) previous else PlaybackSnapshot()).copy(
            videoId = videoId,
            url = VideoId.watchUrl(videoId),
            positionMs = if (sameVideo) pendingPosition else (startPositionMs ?: 0L).coerceAtLeast(0L),
            bufferedPositionMs = if (sameVideo) pendingPosition else 0L,
            isPlaying = false,
            isBuffering = true,
            userWantsPlay = userWantsPlay,
            speed = speed,
            loopMode = loop,
            qualityLabel = pinnedQuality,
            prepared = false,
            firstFrameRendered = false,
            ended = false,
            error = null,
            notice = null,
            failureReport = null,
        )
        player.repeatMode = loop.repeatMode
        castPlayer?.repeatMode = loop.repeatMode
        try {
            val extraction = if (reExtract) extractor.extractFresh(videoId, diagnosticContext = context)
                else extractor.extract(videoId, demand = playbackDemand(), diagnosticContext = context)
            val stream = withContext(Dispatchers.IO) { extraction.stream.await() }
            val metadata = withContext(Dispatchers.IO) { extraction.metadata.await() }
            coroutineContext.ensureActive()
            if (!stillCurrent(generation)) return

            muxedFallbackActive = false
            AppLog.event(AppLog.Category.PLAYER, "extraction_complete", mapOf("format_count" to stream.formats.size, "has_hls" to !stream.hlsUrl.isNullOrBlank(), "has_dash" to !stream.dashUrl.isNullOrBlank()), context = context)
            val source = withContext(Dispatchers.IO) { resolveSource(stream, metadata, context) }
            coroutineContext.ensureActive()
            if (!stillCurrent(generation)) return
            currentStream = stream
            currentMetadata = metadata
            // Short-lived reporting endpoints travel with this response; hand them
            // to the session owner while the media identity is still this load.
            onWatchContext?.invoke(videoId, stream.tracking, reportingGeneration)
            rememberAudio(source.audioFormat)
            adaptiveVideoPool = source.videoFormats
            // Clear just before the new source attaches: the old one keeps its
            // pin while the extraction runs and must not adapt up in the gap.
            clearVideoConstraints()
            applyPreferredQualityConstraint(source)
            sponsorBlock.load(videoId, context)

            val durationMs = metadata.duration * 1000L
            // A settled account read still wins, but an in-flight one must never hold the
            // source: the read started at load begin, so waiting the full three seconds here
            // would delay a cached/fast extraction behind the network instead of overlapping it.
            val remoteMs = remoteResume?.let { withTimeoutOrNull(REMOTE_RESUME_GRACE_MS) { it.await() } }?.takeIf {
                !metadata.isLive && reportingGeneration == com.hhst.youtubelite.data.media.MediaSession.snapshot().generation
            }
            coroutineContext.ensureActive()
            if (!stillCurrent(generation)) return
            val startMs = PlaybackProgress.resolveStartMs(
                startPositionMs,
                rememberedPosition(videoId, metadata.duration),
                durationMs,
                remoteMs,
            )
            AppLog.detail(AppLog.Category.PLAYER, "resume.selected", mapOf("position_ms" to startMs,
                "source" to when { startPositionMs != null -> "explicit"; remoteMs != null -> "account"; else -> "local" }), context)
            val mediaReady = CompletableDeferred<Unit>()
            firstMediaReady = mediaReady
            attachedContext = context
            player.setMediaSource(source.mediaSource, startMs)
            player.setPlaybackSpeed(speed)
            mediaPrepares.incrementAndGet()
            player.prepare()
            attachedGeneration = generation
            // System media card / AVRCP read the session player's metadata;
            // PlaybackService.refresh() mirrors it into the notification too.
            // Player.mediaMetadata is read-only — title/author ride on the
            // MediaItem built by MediaSourceResolver instead.
            // While casting, the receiver keeps the audio; local stays paused
            // until the host recasts the prepared source onto the TV.
            player.playWhenReady = castPlayer == null && userWantsPlay

            // Restore persisted captions only when the key still maps to a
            // parseable track, or the badge would lie.
            val subKey = SubtitleSelection.migrate(
                rememberedSubtitleKey(),
                stream.subtitles,
            )
            if (subKey != null && resolver.hasSubtitleTrack(stream.subtitles, subKey)) {
                applySubtitle(subKey)
            }

            val audioChoices = AudioTrackIdentity.choices(stream.formats)
            _snapshot.value = _snapshot.value.copy(
                title = metadata.title,
                author = metadata.author,
                isLive = metadata.isLive,
                qualities = trackQualityLabels(),
                subtitleKey = subKey,
                subtitleTracks = stream.subtitles.map {
                    SubtitleTrack(it.languageCode, it.autoGenerated)
                },
                audioTracks = audioChoices,
                audioTrackKey = source.audioFormat?.let { AudioTrackIdentity.key(it) }
                    ?: audioPreference,
                durationMs = durationMs,
                prepared = true,
                error = null,
                chapters = emptyList(),
                videoFormat = source.videoFormat,
                audioFormat = source.audioFormat,
            )
            publishRenditionAudio()
            AppLog.event(AppLog.Category.PLAYER, "media_source_submitted", mapOf("duration_ms" to (elapsed() - loadStartedAt)), context = context)
            preparing.finish(DiagnosticOutcome.SUCCESS, "media_source_submitted")

            // During recast the local player is paused and the receiver still
            // plays the previous video until startCasting succeeds. Binding the
            // notification now would show the new title for that receiver.
            // markReceiverIdentity updates it after the handoff.
            if (castPlayer == null) {
                publishNotification(
                    player = activePlayer(),
                    title = metadata.title,
                    author = metadata.author,
                    videoId = videoId,
                )
            }
            maybeFetchOriginalTitle(videoId, metadata.title)
            // Publish the prepared source immediately. Optional /next must not compete
            // with initial media reads, or hold the title/controls behind chapter parsing.
            if (castPlayer != null) mediaReady.complete(Unit)
            chapterJob = scope.launch {
                if (withTimeoutOrNull(15_000) { mediaReady.await(); true } != true) return@launch
                val chapters = try {
                    withContext(Dispatchers.IO) { extraction.chapters.await().chapters }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                if (stillCurrent(generation) && attachedGeneration == generation) {
                    _snapshot.value = _snapshot.value.copy(chapters = chapters)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                preparing.finish(DiagnosticOutcome.CANCELLED, "load_cancelled")
                if (diagnosticContext === context) playbackOperation?.finish(DiagnosticOutcome.CANCELLED, "load_cancelled")
                throw t
            }
            if (!stillCurrent(generation)) return
            if ((t as? PlaybackResolutionException)?.paymentRequired == true) {
                preparing.finish(DiagnosticOutcome.DEFERRED, "payment_required")
                if (diagnosticContext === context) playbackOperation?.finish(DiagnosticOutcome.DEFERRED, "payment_required")
                player.pause()
                userWantsPlay = false
                _snapshot.value = _snapshot.value.copy(isBuffering = false, isPlaying = false,
                    userWantsPlay = false, notice = PlaybackNotice.PAYMENT_REQUIRED,
                    error = null, failureReport = null)
                return
            }
            preparing.finish(DiagnosticOutcome.FAILURE, "prepare_failed", t)
            if (diagnosticContext === context) playbackOperation?.finish(DiagnosticOutcome.FAILURE, "prepare_failed")
            // The previous media stays attached on failure. Pausing it prevents
            // its audio from continuing under the new video's error screen.
            // A failed local load does not pause the receiver; the active Cast
            // session keeps playing.
            player.pause()
            val report = failureReport(t, "prepare")
            _snapshot.value = _snapshot.value.copy(
                isBuffering = false,
                error = report.summary,
                failureReport = report,
            )
        } finally {
            remoteResume?.cancel()
            preparing.finish(DiagnosticOutcome.SUPERSEDED, "generation_replaced")
        }
    }

    override fun stop() {
        PlaybackClaim.videoId = null
        reportWatchBoundary()
        progressWatchdog.reset("cancelled")
        playbackOperation?.finish(DiagnosticOutcome.CANCELLED, "user_stopped")
        nextPlaybackGeneration()
        userWantsPlay = false
        playJob?.cancel()
        rebuildJob?.cancel()
        fallbackPreparation?.cancel(); fallbackPreparation = null
        persistPosition()
        receiverVideoId = null
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(diagnosticTicker)
        performance.reset(elapsed())
        player.stop()
        player.clearMediaItems()
        adaptiveVideoPool = emptyList()
        pinnedQuality = null
        resetAudioSupplement()
        _snapshot.value = PlaybackSnapshot()
        diagnosticContext = null; attachedContext = null
        playbackAttempt = null
        attachCastPlayer(null)
        notificationController?.onStopped()
    }

    private fun reportWatchBoundary() {
        val state = _snapshot.value
        if (state.videoId == null || state.videoId != currentMetadata?.id || attachedGeneration != playbackGeneration.get()) return
        val active = activePlayer()
        onWatchBoundary?.invoke(state.copy(isPlaying = active.isPlaying), active.currentPosition.coerceAtLeast(0))
    }

    override fun setVideoVisible(visible: Boolean) {
        if (videoVisible != visible) performance.stabilize(elapsed())
        videoVisible = visible
    }

    override fun setAudioOnly(enabled: Boolean) {
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled)
            .build()
    }

    override fun setVideoSurface(surface: SurfaceView?) {
        if (surface === videoSurface) return
        playerLog("surface_changed", mapOf("attached" to (surface != null), "valid" to surface?.holder?.surface?.isValid))
        if (surface != null && surface !== videoSurface) surfaceSwitches.incrementAndGet()
        videoSurface?.holder?.removeCallback(surfaceLifecycle)
        invalidateFirstFrame()
        videoSurface = surface
        surface?.holder?.addCallback(surfaceLifecycle)
        performance.stabilize(elapsed())
        player.setVideoSurfaceView(surface)
    }

    private fun activePlayer(): Player = castPlayer ?: player

    override fun playOrPause() {
        // Reaching the end can leave the play intent set; the next tap is replay.
        if (activePlayer().playbackState == Player.STATE_ENDED || !userWantsPlay) play() else pause()
    }

    override fun play() {
        if (_snapshot.value.notice != null) return
        userWantsPlay = true
        performance.stabilize(elapsed())
        playerLog("resume", mapOf("position_ms" to activePlayer().currentPosition))
        resumePlayback(activePlayer())
        notifyTransport(activePlayer().isPlaying)
    }

    override fun pause() {
        userWantsPlay = false
        playerLog("pause", mapOf("position_ms" to activePlayer().currentPosition))
        activePlayer().pause()
        notifyTransport(false)
    }

    private fun notifyTransport(isPlaying: Boolean) {
        notificationController?.onPlayingChanged(
            PlaybackTransport.showAsPlaying(userWantsPlay, isPlaying),
        )
    }

    override fun cancelPending(pausePlayback: Boolean) {
        progressWatchdog.reset("cancelled")
        if (pausePlayback) playbackOperation?.finish(DiagnosticOutcome.CANCELLED, "closed")
        val retainedSource = _snapshot.value.prepared &&
            _snapshot.value.videoId == currentMetadata?.id &&
            attachedContext != null && attachedContext == diagnosticContext &&
            attachedGeneration == playbackGeneration.get()
        val generation = nextPlaybackGeneration()
        // Closing cancels asynchronous work but retains prepared media for
        // reopening. Its new surface frames, errors and end events still belong
        // to this session; a pending replacement must remain invalidated.
        if (retainedSource) attachedGeneration = generation
        playerLog("pending_cancelled", mapOf("retained_source" to retainedSource, "pause_playback" to pausePlayback))
        if (pausePlayback) userWantsPlay = false
        playJob?.cancel()
        rebuildJob?.cancel()
        fallbackPreparation?.cancel(); fallbackPreparation = null
        persistPosition()
        if (pausePlayback) runCatching { activePlayer().pause() }
    }

    /** Headset / notification Play and in-player resume share this path. */
    private fun resumePlayback(p: Player) {
        // Never let both ends roll: handing control to local pauses the TV,
        // and handing control back to the receiver pauses the phone.
        if (p === player) castPlayer?.pause() else player.pause()
        when {
            !_snapshot.value.prepared && _snapshot.value.videoId != null -> {
                if (playJob?.isActive == true) {
                    _snapshot.value = _snapshot.value.copy(userWantsPlay = userWantsPlay)
                } else retry()
            }
            _snapshot.value.error != null -> retry()
            p.playbackState == Player.STATE_ENDED -> replayFromStart(p)
            p.playerError != null -> retry()
            else -> p.play()
        }
    }

    override fun pauseLocal() { player.pause() }

    override fun markReceiverIdentity(videoId: String, title: String, author: String?) {
        receiverVideoId = videoId
        val p = castPlayer ?: return
        publishNotification(p, title, author)
    }

    override fun markReceiverSource(source: CastSource) {
        markReceiverIdentity(source.videoId, source.title, source.author)
        if (castPlayer == null || source.videoId != _snapshot.value.videoId) return
        val video = source.video ?: return
        val label = qualityLabelOf(video.height, video.fps)
        val pool = if (source.audio == null) currentStream?.formats.orEmpty().filter(StreamSelection::isMuxed)
            else castCandidates()
        val compatible = pool.filter { it.url.isNotBlank() && isAvc(it) && it.height <= CAST_MAX_HEIGHT }
            .ifEmpty { pool }
        if (pinnedQuality != null) pinnedQuality = label
        _snapshot.value = _snapshot.value.copy(
            qualityLabel = pinnedQuality,
            activeQuality = label,
            qualities = compatible.filter { it.height > 0 }.sortedByDescending { it.height }
                .map { qualityLabelOf(it.height, it.fps) }.distinct(),
            videoFormat = video,
            audioFormat = source.audio ?: video,
        )
    }

    /** STATE_ENDED ignores playWhenReady: rewind to 0 and start over. */
    private fun replayFromStart(p: Player) {
        // Only the local listener consumes skipSeekInProgress (in
        // onPositionDiscontinuity); the cast listener has none, so setting
        // the flag for a cast replay would leak it into the next LOCAL user
        // seek and wrongly suppress sponsorSkip.onUserSeek there.
        if (p === player) skipSeekInProgress = true
        p.seekTo(0)
        p.play()
    }

    override fun seekTo(positionMs: Long) {
        val p = activePlayer()
        val duration = p.duration
        val target = if (duration > 0) positionMs.coerceAtMost(duration) else positionMs
        p.seekTo(target.coerceAtLeast(0L))
    }

    override fun seekRelative(offsetMs: Long) {
        seekTo(activePlayer().currentPosition + offsetMs)
    }

    override fun setSpeed(speed: Float) {
        if (speed != activePlayer().playbackParameters.speed) performance.stabilize(elapsed())
        val clamped = speed.coerceIn(0.25f, 3f)
        activePlayer().setPlaybackSpeed(clamped)
        // A long-press 2x rate is transient. Persisting it would store a rate
        // the user did not select.
        if (speed != 2f || !speedHoldActive) {
            if (prefs.isEnabled(PreferenceKeys.REMEMBER_PLAYBACK_SPEED)) {
                cache.put(KEY_SPEED, clamped.toString(), PREF_TTL_MS)
            }
        }
    }

    private var speedHoldActive = false

    override fun setSpeedHold(active: Boolean) {
        speedHoldActive = active
    }

    override fun setQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) {
        notificationController?.onQueueNavigation(hasNext, hasPrevious)
    }

    override fun setLoopMode(mode: LoopMode) {
        player.repeatMode = mode.repeatMode
        castPlayer?.repeatMode = mode.repeatMode
        _snapshot.value = _snapshot.value.copy(loopMode = mode)
        cache.put(if (shortsMode) KEY_SHORTS_LOOP_MODE else KEY_LOOP_MODE, mode.persistedValue.toString(), PREF_TTL_MS)
    }

    override fun setShortsMode(enabled: Boolean) {
        if (shortsMode == enabled) return
        shortsMode = enabled
        val mode = rememberedLoopMode()
        player.repeatMode = mode.repeatMode
        castPlayer?.repeatMode = mode.repeatMode
        _snapshot.value = _snapshot.value.copy(loopMode = mode)
    }

    /**
     * Quality change. A pick is a pure track-selection change (instantaneous,
     * keeps position/buffer) on multi-track sources; full rebuild only when
     * the source carries no choice (single rep / muxed / live / pre-prepare).
     */
    override fun setQuality(label: String?) {
        if (castPlayer != null && !localDivergedFromReceiver()) {
            // The receiver's format pool can differ from the paused local tracks. Keep the
            // requested pin until its source is published, including an explicit return to auto.
            pinnedQuality = label
            _snapshot.value = _snapshot.value.copy(qualityLabel = label)
            if (prefs.isEnabled(PreferenceKeys.REMEMBER_QUALITY)) {
                if (label == null) cache.invalidate(KEY_QUALITY) else cache.put(KEY_QUALITY, label, PREF_TTL_MS)
            }
            handler.post { if (castPlayer != null) onCastManifestReload?.invoke() }
            return
        }
        val rebuilding = rebuildJob?.isActive == true || _snapshot.value.error != null
        if (label == null) {
            clearVideoConstraints()
            pinnedQuality = null
            _snapshot.value = _snapshot.value.copy(qualityLabel = null)
            if (prefs.isEnabled(PreferenceKeys.REMEMBER_QUALITY)) cache.invalidate(KEY_QUALITY)
            if (rebuilding) {
                val stream = currentStream ?: return
                val metadata = currentMetadata ?: return
                rebuildAtCurrentPosition(stream, metadata, preferredQuality = null, reloadCast = true)
            }
            return
        }
        if (!rebuilding && switchQualityByTrack(label)) return
        val stream = currentStream ?: return
        val metadata = currentMetadata ?: return
        pinnedQuality = label
        if (prefs.isEnabled(PreferenceKeys.REMEMBER_QUALITY)) {
            cache.put(KEY_QUALITY, label, PREF_TTL_MS)
        }
        rebuildAtCurrentPosition(
            stream, metadata, preferredQuality = label, reloadCast = true,
        )
    }

    /**
     * Pins the video track selector to [label]'s height; false when the source
     * has no supported video track and a rebuild is needed.
     */
    private fun switchQualityByTrack(label: String): Boolean {
        val height = StreamSelection.parseHeight(label)
        if (height <= 0) return false
        val match = findVideoTrack(height, StreamSelection.parseFps(label)) ?: return false
        applyVideoTrackOverride(match)
        pinnedQuality = qualityLabelOf(match.format.height, kotlin.math.round(match.format.frameRate).toInt())
        _snapshot.value = _snapshot.value.copy(qualityLabel = pinnedQuality)
        if (prefs.isEnabled(PreferenceKeys.REMEMBER_QUALITY)) {
            cache.put(KEY_QUALITY, label, PREF_TTL_MS)
        }
        if (castPlayer != null) {
            handler.post { onCastManifestReload?.invoke() }
        }
        return true
    }

    private data class VideoTrackRef(
        val group: Tracks.Group,
        val index: Int,
        val format: MediaFormat,
    )

    /** Align missing qualities to supported tracks using the resolver's fallback policy. */
    private fun findVideoTrack(height: Int, fps: Int): VideoTrackRef? {
        val candidates = mutableListOf<VideoTrackRef>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                candidates += VideoTrackRef(group, i, group.getTrackFormat(i))
            }
        }
        return StreamSelection.selectVideoQuality(candidates, "${height}p${if (fps > 0) fps else ""}",
            { it.format.height }, { kotlin.math.round(it.format.frameRate).toInt() })
    }

    /**
     * Drops every video constraint (overrides + size bounds). First step of any
     * rebuild: a leftover pin from the previous source must not steer the new one.
     */
    private fun clearVideoConstraints() {
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            // clearVideoSizeConstraints only resets max; min must go back to 0.
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearVideoSizeConstraints()
            .setMinVideoSize(0, 0)
            .setExceedVideoConstraintsIfNecessary(true)
            .build()
    }

    /** Height-only pin without an explicit override; the selector adapts within it. */
    private fun pinVideoHeight(height: Int) {
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(Int.MAX_VALUE, height)
            .setMinVideoSize(0, height)
            // Remote manifests may not carry this height. Allow initial selection
            // until onTracksChanged can align the pin to a supported rendition.
            .setExceedVideoConstraintsIfNecessary(true)
            .build()
    }

    /** Deterministic pick: explicit override + matching size constraints. */
    private fun applyVideoTrackOverride(match: VideoTrackRef) {
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(Int.MAX_VALUE, match.format.height)
            .setMinVideoSize(0, match.format.height)
            .setExceedVideoConstraintsIfNecessary(false)
            .setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))
            .build()
    }

    /**
     * Track-derived quality state after every tracks change: honest menu
     * entries, the actually-rendering label, and a re-apply of a pin the
     * source swap dropped. Missing qualities align to the supported track pool.
     */
    private fun updateQualityFromTracks() {
        if (castPlayer != null && !localDivergedFromReceiver()) return
        val active = selectedVideoTrackFormat()
        val activeLabel = active?.let {
            qualityLabelOf(it.height, kotlin.math.round(it.frameRate).toInt())
        }
        val labels = trackQualityLabels()
        var poolMatch: Format? = null
        if (active != null && adaptiveVideoPool.isNotEmpty()) {
            val fps = kotlin.math.round(active.frameRate).toInt()
            poolMatch = adaptiveVideoPool.firstOrNull {
                it.height == active.height && it.fps == fps
            } ?: adaptiveVideoPool.firstOrNull { it.height == active.height }
        }
        _snapshot.value = _snapshot.value.copy(
            qualities = labels,
            activeQuality = activeLabel,
            videoFormat = poolMatch ?: _snapshot.value.videoFormat,
        )
        val pin = pinnedQuality ?: return
        val pinHeight = StreamSelection.parseHeight(pin)
        if (pinHeight <= 0) return
        val match = findVideoTrack(pinHeight, StreamSelection.parseFps(pin))
        if (match == null) {
            if (_snapshot.value.prepared && attachedGeneration == playbackGeneration.get() &&
                player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
                clearVideoConstraints()
            }
            return
        }
        val aligned = qualityLabelOf(match.format.height, kotlin.math.round(match.format.frameRate).toInt())
        if (aligned != pin) {
            pinnedQuality = aligned
            _snapshot.value = _snapshot.value.copy(qualityLabel = aligned)
            playerLog("manual_quality_aligned", mapOf("requested" to pin, "selected" to aligned))
        }
        if (active == null || match.format != active) {
            applyVideoTrackOverride(match)
        }
    }

    /**
     * When HLS exposes several audio renditions, those rows are the menu and a
     * pick is a track override. One player audio track leaves the extractor
     * list in place.
     */
    private fun publishRenditionAudio() {
        val entries = playerAudioEntries()
        val choices = AudioTrackIdentity.renditionChoices(entries)
        if (choices.isEmpty()) return
        val key = _snapshot.value.audioTrackKey
        val renditionKey = AudioTrackIdentity.renditionFor(entries, currentStream?.formats.orEmpty(), audioPreference)
        if (audioPreference == null) audioPreference = renditionKey
        if (choices != _snapshot.value.audioTracks || renditionKey != key) {
            _snapshot.value = _snapshot.value.copy(
                audioTracks = choices,
                audioTrackKey = renditionKey,
            )
        }
        if (renditionKey != null && selectedRenditionKey() != renditionKey) {
            applyRenditionAudio(renditionKey)
        }
    }

    private fun rememberAudio(format: Format?) {
        lastAudioFormat = format
        if (audioPreference == null) audioPreference = format?.let(AudioTrackIdentity::key)?.takeIf { it.isNotBlank() }
    }

    private fun playerAudioEntries(): List<Pair<String?, String?>> {
        val entries = mutableListOf<Pair<String?, String?>>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                entries += format.language to format.label
            }
        }
        return entries
    }

    private fun selectedRenditionKey(): String? {
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                return AudioTrackIdentity.renditionKey(format.language, format.label)
            }
        }
        return null
    }

    private fun applyRenditionAudio(trackKey: String?) {
        val selected = trackKey ?: AudioTrackIdentity.originalRenditionKey(playerAudioEntries(), currentStream?.formats.orEmpty())
        val builder = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(null)
        if (!selected.isNullOrBlank()) {
            val match = findRendition(selected) ?: return
            val language = selected.removePrefix("hls:").substringBefore(':')
            if (language.isNotEmpty()) builder.setPreferredAudioLanguage(language)
            builder.setOverrideForType(
                TrackSelectionOverride(match.group.mediaTrackGroup, match.index),
            )
        }
        trackSelector.parameters = builder.build()
    }

    private fun findRendition(trackKey: String): VideoTrackRef? {
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                if (AudioTrackIdentity.renditionKey(format.language, format.label) == trackKey) {
                    return VideoTrackRef(group, i, format)
                }
            }
        }
        return null
    }

    private fun selectedVideoTrackFormat(): MediaFormat? {
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i)
            }
        }
        return null
    }

    /** Quality menu entries from the live tracks; pool fallback pre-prepare. */
    private fun trackQualityLabels(): List<String> {
        val labels = mutableListOf<String>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                if (f.height > 0) {
                    labels += qualityLabelOf(f.height, kotlin.math.round(f.frameRate).toInt())
                }
            }
        }
        if (labels.isEmpty()) labels += adaptiveVideoPool.map {
            qualityLabelOf(it.height, it.fps)
        }
        return labels.distinct().sortedWith(
            compareByDescending<String> { StreamSelection.parseHeight(it) }
                .thenByDescending { StreamSelection.parseFps(it) },
        )
    }

    private fun qualityLabelOf(height: Int, fps: Int): String =
        "${height}p" + if (fps > 30) "$fps" else ""

    override fun setAudioTrack(trackKey: String?) {
        // A manual dub applies to this video. Every new video starts with Original.
        audioPreference = trackKey
        val renditionMenu = _snapshot.value.audioTracks.any { it.key.startsWith("hls:") }
        if (trackKey?.startsWith("hls:") == true || (trackKey == null && renditionMenu)) {
            _snapshot.value = _snapshot.value.copy(audioTrackKey = trackKey)
            applyRenditionAudio(trackKey)
            return
        }
        val stream = currentStream ?: return
        val metadata = currentMetadata ?: return
        if (trackKey == null) {
            rebuildAtCurrentPosition(stream, metadata, audioTrackKey = null, reloadCast = true)
            return
        }
        // The catalog supplement may expose tracks the current pool lacks; rebuild
        // from that source so the picked track actually decodes. Same identity and
        // generation: the supplement was read for this video under the live session.
        val supplement = audioSupplement
        val target = supplement?.takeIf { (supMetadata, supStream) ->
            supMetadata.id == metadata.id && supStream !== stream &&
                AudioCatalogSupplement.needsSupplement(stream.formats, supStream.formats, trackKey)
        }
        rebuildAtCurrentPosition(
            target?.second ?: stream, target?.first ?: metadata,
            audioTrackKey = trackKey, reloadCast = true,
        )
    }

    /**
     * The user opened the audio menu while the source exposed fewer named
     * tracks than the service can provide (intermittent client answers). One
     * bounded catalog read per video, on demand only, never on the playback path.
     */
    override fun onAudioMenuOpened() {
        val snapshot = _snapshot.value
        if (snapshot.isLive == true || snapshot.audioTracks.size > 1) return
        val metadata = currentMetadata ?: return
        val generation = playbackGeneration.get()
        if (metadata.id == audioSupplementVideo) return
        if (audioSupplementJob?.isActive == true) return
        audioSupplementJob = scope.launch {
            try {
                val (supMetadata, supStream) = withContext(Dispatchers.IO) {
                    extractor.awaitMedia(metadata.id)
                }
                if (currentMetadata?.id != metadata.id || generation != playbackGeneration.get()) return@launch
                audioSupplementVideo = metadata.id
                val known = _snapshot.value.audioTracks.map { it.key }.toSet()
                val extra = AudioCatalogSupplement.extraChoices(known, supStream.formats)
                if (extra.isEmpty()) {
                    AppLog.detail(AppLog.Category.PLAYER, "audio_supplement_empty",
                        mapOf("video_id" to metadata.id))
                    return@launch
                }
                audioSupplement = supMetadata to supStream
                _snapshot.value = _snapshot.value.copy(audioTracks = _snapshot.value.audioTracks + extra)
                AppLog.detail(AppLog.Category.PLAYER, "audio_supplement_added",
                    mapOf("video_id" to metadata.id, "tracks" to extra.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // One bounded attempt per video: a failed probe must not loop behind the menu.
                audioSupplementVideo = metadata.id
                AppLog.detail(AppLog.Category.PLAYER, "audio_supplement_failed",
                    mapOf("video_id" to metadata.id, "reason" to (error.message ?: error.javaClass.simpleName)))
            }
        }
    }

    private fun resetAudioSupplement() {
        audioSupplementJob?.cancel()
        audioSupplementJob = null
        audioSupplement = null
        audioSupplementVideo = null
    }

    /**
     * Caption selection. Off disables the text renderer but keeps the
     * remembered key so turning captions back on restores the same track.
     */
    override fun setSubtitle(trackKey: String?): Boolean {
        val previousKey = _snapshot.value.subtitleKey
        if (trackKey != null &&
            !resolver.hasSubtitleTrack(currentStream?.subtitles.orEmpty(), trackKey)
        ) {
            return false
        }
        if (trackKey == null) {
            cache.put(KEY_SUBTITLE_OFF, "1", PREF_TTL_MS)
            _snapshot.value = _snapshot.value.copy(subtitleKey = null)
        } else {
            cache.invalidate(KEY_SUBTITLE_OFF)
            cache.put(KEY_SUBTITLE, trackKey, PREF_TTL_MS)
            _snapshot.value = _snapshot.value.copy(subtitleKey = trackKey)
        }
        val stream = currentStream
        val metadata = currentMetadata
        if (stream != null && metadata != null && (trackKey != null || previousKey != null)) {
            rebuildAtCurrentPosition(stream, metadata, subtitleKey = trackKey)
        }
        applySubtitle(trackKey)
        return true
    }

    private fun applySubtitle(trackKey: String?) {
        val iso = trackKey?.let { SubtitleSelection.languageOf(it) }
        trackSelector.parameters = trackSelector.parameters.buildUpon()
            .setPreferredTextLanguage(iso)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, trackKey == null)
            .build()
        _snapshot.value = _snapshot.value.copy(
            subtitleEnabled = trackKey != null,
            subtitleKey = trackKey,
        )
    }

    override fun cancelSponsorSkip() {
        sponsorSkip.cancelPending()
        updateSnapshotFromSponsor()
    }

    override fun skipSponsorNow() {
        if (activePlayer() !== player) return
        val target = sponsorSkip.skipPending()
        if (target != null) {
            skipSeekInProgress = true
            player.seekTo(target)
        }
    }

    override fun skipSponsorChipSegment() {
        if (activePlayer() !== player) return
        val target = sponsorSkip.chipTarget(player.currentPosition, sponsorBlock.currentSegments)
        if (target != null) {
            if (sponsorSkip.chipIsHighlight) sponsorSkip.dismissHighlightChip()
            skipSeekInProgress = true
            player.seekTo(target)
        }
    }

    /**
     * Cast-friendly stream pick: H.264 MP4 + AAC decode on every receiver
     * (sidx-friendly); VP9/AV1/Opus only when nothing else matched, capped at
     * 1080p so the LAN proxy is not saturated. A muxed stream carries its own
     * audio and may serve as the video representation alone.
     */
    override fun currentCastSource(): CastSource? {
        val meta = currentMetadata ?: return null
        val id = _snapshot.value.videoId ?: return null
        // A failed load (extraction error) leaves the previous video's stream
        // data in place; casting it under the NEW id would play the old video.
        if (meta.id != id) return null
        val pool = castCandidates()
        val preferred = _snapshot.value.qualityLabel
        val avcCapped = pool.filter { isAvc(it) && it.height <= CAST_MAX_HEIGHT }
        val video = StreamSelection.selectVideo(avcCapped, preferred)
            ?: pool.firstOrNull { isAvc(it) && it.height <= CAST_MAX_HEIGHT }
            ?: pool.firstOrNull { isAvc(it) }
            ?: pool.firstOrNull { it.height <= CAST_MAX_HEIGHT }
            ?: pool.firstOrNull()
        val audio = castAudio(video)
        if (video == null || audio == null) return null
        return CastSource(
            videoId = id,
            title = meta.title.ifBlank { _snapshot.value.title },
            durationSec = meta.duration,
            video = video,
            audio = if (audio == video) null else audio,
            author = meta.author,
            // Public CDN URL. The receiver fetches artwork directly; the LAN
            // proxy is not required, and CORS does not apply.
            thumbnailUrl = VideoId.thumbnailUrl(id),
        )
    }

    /**
     * DLNA renderers play one progressive file: pick the best muxed format
     * (H.264/AAC first, 1080p cap like the chromecast pick). Separate A/V
     * tracks have no receiver-side remux, so they are not considered; null
     * means the caller surfaces the no-muxed failure instead of casting.
     */
    override fun currentMuxedCastSource(): CastSource? {
        val meta = currentMetadata ?: return null
        val id = _snapshot.value.videoId ?: return null
        if (meta.id != id) return null
        val pool = currentStream?.formats
            ?.filter(StreamSelection::isMuxed)
            ?.filter { it.url.isNotBlank() }
            .orEmpty()
        val preferred = _snapshot.value.qualityLabel
        val avcCapped = pool.filter { isAvc(it) && it.height <= CAST_MAX_HEIGHT }
        val video = StreamSelection.selectVideo(avcCapped, preferred)
            ?: pool.firstOrNull { isAvc(it) && it.height <= CAST_MAX_HEIGHT }
            ?: pool.firstOrNull { isAvc(it) }
            ?: pool.firstOrNull { it.height <= CAST_MAX_HEIGHT }
            ?: pool.firstOrNull()
            ?: return null
        return CastSource(
            videoId = id,
            title = meta.title.ifBlank { _snapshot.value.title },
            durationSec = meta.duration,
            video = video,
            audio = null,
            author = meta.author,
            thumbnailUrl = VideoId.thumbnailUrl(id),
        )
    }

    override suspend fun prepareCastSource(): CastSource? = prepareThenPick { currentCastSource() }

    override suspend fun prepareMuxedCastSource(): CastSource? = prepareThenPick { currentMuxedCastSource() }

    private suspend fun prepareThenPick(pick: () -> CastSource?): CastSource? {
        pick()?.let { return it }
        val id = _snapshot.value.videoId ?: return null
        val generation = playbackGeneration.get()
        val media = try { withContext(Dispatchers.IO) { extractor.awaitMedia(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        if (!stillCurrent(generation) || _snapshot.value.videoId != id || media.first.id != id) return null
        currentStream = media.second
        return pick()
    }

    /**
     * Video pool for casting: the manifest pool when present, else muxed. All
     * entries carry sidx ranges — without them the receiver cannot address
     * segments and downloads the whole stream.
     */
    private fun castCandidates(): List<Format> =
        (adaptiveVideoPool.ifEmpty {
            currentStream?.formats?.filter { it.videoOnly }
                ?.ifEmpty { currentStream?.formats?.filter(StreamSelection::isMuxed).orEmpty() }
                ?: emptyList()
        }).filter { it.hasDashRanges }

    /**
     * Audio for casting: current identity first, then AAC (default receivers
     * cannot decode Opus). A muxed candidate needs no separate audio.
     */
    private fun castAudio(video: Format?): Format? {
        if (video != null && !video.videoOnly) return video
        val audioOnly = currentStream?.formats?.filter { it.audioOnly && it.hasDashRanges }
            ?.ifEmpty { listOfNotNull(lastAudioFormat).filter { it.hasDashRanges } }
            .orEmpty()
        val preferredKey = lastAudioFormat?.let { AudioTrackIdentity.key(it) }
            ?: _snapshot.value.audioTrackKey
        return CastAudioSelection.select(audioOnly, preferredKey, ::isAac)
    }

    private fun isAvc(f: Format): Boolean =
        f.mimeType.contains("avc", true) || (f.codec ?: "").startsWith("avc")

    private fun isAac(f: Format): Boolean =
        f.mimeType.contains("mp4a", true) || (f.codec ?: "").startsWith("mp4a")

    override fun attachCastPlayer(delegate: Player?): Boolean {
        val keepLocal = localDivergedFromReceiver() && delegate == null
        // The listener lives on the CAST player; removing it from the local
        // one is a silent no-op and leaks one listener per session.
        castListener?.let { l -> runCatching { castPlayer?.removeListener(l) } }
        castListener = null
        castPlayer = delegate
        skipSeekInProgress = false
        sponsorSkip.reset()
        if (delegate == null) {
            receiverVideoId = null
            if (pinnedQuality == null) clearVideoConstraints()
            else findVideoTrack(StreamSelection.parseHeight(pinnedQuality!!), StreamSelection.parseFps(pinnedQuality!!))
                ?.let(::applyVideoTrackOverride)
            handler.removeCallbacks(ticker)
            if (player.isPlaying) handler.post(ticker)
            publishNotification(player)
            publish()
            return keepLocal
        }
        receiverVideoId = _snapshot.value.videoId
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (localDivergedFromReceiver()) return
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.post(ticker)
                else persistPosition()
                publish()
                notifyTransport(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (localDivergedFromReceiver()) return
                publish()
                if (playbackState == Player.STATE_ENDED) handleEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (castPlayer == null) return
                val pos = castPlayer?.currentPosition ?: 0L
                val keepLocal = attachCastPlayer(null)
                if (!keepLocal) {
                    if (pos > 0L) seekTo(pos)
                    play()
                }
                onCastError?.invoke()
            }
        }
        delegate.addListener(listener)
        castListener = listener
        delegate.repeatMode = _snapshot.value.loopMode.repeatMode
        delegate.setPlaybackSpeed(player.playbackParameters.speed)
        // Local surface blacks out; keep it paused and silent.
        player.pause()
        // The notification/MediaSession must follow the delegation too, or
        // headset keys and the play state keep driving the paused local player.
        publishNotification(delegate)
        publish()
        return false
    }

    /**
     * Re-extracts [videoId] (the published proxy identity, not necessarily
     * the phone's current media) and returns a fresh URL for token "v"/"a".
     */
    override fun refreshCastUrl(token: String, videoId: String?, itag: Int?): String? {
        val id = videoId ?: _snapshot.value.videoId ?: return null
        val generation = playbackGeneration.get()
        val reportingGeneration = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation
        return runCatching {
            runBlocking {
                refreshCastMutex.withLock {
                    val fresh = withTimeoutOrNull(45_000) {
                        withContext(Dispatchers.IO) {
                            val extracted = extractor.extractFresh(id).stream.await()
                            val url = pickPublishedUrl(extracted, token, itag)
                            val localMeta = currentMetadata
                            val source = if (localMeta != null && localMeta.id == id) {
                                resolveSource(extracted, localMeta)
                            } else {
                                null
                            }
                            handler.post {
                                // A proxy may still need its URL after local playback changes.
                                // Only the captured source may update local state and reporting.
                                if (stillCurrent(generation) && attachedGeneration == generation &&
                                    _snapshot.value.videoId == id && reportingGeneration ==
                                    com.hhst.youtubelite.data.media.MediaSession.snapshot().generation) {
                                    currentStream = extracted
                                    onWatchContext?.invoke(id, extracted.tracking, reportingGeneration)
                                    if (source != null) {
                                        rememberAudio(source.audioFormat)
                                        adaptiveVideoPool = source.videoFormats
                                    }
                                }
                            }
                            url
                        }
                    }
                    fresh
                }
            }
        }.getOrNull()
    }

    override fun backupCastUrl(token: String, videoId: String?, itag: Int?, failedProfile: String?): String? {
        if (currentMetadata?.id != videoId) return null
        val profile = failedProfile?.let(StreamClient::byName) ?: return null
        val stream = currentStream ?: return null
        return pickPublishedUrl(stream.excluding(profile), token, itag)
    }

    private fun pickPublishedUrl(stream: Stream, token: String, itag: Int?): String? {
        val previous = if (token == "a") lastAudioFormat else _snapshot.value.videoFormat?.takeIf { itag == null || it.itag == itag }
        if (previous?.formatKey != null) {
            if (previous.resourceIdentity.isNullOrBlank()) return null
            return stream.formats.firstOrNull { it.formatKey == previous.formatKey && it.resourceIdentity == previous.resourceIdentity }?.url
        }
        if (itag != null) {
            stream.formats.filter { it.itag == itag }.distinctBy { it.audioTrackId to it.codec }.singleOrNull()?.url?.let { return it }
        }
        return stream.formats.firstOrNull {
            if (token == "a") it.audioOnly else !it.audioOnly
        }?.url
    }

    /**
     * Applies the remembered quality as track-selection constraints after a
     * fresh adaptive resolve. A new player/selector starts unconstrained, so
     * without this the first frames would render at the pool's top quality
     * before any later constraint lands.
     */
    private fun applyPreferredQualityConstraint(source: MediaSourceResolver.Resolved) {
        val label = pinnedQuality ?: return
        // Direct/synthetic sources already chose a playable fallback. Remote
        // manifests align once their supported tracks arrive. Keep the saved
        // preference for the next video; only this session's pin is adjusted.
        val aligned = source.videoFormat?.takeIf { it.height > 0 }?.let {
            qualityLabelOf(it.height, it.fps)
        } ?: label
        val height = StreamSelection.parseHeight(aligned)
        if (height <= 0) return
        pinnedQuality = aligned
        pinVideoHeight(height)
        // The selector is hard-pinned; the badge must not claim "Auto".
        _snapshot.value = _snapshot.value.copy(qualityLabel = aligned)
    }

    /** Single resolve entry point for both load and cast-refresh paths. */
    private fun resolveSource(
        stream: Stream,
        metadata: Metadata,
        context: DiagnosticContext? = diagnosticContext,
    ): MediaSourceResolver.Resolved {
        val generation = playbackGeneration.get()
        val slow = if (metadata.isLive || stream.hlsUrl.isNullOrBlank() || stream.hlsRequestPlan == null) null else ({
            handler.post {
                if (generation == playbackGeneration.get() && currentStream === stream &&
                    !player.isPlaying && fallbackPreparation == null && castPlayer == null &&
                    !com.hhst.youtubelite.data.media.MediaSession.privateBrowsing() &&
                    !prefs.isEnabled(PreferenceKeys.DATA_SAVER)) {
                    val identity = com.hhst.youtubelite.data.media.MediaSession.snapshot().generation
                    fallbackPreparation = scope.launch {
                        val started = elapsed()
                        try {
                            val bytes = resolver.prepareHlsFallback(stream) {
                                if (Thread.currentThread().isInterrupted || snapshot.value.videoId != metadata.id ||
                                    com.hhst.youtubelite.data.media.MediaSession.snapshot().generation != identity ||
                                    com.hhst.youtubelite.data.media.MediaSession.privateBrowsing() ||
                                    prefs.isEnabled(PreferenceKeys.DATA_SAVER)) throw java.io.InterruptedIOException("HLS_PREPARATION_STOPPED")
                            }
                            playerLog("fallback_manifest_prepared", mapOf("bytes" to bytes, "duration_ms" to elapsed()-started))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            playerLog("fallback_manifest_skipped", mapOf("kind" to error.javaClass.simpleName, "duration_ms" to elapsed()-started))
                        }
                    }
                }
            }
            Unit
        })
        return resolver.resolve(stream, metadata, preferredQuality = pinnedQuality,
            audioTrackKey = audioPreference, forceMuxed = muxedFallbackActive,
            subtitleKey = rememberedSubtitleKey(), diagnosticContext = context, onSlowInitialRead = slow)
    }

    private fun handleEnded() {
        if (playJob?.isActive == true || rebuildJob?.isActive == true) return
        if (attachedGeneration != playbackGeneration.get()) return
        progressWatchdog.reset("completed")
        playbackOperation?.finish(DiagnosticOutcome.SUCCESS, "ended")
        persistPosition()
        when (_snapshot.value.loopMode) {
            LoopMode.LOOP_ONE -> Unit // REPEAT_MODE_ONE already replays
            LoopMode.PAUSE_AT_END -> Unit
            LoopMode.QUEUE_NEXT, LoopMode.QUEUE_RANDOM -> {
                if (localDivergedFromReceiver()) return
                onEnded?.invoke()
            }
        }
    }

    private fun handleError(error: PlaybackException) {
        if (attachedGeneration != playbackGeneration.get()) return
        if (castPlayer != null && !localDivergedFromReceiver()) {
            playerLog("local_source_failed_while_casting", mapOf("error_code" to error.errorCode))
            publish()
            return
        }
        val videoId = _snapshot.value.videoId
        val attempt = playbackAttempt?.takeIf { it.videoId == videoId }
        val metadata = currentMetadata
        // Ownership: an error fired from media a pending load is replacing
        // (old video still attached) must not drive recovery — same
        // metadata/snapshot identity guard as refreshCastUrl.
        if (metadata != null && metadata.id != videoId) return
        if (_snapshot.value.subtitleKey != null && (isTextRendererError(error) || isSubtitleSourceError(error))) {
            recoverFromTextError(error)
            return
        }
        var reason = recoveryReason(error)
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val failedRequest = causes.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull()
        val invalidResponse = causes.filterIsInstance<InvalidMediaResponseException>().firstOrNull()
        playerLog("recovery_decision", mapOf("reason" to (reason?.name ?: "unsupported"), "light_attempts" to (attempt?.lightRefreshes ?: 0), "full_attempts" to (attempt?.fullRefreshes ?: 0)), critical = false)
        val stream = currentStream

        val rejection = reason?.rejection
        val failedUri = if (rejection != null) failingUri(error) else null
        val rejectedProfile = if (videoId != null && rejection != null) failedUri?.let {
            extractor.noteMediaRejected(videoId, it.toString(), rejection, diagnosticContext)
        } else null
        if (rejection != null) playerLog("recovery_client_feedback", mapOf(
            "applied" to (rejectedProfile != null), "client" to rejectedProfile?.name,
            "reason" to if (rejectedProfile != null) rejection.code else "unknown_or_stale_request_plan"))
        attempt?.recordRecovery(mapOf(
            "reason" to reason?.name, "error_name" to error.errorCodeName, "client" to (rejectedProfile ?: invalidResponse?.client)?.name,
            "http_status" to (causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode ?: invalidResponse?.statusCode),
            "content_type" to invalidResponse?.contentType, "transport_method" to invalidResponse?.method,
            "cause" to causes.lastOrNull()?.let { DiagnosticRedaction.text("${it.javaClass.name}: ${it.message.orEmpty()}").take(512) },
            "request" to failedRequest?.let { DiagnosticRedaction.resource(it.dataSpec.uri.toString()) },
        ))

        // A failed CDN connection does not invalidate the client or its tokens. Reuse
        // the already issued HLS path before re-extracting the same direct URLs.
        if (reason == RecoveryReason.TRANSPORT_FAILED &&
            stream != null && metadata != null && !metadata.isLive &&
            _snapshot.value.videoFormat != null && !stream.hlsUrl.isNullOrBlank() &&
            attempt?.tryTransportManifest() == true) {
            val manifest = Stream().also { it.copyFrom(stream); it.formats = emptyList() }
            playerLog("recovery_transport_manifest", mapOf("protocol" to "HLS"))
            rebuildAtCurrentPosition(manifest, metadata, startMs = player.currentPosition, reloadCast = true)
            return
        }

        // Refresh the source once with the current session and tokens. A transport failure
        // is evidence about the connection, so it must not reset tokens or blame a client.
        if (reason?.needsLightRefresh == true) {
            if (videoId != null && attempt?.tryLightRefresh() == true) {
                playerLog("recovery_light_refresh", mapOf("reason" to reason.name, "attempt" to attempt.lightRefreshes))
                val position = player.currentPosition
                nextPlaybackGeneration()
                playJob?.cancel()
                rebuildJob?.cancel()
                playJob = scope.launch {
                    _snapshot.value = _snapshot.value.copy(isBuffering = true, error = null)
                    skipSeekInProgress = true
                    withContext(Dispatchers.IO) { extractor.invalidateStream(videoId) }
                    loadInternal(
                        videoId, reExtract = false,
                        requestUrl = _snapshot.value.url,
                        startPositionMs = position,
                    )
                }
                return
            }
            // A local account/route fence is not a server refusal. Do not consume
            // the 403 fallback chain or reset the challenge runtime for it.
            reason = reason.afterLightBudget()
        }

        if (reason?.rejection != null && stream != null && metadata != null && attempt?.tryBackup() == true) {
            // The failed request owns its client. Selected video/audio and the current manifest
            // can belong to different clients, especially after a fresh extraction or prefetch.
            val profile = rejectedProfile ?: stream.formats.firstOrNull {
                it.url == failedUri?.toString()
            }?.requestPlan?.client
            val backup = profile?.let(stream::excluding)
            if (backup != null && runCatching { resolveSource(backup, metadata) }.isSuccess) {
                currentStream = backup
                rebuildAtCurrentPosition(backup, metadata, startMs = player.currentPosition, reloadCast = true)
                return
            }
        }

        // Adaptive failure → muxed only if a muxed itag the resolver would
        // actually select can play (sidx ranges or a poToken). VR formats are
        // excluded by preferPlayable; counting them viable makes the forceMuxed
        // rebuild throw instead of reaching the re-extract recovery below.
        if (reason == RecoveryReason.TRANSPORT_FAILED && !muxedFallbackActive && stream != null && metadata != null) {
            val muxedViable = stream.formats.any {
                StreamSelection.isMuxed(it) && !StreamSelection.isAndroidVr(it) &&
                    (it.requestPlan != null || it.hasDashRanges || it.url.contains("&pot="))
            }
            if (muxedViable) {
                val position = player.currentPosition
                muxedFallbackActive = true
                rebuildAtCurrentPosition(stream, metadata, forceMuxed = true, startMs = position, reloadCast = true)
                return
            }
        }

        // One full session/token refresh, followed by one existing compatible backup.
        if (reason?.rejection != null &&
            videoId != null && attempt?.tryFullRefresh() == true
        ) {
            muxedFallbackActive = false
            val position = player.currentPosition
            nextPlaybackGeneration()
            playJob?.cancel()
            rebuildJob?.cancel()
            playJob = scope.launch {
                _snapshot.value = _snapshot.value.copy(isBuffering = true, error = null)
                skipSeekInProgress = true
                loadInternal(
                    videoId, reExtract = true,
                    requestUrl = _snapshot.value.url,
                    startPositionMs = position,
                )
            }
            return
        }

        val report = failureReport(error, "playback")
        _snapshot.value = _snapshot.value.copy(
            isBuffering = false,
            // play()/playOrPause() check this to reload instead of issuing a
            // no-op playWhenReady against an idle-with-error player.
            prepared = false,
            error = report.summary,
            failureReport = report,
        )
        playbackOperation?.finish(DiagnosticOutcome.FAILURE, reason?.name ?: "playback_error")
    }

    private fun recoveryReason(error: PlaybackException): RecoveryReason? = PlaybackRecovery.classify(error)

    private fun failingUri(error: PlaybackException): Uri? {
        val seen = mutableSetOf<Throwable>()
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            if (cause is HttpDataSource.HttpDataSourceException && PlaybackRecovery.classify(cause)?.rejection != null) {
                return cause.dataSpec.uri
            }
            cause = cause.cause
        }
        return null
    }

    private fun isTextRendererError(error: PlaybackException): Boolean {
        val exo = error as? ExoPlaybackException
        if (exo != null && exo.type == ExoPlaybackException.TYPE_RENDERER) {
            val mime = exo.rendererFormat?.sampleMimeType.orEmpty()
            if (mime.startsWith("text/") ||
                mime.contains("ttml", ignoreCase = true) ||
                mime.contains("x-media3-cues", ignoreCase = true)
            ) {
                return true
            }
            if (exo.rendererName?.contains("Text", ignoreCase = true) == true) return true
        }
        var cause: Throwable? = error
        while (cause != null) {
            val msg = cause.message.orEmpty()
            if (msg.contains("Legacy decoding is disabled", ignoreCase = true) ||
                msg.contains("application/ttml+xml", ignoreCase = true) ||
                msg.contains("application/x-media3-cues", ignoreCase = true)
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun isSubtitleSourceError(error: PlaybackException): Boolean {
        val urls = SubtitleSelection.select(currentStream?.subtitles.orEmpty(), _snapshot.value.subtitleKey)
            .map { Uri.parse(it.url) }.toSet()
        return generateSequence<Throwable>(error) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.HttpDataSourceException>().any { it.dataSpec.uri in urls }
    }

    private fun recoverFromTextError(error: PlaybackException) {
        val stream = currentStream ?: return
        val metadata = currentMetadata ?: return
        playerLog("subtitle_disabled", mapOf("error_code" to error.errorCode), error)
        applySubtitle(null)
        // Disabling the renderer leaves a failed merged source attached. Remove that source too.
        rebuildAtCurrentPosition(stream, metadata, subtitleKey = null)
    }


    private fun rebuildAtCurrentPosition(
        stream: Stream,
        metadata: Metadata,
        preferredQuality: String? = pinnedQuality,
        audioTrackKey: String? = _snapshot.value.audioTrackKey,
        forceMuxed: Boolean = muxedFallbackActive,
        startMs: Long = player.currentPosition,
        subtitleKey: String? = _snapshot.value.subtitleKey,
        reloadCast: Boolean = false,
    ) {
        val speed = activePlayer().playbackParameters.speed
        if (preferredQuality != null) pinnedQuality = preferredQuality
        val generation = nextPlaybackGeneration()
        val context = beginDiagnostics(metadata.id, generation)
        val rebuilding = AppLog.operation(AppLog.Category.PLAYER, "rebuild", context.child(), mapOf("quality" to preferredQuality, "force_muxed" to forceMuxed))
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            _snapshot.value = _snapshot.value.copy(isBuffering = true)
            try {
                val source = withContext(Dispatchers.IO) {
                    resolver.resolve(
                        stream, metadata, preferredQuality, audioTrackKey, forceMuxed,
                        subtitleKey,
                        diagnosticContext = context,
                    )
                }
                coroutineContext.ensureActive()
                if (!stillCurrent(generation)) return@launch
                // Audio discovery can rebuild from a different catalog. Keep it
                // as the active pool for subsequent quality and Default picks.
                currentStream = stream
                currentMetadata = metadata
                // Clear just before the swap: the old source keeps playing (and
                // its pin) while the resolve runs and must not adapt up in the gap.
                clearVideoConstraints()
                pinnedQuality = preferredQuality
                applyPreferredQualityConstraint(source)
                attachedContext = context
                player.setMediaSource(source.mediaSource, startMs)
                player.setPlaybackSpeed(speed)
                player.prepare()
                attachedGeneration = generation
                // A rebuild while casting retargets the paused local player
                // only (quality/audio picks are remembered and take effect
                // when the session ends); the receiver keeps playing until
                // onCastManifestReload recasts.
                player.playWhenReady = castPlayer == null && userWantsPlay
                rememberAudio(source.audioFormat)
                adaptiveVideoPool = source.videoFormats
                val discoveredAudio = audioSupplement?.takeIf { it.first.id == metadata.id }?.second?.formats.orEmpty()
                val audioChoices = AudioTrackIdentity.choices(stream.formats + discoveredAudio)
                _snapshot.value = _snapshot.value.copy(
                    qualityLabel = pinnedQuality,
                    audioTrackKey = source.audioFormat?.let { AudioTrackIdentity.key(it) }
                        ?: audioTrackKey,
                    audioTracks = audioChoices,
                    subtitleKey = subtitleKey,
                    prepared = true,
                    isBuffering = false,
                    error = null,
                    failureReport = null,
                    videoFormat = source.videoFormat,
                    audioFormat = source.audioFormat,
                )
                publishRenditionAudio()
                rebuilding.finish(DiagnosticOutcome.SUCCESS, "media_source_submitted")
                if (reloadCast && castPlayer != null) {
                    handler.post { onCastManifestReload?.invoke() }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) { rebuilding.finish(DiagnosticOutcome.CANCELLED, "rebuild_cancelled"); throw t }
                if (!stillCurrent(generation)) return@launch
                rebuilding.finish(DiagnosticOutcome.FAILURE, "rebuild_failed", t)
                if (diagnosticContext == context) playbackOperation?.finish(DiagnosticOutcome.FAILURE, "rebuild_failed", t)
                val report = failureReport(t, "rebuild")
                if (castPlayer != null && !localDivergedFromReceiver()) {
                    publish()
                } else {
                    _snapshot.value = _snapshot.value.copy(isBuffering = false, error = report.summary, failureReport = report)
                }
            } finally { rebuilding.finish(DiagnosticOutcome.SUPERSEDED, "generation_replaced") }
        }
    }

    /**
     * `use_original_title`: swaps the localized title for the oEmbed canonical
     * one. Off-main fetch; a stale result is dropped when the video changed.
     */
    private fun maybeFetchOriginalTitle(videoId: String, localizedTitle: String) {
        val fetcher = titleFetcher ?: return
        if (!prefs.isEnabled(PreferenceKeys.USE_ORIGINAL_TITLE)) return
        if (localizedTitle.isBlank()) return
        scope.launch {
            val original = withContext(Dispatchers.IO) { fetcher.fetchOriginalTitle(videoId) }
            if (original != null &&
                videoId == _snapshot.value.videoId &&
                original != _snapshot.value.title
            ) {
                _snapshot.value = _snapshot.value.copy(title = original)
                // Recast in progress: the receiver still plays the previous video,
                // so this title is not published on its notification.
                publishNotification(activePlayer(), title = original)
            }
        }
    }

    private fun persistPosition() {
        // Load fence: with a load in flight the snapshot's videoId is the NEW
        // video while position/duration still come from the old player —
        // persisting would write the old position under the new video's key.
        if (!_snapshot.value.prepared || com.hhst.youtubelite.data.media.MediaSession.privateBrowsing()) return
        val videoId = _snapshot.value.videoId ?: return
        val p = if (localDivergedFromReceiver()) player else activePlayer()
        val ended = p.playbackState == Player.STATE_ENDED
        when (PlaybackProgress.write(p.currentPosition, p.duration, ended)) {
            PlaybackProgress.Write.CLEAR -> cache.invalidate(progressKey(videoId))
            PlaybackProgress.Write.SAVE ->
                if (prefs.isEnabled(PreferenceKeys.REMEMBER_LAST_POSITION)) {
                    cache.put(
                        progressKey(videoId),
                        ResumePoint(p.currentPosition),
                        PROGRESS_TTL_MS,
                    )
                }
            PlaybackProgress.Write.SKIP -> Unit
        }
    }

    private fun rememberedSpeed(): Float =
        if (prefs.isEnabled(PreferenceKeys.REMEMBER_PLAYBACK_SPEED)) {
            cache.get(KEY_SPEED, String::class.java)?.toFloatOrNull() ?: 1f
        } else 1f

    private fun rememberedLoopMode(): LoopMode =
        cache.get(if (shortsMode) KEY_SHORTS_LOOP_MODE else KEY_LOOP_MODE, String::class.java)
            ?.toIntOrNull()
            ?.let(LoopMode::fromPersisted)
            ?.takeUnless { shortsMode && it == LoopMode.QUEUE_RANDOM }
            ?: if (shortsMode) LoopMode.LOOP_ONE else LoopMode.QUEUE_NEXT

    private fun rememberedQuality(): String? =
        if (prefs.isEnabled(PreferenceKeys.REMEMBER_QUALITY)) {
            cache.get(KEY_QUALITY, String::class.java)
        } else null

    private fun playbackDemand(videoId: String? = _snapshot.value.videoId) = PlaybackDemand(
        (if (videoId == _snapshot.value.videoId) pinnedQuality else rememberedQuality())
            ?.let(StreamSelection::parseHeight) ?: 0,
        audioPreference?.takeIf { videoId == _snapshot.value.videoId && it.startsWith("id:") }?.removePrefix("id:"), false,
        setOf("avc", "vp9", "vp09", "hvc", "hev") +
            if (CodecCapabilities.isDecodable(
                    Format(codec = "av01", height = 1080))) setOf("av01") else emptySet())

    override fun preparePlayback(urlOrId: String) {
        if (PageKind.isShorts(urlOrId)) return
        // Playback owns this video now: stop warming it so the prefetch cannot take the pipe.
        PlaybackClaim.videoId = VideoId.parse(urlOrId)
        extractor.preparePlayback(urlOrId, playbackDemand(VideoId.parse(urlOrId)))
    }

    override fun prefetchDemand(videoId: String): PlaybackDemand = playbackDemand(videoId)

    private fun rememberedSubtitleKey(): String? {
        if (cache.get(KEY_SUBTITLE_OFF, String::class.java) != null) return null
        return cache.get(KEY_SUBTITLE, String::class.java)
    }

    private fun rememberedPosition(videoId: String, durationSec: Long): Long {
        if (!prefs.isEnabled(PreferenceKeys.REMEMBER_LAST_POSITION) ||
            com.hhst.youtubelite.data.media.MediaSession.privateBrowsing()) return 0L
        val point = cache.get(progressKey(videoId), ResumePoint::class.java) ?: return 0L
        val durationMs = durationSec * 1000
        return if (point.positionMs > PlaybackProgress.SAFE_ZONE_MS &&
            (durationMs <= 0 || point.positionMs < durationMs - PlaybackProgress.SAFE_ZONE_MS)
        ) point.positionMs else 0L
    }

    private fun progressKey(videoId: String): String {
        val identity = com.hhst.youtubelite.data.media.MediaSession.snapshot()
        val owner = com.hhst.youtubelite.player.QueueOwner.from(identity.signedIn, identity.accountId, identity.onBehalfOfUser)
        return PlaybackProgress.cacheKey(videoId, owner.id)
    }

    private fun publish() {
        // Load fence: while a load is in flight the snapshot already carries
        // the NEW video id but the player still holds the previous one —
        // publishing here would label the old timeline/duration with the new
        // id (and hide the loading spinner). Listeners and the ticker both
        // route through this single gate.
        if (!_snapshot.value.prepared) return
        val p = if (localDivergedFromReceiver()) player else activePlayer()
        val s = _snapshot.value
        _snapshot.value = s.copy(
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            userWantsPlay = userWantsPlay,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.takeIf { it > 0 } ?: s.durationMs,
            bufferedPositionMs = p.bufferedPosition.coerceAtLeast(0L),
            speed = p.playbackParameters.speed,
            ended = p.playbackState == Player.STATE_ENDED,
            sponsorCountdownSec = sponsorSkip.countdownSec,
            sponsorChip = sponsorSkip.chipActive,
            sponsorChipHighlight = sponsorSkip.chipIsHighlight,
            discontinuityEpoch = discontinuityEpoch.get(),
        )
    }

    private fun updateSnapshotFromSponsor() {
        val s = _snapshot.value
        if (s.sponsorCountdownSec != sponsorSkip.countdownSec ||
            s.sponsorChip != sponsorSkip.chipActive ||
            s.sponsorChipHighlight != sponsorSkip.chipIsHighlight
        ) {
            _snapshot.value = s.copy(
                sponsorCountdownSec = sponsorSkip.countdownSec,
                sponsorChip = sponsorSkip.chipActive,
                sponsorChipHighlight = sponsorSkip.chipIsHighlight,
            )
        }
    }

    private fun stillCurrent(generation: Long): Boolean =
        generation == playbackGeneration.get()

    private fun publishNotification(
        player: Player,
        title: String = _snapshot.value.title,
        author: String? = _snapshot.value.author,
        videoId: String? = _snapshot.value.videoId,
    ) {
        if (castPlayer != null &&
            receiverVideoId != null && receiverVideoId != _snapshot.value.videoId
        ) {
            return
        }
        val id = videoId ?: return
        notificationController?.onLoaded(player, title, author, VideoId.thumbnailUrl(id))
    }

    private fun localDivergedFromReceiver(): Boolean {
        val id = _snapshot.value.videoId ?: return false
        val recv = receiverVideoId ?: return false
        return id != recv
    }

    private fun playbackFailed(t: Throwable): String {
        Log.w(TAG, "playback failed", t)
        val causes = generateSequence(t) { it.cause }.take(12).toList()
        val verification = causes.filterIsInstance<PlaybackResolutionException>()
            .any { it.error.kind == com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION }
        return appContext.getString(when {
            verification -> R.string.player_verification_required
            causes.any { it.message == "LIVE_STREAM_SOURCE_UNAVAILABLE" } -> R.string.player_live_unavailable
            else -> PlaybackNetworkMessage.resource(t, AndroidDiagnosticState.networkSnapshot()["connected"] as? Boolean)
                ?: R.string.player_error
        })
    }

    private fun failureReport(error: Throwable, phase: String): PlaybackFailureReport {
        val state = _snapshot.value
        val attempt = playbackAttempt?.takeIf { it.videoId == state.videoId }
        val stream = currentStream.takeIf { currentMetadata?.id == state.videoId }
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val resolution = causes.filterIsInstance<PlaybackResolutionException>().firstOrNull()
        val request = causes.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull()
        val response = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val invalidResponse = causes.filterIsInstance<InvalidMediaResponseException>().firstOrNull()
        val plan = stream?.hlsRequestPlan ?: stream?.dashRequestPlan ?: stream?.formats?.firstNotNullOfOrNull { it.requestPlan }
        fun format(value: Format?) = value?.let {
            mapOf("itag" to it.itag, "mime" to it.mimeType, "codec" to it.codec,
                "height" to it.height, "bitrate" to it.bitrate, "client" to it.requestPlan?.client?.name)
        }
        val fields = linkedMapOf<String, Any?>(
            "position_ms" to player.currentPosition, "duration_ms" to state.durationMs,
            "buffered_ms" to player.bufferedPosition, "prepared" to state.prepared,
            "first_frame" to state.firstFrameRendered, "play_when_ready" to userWantsPlay,
            "live" to state.isLive, "quality" to state.qualityLabel, "active_quality" to state.activeQuality,
            "video" to format(state.videoFormat), "audio" to format(state.audioFormat),
            "video_decoder" to state.diagnostics.videoDecoder, "audio_decoder" to state.diagnostics.audioDecoder,
            "video_codec" to state.diagnostics.videoCodec, "audio_codec" to state.diagnostics.audioCodec,
            "video_width" to state.diagnostics.width, "video_height" to state.diagnostics.height,
            "video_bitrate" to state.diagnostics.videoBitrate, "audio_bitrate" to state.diagnostics.audioBitrate,
            "account_state" to (plan?.session?.account?.name ?: resolution?.diagnostics?.accountState),
            "configuration_source" to (plan?.session?.configurationSource ?: resolution?.diagnostics?.configurationSource),
            "network_generation" to (plan?.session?.networkGeneration ?: resolution?.diagnostics?.networkGeneration),
            "hls_client" to stream?.hlsRequestPlan?.client?.name, "dash_client" to stream?.dashRequestPlan?.client?.name,
            "format_count" to stream?.formats?.size, "light_refreshes" to (attempt?.lightRefreshes ?: 0),
            "full_refreshes" to (attempt?.fullRefreshes ?: 0), "backup_attempted" to (attempt?.backupAttempted ?: false),
            "muxed_fallback" to muxedFallbackActive,
            "recovery_history" to attempt?.history.orEmpty(),
            "error_code" to ((error as? PlaybackException)?.errorCode ?: resolution?.error?.kind?.name),
            "error_name" to ((error as? PlaybackException)?.errorCodeName ?: resolution?.error?.code),
            "transport_cause" to resolution?.error?.cause,
            "http_status" to (response?.responseCode ?: invalidResponse?.statusCode ?: resolution?.error?.status),
            "content_type" to (response?.headerFields?.entries?.firstOrNull { it.key.equals("content-type", true) }?.value?.firstOrNull() ?: invalidResponse?.contentType),
            "transport_method" to invalidResponse?.method, "failed_client" to invalidResponse?.client?.name,
            "request_method" to request?.dataSpec?.httpMethodString,
            "request_position" to request?.dataSpec?.position, "request_length" to request?.dataSpec?.length,
            "player_attempts" to resolution?.diagnostics?.attempts?.map { attempt ->
                mapOf("client" to attempt.client.name, "status" to attempt.status,
                    "outcome" to (attempt.error?.code ?: "PLAYABLE"), "duration_ms" to attempt.elapsedMs,
                    "transport_cause" to attempt.error?.cause)
            },
        )
        request?.let { fields["request"] = DiagnosticRedaction.resource(it.dataSpec.uri.toString()) }
        val report = PlaybackFailureReport.create(playbackFailed(error), error, phase, diagnosticContext,
            mapOf("app_version" to BuildConfig.VERSION_NAME, "build_id" to BuildConfig.DIAGNOSTIC_BUILD_ID,
                "build_type" to BuildConfig.BUILD_TYPE, "android_sdk" to Build.VERSION.SDK_INT,
                "android_release" to Build.VERSION.RELEASE, "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL, "network" to AndroidDiagnosticState.networkSnapshot()),
            fields, AppLog.recent(diagnosticContext))
        playerLog("terminal_failure", mapOf("report_id" to report.id, "phase" to phase), error)
        return report
    }

    companion object {
        private const val TAG = "PlaybackEngine"
        private const val TICK_MS = 250L
        private const val CAST_MAX_HEIGHT = 1080
        private val PROGRESS_TTL_MS = TimeUnit.DAYS.toMillis(3)
        private const val KEY_SPEED = "player:speed"
        private const val KEY_QUALITY = "player:quality"
        private const val KEY_LOOP_MODE = "player:loop_mode"
        private const val KEY_SHORTS_LOOP_MODE = "player:shorts_loop_mode"
        private const val KEY_SUBTITLE = "player:subtitle_language"
        /** How long a still-running account resume read may delay source submission. */
        private const val REMOTE_RESUME_GRACE_MS = 600L
        private const val KEY_SUBTITLE_OFF = "player:subtitle_off"
    }
}

/** Bound compressed samples too: a 50 s high-bitrate buffer can exhaust a 256 MB heap. */
internal fun mediaBufferBudgetBytes(maxHeapBytes: Long): Int =
    (maxHeapBytes / 8).coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024).toInt()

/** Keep the time windows, but let the byte budget stop high-bitrate read-ahead. */
@UnstableApi
internal fun defaultLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(50_000, 60_000, 500, 4_000)
    .setTargetBufferBytes(mediaBufferBudgetBytes(Runtime.getRuntime().maxMemory()))
    .setPrioritizeTimeOverSizeThresholds(false)
    .build()
