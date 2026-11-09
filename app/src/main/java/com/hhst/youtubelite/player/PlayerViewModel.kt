@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.R
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.CastDevice
import com.hhst.youtubelite.cast.DlnaController
import com.hhst.youtubelite.cast.DlnaUiState
import com.hhst.youtubelite.cast.LocalStreamProxy
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.core.PipSupport
import com.hhst.youtubelite.core.JsonCache
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.datasource.AudioTrackChoice
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.CastSource
import com.hhst.youtubelite.player.engine.PlaybackDiagnostics
import com.hhst.youtubelite.player.engine.PlaybackSnapshot
import com.hhst.youtubelite.player.engine.SubtitleTrack
import com.hhst.youtubelite.player.service.PlaybackCommandRouter
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.player.surface.GestureMath.GestureZone
import com.hhst.youtubelite.player.surface.PlayerPlaybackActions
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.player.surface.ResizeMode
import com.hhst.youtubelite.player.surface.SubtitleStyle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface GestureUi {
    data class DoubleTapSeek(val forward: Boolean, val accumMs: Long) : GestureUi
    data object SpeedHold : GestureUi
    data class Scrub(val targetMs: Long, val deltaMs: Long) : GestureUi
    data class EdgeSlider(val volume: Boolean, val percent: Int) : GestureUi
}

enum class MediaKind { NONE, WATCH, SHORTS }

data class PlayerUiState(
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
    val visible: Boolean = false,
    val fullscreen: Boolean = false,
    val fullscreenKeepsOrientation: Boolean = false,
    val fullscreenRestoresEntryOrientation: Boolean = false,
    val controlsVisible: Boolean = true,
    val loading: Boolean = false,
    val isPlaying: Boolean = false,
    val userWantsPlay: Boolean = false,
    val shortPausedByUser: Boolean = false,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val title: String = "",
    val author: String? = null,
    val isLive: Boolean = false,
    val videoId: String? = null,
    /** Media is attached; the first video frame may still be pending. */
    val prepared: Boolean = false,
    val firstFrameRendered: Boolean = false,
    /** This load has rendered video, even if a replacement surface is still waiting for its frame. */
    val hasRenderedVideoFrame: Boolean = false,
    val url: String? = null,
    val loopMode: LoopMode = LoopMode.QUEUE_NEXT,
    val qualityLabel: String? = null,
    val qualities: List<String> = emptyList(),
    val subtitleKey: String? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrackChoice> = emptyList(),
    val audioTrackKey: String? = null,
    val error: String? = null,
    val notice: com.hhst.youtubelite.player.engine.PlaybackNotice? = null,
    val failureReport: com.hhst.youtubelite.diagnostics.PlaybackFailureReport? = null,
    val failureDialogVisible: Boolean = false,
    /** True while the time bar is being dragged; keeps chrome from auto-hiding. */
    val scrubbing: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val locked: Boolean = false,
    val queueItems: List<QueueItem> = emptyList(),
    val queueEnabled: Boolean = false,
    /** Owner of the published queue; gesture commits carry it back for validation. */
    val queueOwner: QueueOwner = QueueOwner.DEVICE,
    val sponsorSegments: List<SponsorBlockManager.Segment> = emptyList(),
    val resizeMode: ResizeMode = ResizeMode.Fit,
    val pipAvailable: Boolean = false,
    val ended: Boolean = false,
    val mini: Boolean = false,
    val pageHeightDp: Int? = null,
    val pageTopDp: Int? = null,
    val pageLeftDp: Int = 0,
    val pageWidthDp: Int? = null,
    val pageViewportWidthDp: Int? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    /** Quality actually rendering; badge shows "Auto · 720p" when unpinned. */
    val activeQuality: String? = null,
    val subtitleEnabled: Boolean = false,
    val videoItag: Int? = null,
    val audioBitrate: Int = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val sponsorCountdownSec: Int? = null,
    /** Manual skip chip while inside a suppressed segment. */
    val sponsorChip: Boolean = false,
    /** True when [sponsorChip] targets a highlight point (chip jumps TO it). */
    val sponsorChipHighlight: Boolean = false,
    /** Seek counter from the engine; watch reporting re-anchors its interval on change. */
    val discontinuityEpoch: Long = 0,
    val casting: Boolean = false,
    val castingDeviceName: String? = null,
    val castLinkUrl: String? = null,
    val castLinkConnected: Boolean = false,
    val castAvailable: Boolean = false,
    val castDevices: List<CastDevice> = emptyList(),
    val castFailure: com.hhst.youtubelite.cast.CastFailureReport? = null,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
)

/**
 * Thin reducer between the surface and [PlaybackApi]: the engine is the single
 * source of truth for playback; UI-only state lives here, intents forward down.
 *
 * Registered as a Koin single (process scope): background play, MediaSession
 * keys and queue auto-advance must outlive the activity, so [onCleared] never runs.
 */
class PlayerViewModel(
    private val engine: PlaybackApi,
    private val queue: QueueRepository,
    private val prefs: ExtensionManager,
    private val cache: JsonCache,
    private val cast: CastController,
    /** DLNA route; null in unit tests, where all DLNA branches no-op. */
    private val dlna: DlnaController? = null,
    private val appContext: Context? = null,
    private val haptics: HapticsController? = null,
    private val nextVideo: (suspend (String) -> String?)? = null,
    private val reporting: com.hhst.youtubelite.data.media.PlaybackWatchReporting? = null,
    private val identityChanges: Flow<Long> = com.hhst.youtubelite.data.media.MediaSession.changes.map { it.generation },
) : ViewModel(), PlayerPlaybackActions {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Position lives outside [PlayerUiState] so the ~4 Hz snapshot tick
    // recomposes only the time readouts, not the whole surface tree.
    private val _positionState = mutableLongStateOf(0L)
    private val _bufferedPositionState = mutableLongStateOf(0L)

    // Transient overlays live outside [PlayerUiState] for the same reason:
    // ~60-120 Hz gesture updates recompose only the leaf overlay.
    private val _gestureState = mutableStateOf<GestureUi?>(null)
    private val _hintState = mutableStateOf<String?>(null)
    private val _subtitleCuesState = mutableStateOf<List<String>>(emptyList())

    val positionState: State<Long> get() = _positionState
    val bufferedPositionState: State<Long> get() = _bufferedPositionState

    val gestureState: State<GestureUi?> get() = _gestureState

    val hintState: State<String?> get() = _hintState

    val subtitleCuesState: State<List<String>> get() = _subtitleCuesState


    /** Resolved lazily; context may be null in tests. */
    private val hintCastFailed: String by lazy {
        appContext?.getString(R.string.cast_failed) ?: "Cast failed"
    }

    /** Resolved lazily; context may be null in tests. */
    private val hintSubtitleUnavailable: String by lazy {
        appContext?.getString(R.string.subtitle_unavailable) ?: "Subtitle unavailable"
    }

    /** Avoid rewriting the queue on every 4 Hz snapshot tick. */
    private var queuedSignature: String? = null

    private var speedBeforeHold: Float? = null

    private val fullscreenState = FullscreenState()

    private var seekAccumMs = 0L
    private var lastSeekTapAt = 0L

    /** Live-scrub anchor: position captured on the first preview of a scrub. */
    private var scrubAnchorMs: Long? = null

    /** Supersedes in-flight cast-handoff waiters when a newer play/close wins. */
    private var playbackRequestSeq = 0
    private var dlnaTransfer: kotlinx.coroutines.Job? = null
    private var castReloadJob: kotlinx.coroutines.Job? = null
    private var handledEndSeq: Int? = null
    private val autoplayRequestId = AtomicLong()

    /** Last notification nav push; snapshots tick 4x/s, refresh is not free. */
    private var lastNavNext: Boolean? = null
    private var lastNavPrev: Boolean? = null

    /**
     * Set when the user stops casting, held until the next session starts: a
     * late session-end echo must never resume local playback behind the user's
     * back; a fresh session clears it in [onCastSessionStarted].
     */
    private var userCastStop = false

    /** Installed once in the init block; the process-scoped router is never re-pointed. */
    private val commandHandler = object : PlaybackCommandRouter.Handler {
        override fun onPlayPause() { this@PlayerViewModel.onPlayPause() }
        override fun onPlay() { autoplayRequestId.incrementAndGet(); engine.play() }
        override fun onPause() { autoplayRequestId.incrementAndGet(); engine.pause() }
        override fun onStop() { stopPlayback() }
        override fun onNext() { skipToNext() }
        override fun onPrevious() { skipToPrevious() }
    }

    fun gestureEnabled(zone: GestureZone): Boolean =
        prefs.isEnabled(gesturePrefKey(zone, _uiState.value.fullscreen))

    private var playlistItems: List<QueueItem> = emptyList()
    private var playlistActive = false
    /** The first notification establishes the startup identity, rather than switching it. */
    private var identityGeneration: Long? = null
    @Volatile private var inShorts = false
    private var shortsHoldWasPlaying = false
    private var shortsHeldItemId: String? = null
    private var shortsResumeOnOpen = false
    @Volatile private var mediaKind = MediaKind.NONE
    val currentMediaKind: MediaKind get() = mediaKind
    private val sessionTrail = ArrayDeque<String>()
    private var relatedNext: Pair<String, String?>? = null
    private var relatedJob: kotlinx.coroutines.Job? = null

    init {
        val rememberedResize = if (prefs.isEnabled(PreferenceKeys.REMEMBER_RESIZE_MODE)) {
            cache.get(KEY_RESIZE, String::class.java)
                ?.let { runCatching { ResizeMode.valueOf(it) }.getOrNull() }
        } else {
            null
        }
        val resize = rememberedResize ?: ResizeMode.Fit
        _uiState.update {
            it.copy(
                resizeMode = resize,
                pipAvailable = pipEnabled(),
                subtitleStyle = SubtitleStyle.decode(
                    cache.get(KEY_SUBTITLE_STYLE, String::class.java),
                ),
            )
        }
        viewModelScope.launch {
            engine.snapshot.collect { s -> mergeSnapshot(s) }
        }
        // Playback responses carry short-lived watch-reporting endpoints; the
        // session owner consumes them directly, keyed by the loaded media identity.
        engine.onWatchContext = { videoId, tracking, generation -> reporting?.armTracking(videoId, tracking, generation) }
        engine.onWatchBoundary = { state, position ->
            reporting?.finishPlayback(com.hhst.youtubelite.data.media.PlaybackSample(state.videoId, position,
                state.durationMs, state.isPlaying, state.discontinuityEpoch))
        }
        viewModelScope.launch {
            queue.state.collect { q ->
                val nav = queueNavAvailability()
                pushNavIfChanged(nav.next, nav.previous)
                _uiState.update {
                    it.copy(
                        queueItems = q.items,
                        queueEnabled = q.enabled,
                        // Attribution for gesture commits (drag reorder), not display data.
                        queueOwner = queue.currentOwner(),
                        hasNext = nav.next,
                        hasPrevious = nav.previous,
                    )
                }
            }
        }
        viewModelScope.launch {
            identityChanges.collect { generation ->
                val previousGeneration = identityGeneration
                val previousOwner = queue.currentOwner()
                queue.activate()
                // StateFlow skips structurally equal states: republish the owner
                // here or drag commits validate against a stale owner.
                _uiState.update { state -> state.copy(queueOwner = queue.currentOwner()) }
                identityGeneration = generation
                if (previousGeneration == null) return@collect
                // A missing access token can recover without a new transport
                // generation, but still move the queue from device to account.
                if (previousGeneration == generation && previousOwner == queue.currentOwner()) return@collect
                // The previous identity's playlist, back trail and prefetch die
                // with it; the current video keeps playing under the new identity.
                playlistActive = false
                playlistItems = emptyList()
                sessionTrail.clear()
                relatedNext = null
                relatedJob?.cancel()
                queuedSignature = null
                refreshNavAvailability()
            }
        }
        viewModelScope.launch {
            val dlnaState: Flow<DlnaUiState> = dlna?.state ?: flowOf(DlnaUiState())
            combine(cast.state, dlnaState) { c, d -> c to d }.collect { (c, d) ->
                _uiState.update {
                    it.copy(
                        casting = c.chromecastSession || d.session,
                        castingDeviceName = c.deviceName ?: d.deviceName,
                        castLinkUrl = c.linkUrl,
                        castLinkConnected = c.linkConnected,
                        castAvailable = c.available || d.available,
                        castDevices = c.devices + d.devices,
                        castFailure = d.failure ?: c.failure ?: it.castFailure,
                    )
                }
            }
        }
        dlna?.initialize()
        dlna?.let { d ->
            d.onSessionEnded = {
                if (!userCastStop) {
                    detachCastAndResumeLocal(d.stopCasting(), resume = true)
                }
                d.endSession()
                cast.stopLink()
            }
            d.onSessionFailed = {
                if (!userCastStop) {
                    detachCastAndResumeLocal(d.stopCasting(), resume = false)
                    onHint(hintCastFailed)
                }
                d.endSession()
                cast.stopLink()
            }
        }
        engine.onEnded = { onPlaybackEnded() }
        engine.onCastManifestReload = { recastPublishedSource() }
        cast.onSessionStarted = ::onCastSessionStarted
        cast.onSessionEnded = {
            if (!userCastStop) {
                detachCastAndResumeLocal(cast.stopCasting(), resume = true)
            }
        }
        cast.onSessionFailed = {
            if (!userCastStop) {
                detachCastAndResumeLocal(cast.stopCasting(), resume = false)
                onHint(hintCastFailed)
            }
        }
        // Already detached (and resumed local unless a recast was in flight);
        // end whichever route is active.
        engine.onCastError = {
            if (!userCastStop) teardownCasting()
        }
        cast.urlRefresher = object : LocalStreamProxy.UrlRefresher {
            override fun refreshUrl(token: String, videoId: String?, itag: Int?) = engine.refreshCastUrl(token, videoId, itag)
            override fun backupUrl(token: String, videoId: String?, itag: Int?, failedProfile: String?) = engine.backupCastUrl(token, videoId, itag, failedProfile)
        }
        PlaybackCommandRouter.handler = commandHandler
        // Poll link liveness only while a link is live: 5 s stays snappy
        // against the proxy's 30 s window without busy-polling an idle session.
        viewModelScope.launch {
            while (true) {
                if (cast.state.value.linkUrl == null) {
                    cast.state.first { it.linkUrl != null }
                }
                cast.refreshLinkBanner()
                delay(5_000)
            }
        }
    }

    private fun pushNavIfChanged(next: Boolean, prev: Boolean) {
        if (next == lastNavNext && prev == lastNavPrev) return
        lastNavNext = next
        lastNavPrev = prev
        engine.setQueueNavigation(next, prev)
    }

    private fun mergeSnapshot(s: PlaybackSnapshot) {
        if (_uiState.value.ended && !s.ended) {
            // Replaying/seeking also invalidates any previous end-of-video query.
            autoplayRequestId.incrementAndGet()
            handledEndSeq = null
        }
        _positionState.longValue = s.positionMs
        _bufferedPositionState.longValue = s.bufferedPositionMs
        _subtitleCuesState.value = s.subtitleCues
        val nav = queueNavAvailability()
        pushNavIfChanged(nav.next, nav.previous)
        _uiState.update {
            it.copy(
                diagnostics = s.diagnostics,
                loading = s.isBuffering && s.userWantsPlay,
                isPlaying = s.isPlaying,
                userWantsPlay = s.userWantsPlay,
                durationMs = s.durationMs,
                speed = s.speed,
                title = s.title,
                author = s.author,
                isLive = s.isLive,
                videoId = s.videoId,
                prepared = s.prepared,
                firstFrameRendered = s.firstFrameRendered,
                hasRenderedVideoFrame = s.hasRenderedVideoFrame,
                url = s.url,
                loopMode = s.loopMode,
                qualityLabel = s.qualityLabel,
                qualities = s.qualities,
                activeQuality = s.activeQuality,
                subtitleKey = s.subtitleKey,
                subtitleEnabled = s.subtitleEnabled,
                subtitleTracks = s.subtitleTracks,
                audioTracks = s.audioTracks,
                audioTrackKey = s.audioTrackKey,
                error = s.error,
                notice = s.notice,
                failureReport = s.failureReport,
                failureDialogVisible = s.failureReport != null &&
                    (it.failureReport?.id != s.failureReport.id || it.failureDialogVisible),
                hasNext = nav.next,
                hasPrevious = nav.previous,
                sponsorSegments = s.sponsorSegments,
                // Force controls on only when the screen ENTERS error/ended
                // (retry/replay must show); afterwards the user's hide toggle
                // must stick instead of being re-asserted by every snapshot.
                controlsVisible = if ((s.error != null || s.notice != null || s.ended) && !(it.error != null || it.notice != null || it.ended)) {
                    true
                } else {
                    it.controlsVisible
                },
                ended = s.ended,
                videoWidth = s.videoWidth,
                videoHeight = s.videoHeight,
                chapters = s.chapters,

                videoItag = s.videoFormat?.itag,

                audioSampleRate = s.audioFormat?.sampleRate ?: 0,
                audioChannels = s.audioFormat?.audioChannels ?: 0,
                sponsorCountdownSec = s.sponsorCountdownSec,
                sponsorChip = s.sponsorChip,
                sponsorChipHighlight = s.sponsorChipHighlight,
                discontinuityEpoch = s.discontinuityEpoch,
            )
        }
        maybeSyncQueue(s)
        maybePrefetchRelated(s)
    }

    private fun maybeSyncQueue(s: PlaybackSnapshot) {
        val id = s.videoId ?: return
        val signature = "$id|${s.title}|${s.author}"
        if (signature == queuedSignature) return
        queuedSignature = signature
        // Only refresh metadata for videos the user already queued — watching
        // must not silently grow a playlist (that made autoplay loop history).
        if (queue.state.value.items.any { it.videoId == id }) {
            queue.upsertPlaying(
                QueueItem(
                    videoId = id,
                    url = s.url ?: VideoId.watchUrl(id),
                    title = s.title,
                    author = s.author,
                ),
            )
        }
    }

    /** PiP entry can resume the browse host before the mode callback arrives. */
    fun onPipEntered() {
        _uiState.update { it.copy(mini = false) }
    }

    fun onBrowseOpened() {
        if (!_uiState.value.visible || _uiState.value.mini) return
        if (mediaKind == MediaKind.SHORTS) {
            onShortsStopped()
            return
        }
        if (prefs.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)) enterMiniPlayer()
        else onLeaveWatch()
    }

    fun openVideo(videoId: String, startSeconds: Long? = null) {
        val id = VideoId.parse(videoId) ?: return
        inShorts = false
        mediaKind = MediaKind.WATCH
        engine.setShortsMode(false)
        if (playlistActive && playlistItems.none { it.videoId == id }) {
            playlistItems = emptyList()
            playlistActive = false
        }
        val current = engine.snapshot.value
        if (current.videoId == id && current.prepared && startSeconds != null) engine.seekTo(startSeconds.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE / 1000) * 1000)
        if (current.videoId == id && (current.prepared || _uiState.value.visible)) {
            _uiState.update { it.copy(visible = true, mini = false, controlsVisible = true) }
            // A Shorts session turned the engine output off; a same-video
            // restore must turn it back on or the surface stays black.
            engine.setVideoVisible(true)
            return
        }
        // Any non-null start (0 included) must stay explicit or resume logic overrides it.
        startPlayback(VideoId.watchUrl(id) + (startSeconds?.let { "&t=" + it } ?: ""), keepMini = false, kind = MediaKind.WATCH)
    }

    fun setVideoVisible(visible: Boolean) = engine.setVideoVisible(visible)

    fun onShortsOpened() {
        mediaKind = MediaKind.SHORTS
        engine.setShortsMode(true)
        if (inShorts) {
            engine.setVideoVisible(true)
            if (shortsResumeOnOpen) engine.play()
            shortsResumeOnOpen = false
            return
        }
        inShorts = true
        playbackRequestSeq++
        engine.cancelPending(pausePlayback = !_uiState.value.casting)
        engine.pauseLocal()
        if (speedBeforeHold != null) onSpeedHoldEnd()
        fullscreenState.clearAutoEntry()
        _uiState.update { it.copy(visible = false, mini = false, fullscreen = false, locked = false) }
    }

    fun onShortsClosed() {
        if (mediaKind != MediaKind.SHORTS) return
        onShortsStopped()
        inShorts = false
        shortsResumeOnOpen = false
        stopShortsPresentation()
    }

    fun onShortsStopped() {
        if (mediaKind != MediaKind.SHORTS || !inShorts) return
        if (_uiState.value.visible) shortsResumeOnOpen = engine.snapshot.value.userWantsPlay
        onSpeedHoldEnd()
        playbackRequestSeq++
        engine.cancelPending(pausePlayback = true)
        engine.setVideoVisible(false)
        engine.pause()
        stopShortsPresentation()
    }

    /** Shorts never keep a floating window: every presentation flag ends here. */
    private fun stopShortsPresentation() {
        _uiState.update { it.copy(visible = false, mini = false, fullscreen = false, locked = false) }
    }

    fun openShort(videoId: String) {
        val id = VideoId.parse(videoId) ?: return
        inShorts = true
        mediaKind = MediaKind.SHORTS
        engine.setShortsMode(true)
        val current = engine.snapshot.value
        if (current.videoId == id && (current.prepared || _uiState.value.visible)) {
            _uiState.update { it.copy(visible = true, mini = false, fullscreen = false) }
            engine.setVideoVisible(true)
        } else {
            // A new short starts at zero; account/local resume belongs to watch playback.
            // Re-entering the prepared short above keeps its position and pause intent.
            startPlayback(VideoId.watchUrl(id) + "&t=0", keepMini = false, kind = MediaKind.SHORTS)
        }
    }

    fun pauseShort() {
        engine.pauseLocal()
        _uiState.update { it.copy(visible = false, fullscreen = false, mini = false) }
        inShorts = false
    }

    /** A swipe started: silence the settled short without leaving the Shorts session. */
    fun holdShortsForSwipe() {
        if (!inShorts) return
        shortsHeldItemId = engine.snapshot.value.videoId
        shortsHoldWasPlaying = engine.snapshot.value.userWantsPlay
        onSpeedHoldEnd()
        engine.pause()
    }

    /**
     * A swipe cancelled back to its origin short: only the short the engine
     * still owns resumes; a newer target keeps playback, a user-paused short
     * stays paused.
     */
    fun resumeShortsAfterSwipe(videoId: String) {
        if (!inShorts) return
        val expected = VideoId.parse(videoId)
        val resume = shortsHoldWasPlaying && expected != null && expected == shortsHeldItemId &&
            engine.snapshot.value.videoId == expected
        shortsHeldItemId = null
        shortsHoldWasPlaying = false
        if (resume) engine.play()
    }

    fun onLeaveWatch() {
        val state = _uiState.value
        if (state.mini || !state.visible) return
        if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY) || state.casting) {
            fullscreenState.clearAutoEntry()
            _uiState.update { it.copy(visible = false, fullscreen = false, locked = false, mini = false) }
            if (state.casting) engine.pauseLocal()
        } else stopAndHide()
    }

    fun enterMiniPlayer() {
        fullscreenState.clearAutoEntry()
        _uiState.update {
            it.copy(
                visible = true,
                mini = true,
                fullscreen = false,
                locked = false,
                controlsVisible = true,
            )
        }
    }

    /** A center-area downward drag on the embedded player asks for the mini player. */
    fun onMiniSwipeRequested() {
        if (mediaKind != MediaKind.WATCH || !prefs.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)) return
        val state = _uiState.value
        if (!state.visible || state.mini || state.fullscreen) return
        enterMiniPlayer()
    }

    private fun stopAndHide(pausePlayback: Boolean = true) {
        playbackRequestSeq++
        autoplayRequestId.incrementAndGet()
        fullscreenState.clearAutoEntry()
        if (audioOnlyBackground) {
            audioOnlyBackground = false
            engine.setAudioOnly(false)
        }
        _uiState.update {
            it.copy(visible = false, fullscreen = false, locked = false, mini = false)
        }
        engine.cancelPending(pausePlayback)
    }

    /** Queue navigation keeps the current surface. */
    private fun startPlayback(url: String, keepMini: Boolean, kind: MediaKind? = null, recordHistory: Boolean = true) {
        if (dlnaTransfer?.isActive == true) onCastStop()
        autoplayRequestId.incrementAndGet()
        playbackRequestSeq++
        val token = playbackRequestSeq
        val id = VideoId.parse(url)
        val previousId = _uiState.value.videoId
        val previousKind = mediaKind
        if (kind != null) mediaKind = kind
        engine.setShortsMode(mediaKind == MediaKind.SHORTS)
        val switching = id != null && id != previousId
        if (recordHistory && switching && previousKind == MediaKind.WATCH && previousId != null) {
            if (sessionTrail.lastOrNull() != previousId) sessionTrail.addLast(previousId)
            if (sessionTrail.size > MAX_SESSION_TRAIL) sessionTrail.removeFirst()
        }
        // A speed-hold or scrub interrupted by surface mode swaps (PiP enter,
        // mini collapse) never sees its End/Commit; drop the stale gesture
        // state so the next video starts clean.
        speedBeforeHold = null
        engine.setSpeedHold(false)
        scrubAnchorMs = null
        seekAccumMs = 0L
        _gestureState.value = null
        _uiState.update {
            it.copy(
                visible = true,
                mini = if (keepMini) it.mini else false,
                error = null,
                notice = null,
                failureReport = null,
                failureDialogVisible = false,
                locked = false,
                url = url,
                videoId = id ?: it.videoId,
                loading = if (switching) true else it.loading,
                prepared = if (switching) false else it.prepared,
                firstFrameRendered = if (switching) false else it.firstFrameRendered,
                hasRenderedVideoFrame = if (switching) false else it.hasRenderedVideoFrame,
                isPlaying = if (switching) false else it.isPlaying,
                userWantsPlay = if (switching) true else it.userWantsPlay,
                shortPausedByUser = if (switching) false else it.shortPausedByUser,
                ended = if (switching) false else it.ended,
                pageHeightDp = null,
                pageTopDp = null,
                pageLeftDp = 0,
                pageWidthDp = null,
                pageViewportWidthDp = null,
                sponsorCountdownSec = null,
                sponsorChip = false,
                sponsorChipHighlight = false,
                pipAvailable = pipEnabled(),
            )
        }
        // Activity.onStart can run before openVideo, while visible is still
        // false. Publish the new session's output intent as well as its UI.
        engine.setVideoVisible(!audioOnlyBackground)
        engine.play(url)
        applyAutoFullscreen()
        // Queue advance mid-cast: the receiver must switch videos too, or the
        // TV keeps the old stream under the new title. Skipped when the
        // receiver already plays this exact video (user re-opened its watch
        // page mid-cast): engine.play short-circuited and resumed the
        // receiver — re-handing off would reload it at 0.
        if (
            isCasting() &&
            id != null &&
            engine.currentCastSource()?.videoId != id
        ) {
            viewModelScope.launch {
                val ready = withTimeoutOrNull(PREPARED_WAIT_TIMEOUT_MS) {
                    engine.snapshot.first { loadSettledFor(it, id) }
                }
                if (token != playbackRequestSeq) return@launch
                if (ready == null) return@launch
                if (engine.snapshot.value.error != null || engine.snapshot.value.notice != null) return@launch
                val source = recastToActiveRoute(0L)
                if (token != playbackRequestSeq) return@launch
                if (source != null) {
                    engine.markReceiverSource(source)
                } else {
                    teardownCasting()
                    onHint(hintCastFailed)
                }
            }
        }
    }

    override fun onPlayPause() {
        autoplayRequestId.incrementAndGet()
        if (mediaKind == MediaKind.SHORTS) {
            val pausing = engine.snapshot.value.userWantsPlay && !engine.snapshot.value.ended
            _uiState.update { it.copy(shortPausedByUser = pausing) }
        }
        engine.playOrPause()
    }
    override fun onSeek(positionMs: Long) { autoplayRequestId.incrementAndGet(); engine.seekTo(positionMs) }

    override fun onDoubleTapSeek(offsetMs: Long) {
        haptics?.perform(HapticsController.Event.SELECTION)
        autoplayRequestId.incrementAndGet()
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekTapAt > SEEK_ACCUM_WINDOW_MS || (seekAccumMs > 0) != (offsetMs > 0)) seekAccumMs = 0L
        lastSeekTapAt = now
        seekAccumMs += offsetMs
        engine.seekRelative(offsetMs)
        _gestureState.value = GestureUi.DoubleTapSeek(forward = offsetMs > 0, accumMs = seekAccumMs)
        _hintState.value = null
    }

    /** Preview only: the bubble tracks the finger; the seek lands on commit. */
    override fun onScrubPreview(offsetMs: Long) {
        val anchor = scrubAnchorMs
            ?: _positionState.longValue.also { scrubAnchorMs = it }
        val target = scrubTarget(anchor + offsetMs)
        _gestureState.value = GestureUi.Scrub(targetMs = target, deltaMs = target - anchor)
    }

    override fun onScrubCommit(offsetMs: Long) {
        // Clear the bubble first: an anchor lost to a mid-scrub video switch
        // must not strand the overlay on the new video.
        _gestureState.value = null
        val anchor = scrubAnchorMs ?: return
        scrubAnchorMs = null
        autoplayRequestId.incrementAndGet()
        haptics?.perform(HapticsController.Event.SELECTION)
        engine.seekTo(scrubTarget(anchor + offsetMs))
    }

    override fun onTimeBarPreview(targetMs: Long?) {
        if (targetMs == null) {
            _gestureState.value = null
            _uiState.update { it.copy(scrubbing = false) }
            return
        }
        val target = scrubTarget(targetMs)
        val delta = target - _positionState.longValue
        _gestureState.value = GestureUi.Scrub(targetMs = target, deltaMs = delta)
        _uiState.update { it.copy(scrubbing = true) }
    }

    override fun onTimeBarCommit() {
        _gestureState.value = null
        _uiState.update { it.copy(scrubbing = false) }
    }

    private fun scrubTarget(targetMs: Long): Long {
        val duration = _uiState.value.durationMs
        // duration <= 0 means unknown (live or not yet prepared); do not clamp
        // to an empty range.
        return if (duration > 0) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
    }

    override fun onSpeed(speed: Float) {
        haptics?.perform(HapticsController.Event.SELECTION)
        engine.setSpeed(speed)
        onHint(PlayerUi.speedLabel(speed))
    }

    override fun onSpeedHoldStart() {
        if (speedBeforeHold != null) return
        haptics?.perform(HapticsController.Event.HOLD)
        speedBeforeHold = _uiState.value.speed
        engine.setSpeedHold(true)
        engine.setSpeed(2f)
        _gestureState.value = GestureUi.SpeedHold
        _hintState.value = null
    }

    override fun onSpeedHoldEnd() {
        val previousSpeed = speedBeforeHold ?: return
        engine.setSpeedHold(false)
        engine.setSpeed(previousSpeed)
        speedBeforeHold = null
        _gestureState.value = null
    }

    override fun onLoopMode(mode: LoopMode) {
        engine.setLoopMode(mode)
    }

    override fun onQuality(label: String?) { haptics?.perform(HapticsController.Event.SELECTION); engine.setQuality(label) }

    /** No "off" row: tapping the active language turns captions off; other picks enable and switch. */
    override fun onSubtitle(trackKey: String?) {
        haptics?.perform(HapticsController.Event.SELECTION)
        if (trackKey == null) return
        val current = _uiState.value
        val off = current.subtitleEnabled && current.subtitleKey == trackKey
        val applied = engine.setSubtitle(if (off) null else trackKey)
        onHint(if (applied || off) null else hintSubtitleUnavailable)
    }

    override fun onAudioTrack(trackKey: String?) = engine.setAudioTrack(trackKey)

    override fun onAudioMenuOpened() = engine.onAudioMenuOpened()

    override fun onSubtitleStyle(style: SubtitleStyle) {
        val snapped = style.clamped()
        if (snapped == _uiState.value.subtitleStyle) return
        _uiState.update { it.copy(subtitleStyle = snapped) }
        cache.put(KEY_SUBTITLE_STYLE, snapped.encode(), PREF_TTL_MS)
    }

    fun startPlaylist(items: List<QueueItem>, shuffle: Boolean = false, startId: String? = null) {
        val clean = items.mapNotNull { item ->
            VideoId.parse(item.videoId)?.let { item.copy(videoId = it, url = VideoId.watchUrl(it)) }
        }
        playlistItems = if (shuffle) clean.shuffled() else clean
        playlistActive = playlistItems.isNotEmpty()
        (playlistItems.firstOrNull { it.videoId == startId } ?: playlistItems.firstOrNull())?.let { openVideo(it.videoId) }
    }

    private fun playlistNeighbor(direction: Int): QueueItem? {
        val index = playlistItems.indexOfFirst { it.videoId == playbackQueueId() }
        return if (index < 0) null else playlistItems.getOrNull(index + direction)
    }

    private fun queueActive(): Boolean {
        val q = queue.state.value
        return !playlistActive && q.enabled && q.items.isNotEmpty()
    }

    private fun playbackQueueId(): String? =
        engine.snapshot.value.videoId ?: _uiState.value.videoId

    private fun currentInQueue(): Boolean {
        val id = playbackQueueId() ?: return false
        return queue.state.value.items.any { it.videoId == id }
    }

    /**
     * The single navigation decision shared by surface buttons, the media
     * notification, PiP actions and headset keys. Queue and playlist navigate
     * only inside their own context (no stale-queue hijack); Shorts expose
     * no watch navigation.
     */
    private fun nextTarget(id: String?): QueueItem? {
        if (id == null) return null
        return when {
            queueActive() && currentInQueue() -> when (_uiState.value.loopMode) {
                LoopMode.QUEUE_RANDOM -> queue.random(id)
                else -> queue.nextStrict(id)
            }
            playlistActive && playlistItems.any { it.videoId == id } -> playlistNeighbor(1)
            else -> null
        }
    }

    private fun previousTarget(id: String?): QueueItem? {
        if (id == null) return null
        return when {
            queueActive() && currentInQueue() -> queue.previous(id)
            playlistActive && playlistItems.any { it.videoId == id } -> playlistNeighbor(-1)
            else -> null
        }
    }

    private fun relatedAvailable(id: String?): Boolean {
        if (id == null || nextVideo == null) return false
        if (queueActive() && currentInQueue()) return false
        if (playlistActive && playlistItems.any { it.videoId == id }) return false
        val resolved = relatedNext
        return resolved?.first != id || resolved.second != null
    }

    private fun restartAvailable(): Boolean =
        engine.snapshot.value.positionMs > PREVIOUS_RESTART_MS

    private fun queueNavAvailability(): QueueNav {
        val id = playbackQueueId()
        if (mediaKind == MediaKind.SHORTS || id == null) return QueueNav(false, false)
        val next = nextTarget(id) != null || relatedAvailable(id)
        val previous = restartAvailable() || previousTarget(id) != null || sessionTrail.isNotEmpty()
        return QueueNav(next, previous)
    }

    internal data class QueueNav(val next: Boolean, val previous: Boolean)

    fun skipToNext() {
        val id = playbackQueueId()
        if (mediaKind == MediaKind.SHORTS || id == null) return
        nextTarget(id)?.let { startPlayback(it.url, keepMini = true); return }
        if (relatedAvailable(id)) startRelatedNext(id)
    }

    fun skipToPrevious() {
        if (engine.snapshot.value.positionMs > PREVIOUS_RESTART_MS) {
            engine.seekTo(0)
            return
        }
        val id = playbackQueueId()
        if (mediaKind == MediaKind.SHORTS || id == null) return
        previousTarget(id)?.let { startPlayback(it.url, keepMini = true); return }
        // Plain watch without queue/playlist: walk back this session's trail.
        sessionTrail.removeLastOrNull()?.let { startPlayback(VideoId.watchUrl(it), keepMini = true, recordHistory = false) }
    }

    /**
     * Manual next resolves the related target even when autoplay is off;
     * a resolved miss keeps the current playback untouched and refreshes
     * the button to its now-known disabled state.
     */
    private fun startRelatedNext(id: String, requireEnded: Boolean = false, honorAutoplay: Boolean = false) {
        val provider = nextVideo ?: return
        if (honorAutoplay &&
            (!prefs.isEnabled(PreferenceKeys.ENABLE_AUTOPLAY) || _uiState.value.loopMode != LoopMode.QUEUE_NEXT)
        ) return
        val resolved = relatedNext
        if (resolved?.first == id) {
            if (requireEnded && (!engine.snapshot.value.ended || !_uiState.value.visible)) return
            val next = resolved.second
            if (next != null && next != id) startPlayback(VideoId.watchUrl(next), keepMini = true)
            return
        }
        val token = playbackRequestSeq
        val request = autoplayRequestId.incrementAndGet()
        // The prefetch belongs to the identity it was requested under; an
        // identity switch discards it so Next never uses another identity's data.
        val owner = queue.currentOwner()
        viewModelScope.launch {
            val next = try { provider(id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (request != autoplayRequestId.get() || token != playbackRequestSeq) return@launch
            if (queue.currentOwner() != owner) return@launch
            if (honorAutoplay && (!prefs.isEnabled(PreferenceKeys.ENABLE_AUTOPLAY) ||
                _uiState.value.loopMode != LoopMode.QUEUE_NEXT || mediaKind != MediaKind.WATCH)) return@launch
            if (relatedNext?.first != id) relatedNext = id to next
            if (requireEnded && (!engine.snapshot.value.ended || !_uiState.value.visible)) return@launch
            if (next != null && next != id) startPlayback(VideoId.watchUrl(next), keepMini = true)
            else refreshNavAvailability()
        }
    }

    private fun refreshNavAvailability() {
        val nav = queueNavAvailability()
        pushNavIfChanged(nav.next, nav.previous)
        _uiState.update { it.copy(hasNext = nav.next, hasPrevious = nav.previous) }
    }

    /** Resolves the related next once per video so the Next button is honest. */
    private fun maybePrefetchRelated(s: PlaybackSnapshot) {
        val id = s.videoId ?: return
        if (mediaKind != MediaKind.WATCH || !s.prepared) return
        if (queueActive() || playlistActive || nextVideo == null) return
        if (relatedNext?.first == id || relatedJob?.isActive == true) return
        val provider = nextVideo!!
        val token = playbackRequestSeq
        val owner = queue.currentOwner()
        relatedJob = viewModelScope.launch {
            val next = try { provider(id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (token != playbackRequestSeq) return@launch
            if (queue.currentOwner() != owner) return@launch
            if (relatedNext?.first != id) relatedNext = id to next
            refreshNavAvailability()
        }
    }

    override fun onNext() = skipToNext()

    override fun onPrevious() = skipToPrevious()

    override fun onQueueItem(url: String) {
        playlistActive = false
        playlistItems = emptyList()
        startPlayback(url, keepMini = true)
    }

    override fun onToggleControls() =
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }

    override fun onHideControls() =
        _uiState.update { it.copy(controlsVisible = false) }

    override fun onHint(text: String?) {
        _hintState.value = text
    }

    override fun onFullscreenToggle() {
        val next = !_uiState.value.fullscreen
        fullscreenState.onManualChange(next)
        setFullscreen(next)
    }

    override fun onFullscreenSwipeExit() = setFullscreenManual(false)

    override fun onFullscreenSwipeRestore() {
        if (!_uiState.value.fullscreen) return
        fullscreenState.onManualChange(false)
        setFullscreen(false, restoreEntryOrientation = true)
    }

    /** Content pulls enter and fullscreen swipes exit in the current orientation. */
    fun setFullscreenManual(fullscreen: Boolean) {
        if (_uiState.value.fullscreen == fullscreen) return
        fullscreenState.onManualChange(fullscreen)
        setFullscreen(fullscreen, keepOrientation = true)
    }

    /**
     * Physical device tilt (OrientationEventListener). Landscape + system
     * auto-rotate enters; only an auto-entered session leaves on portrait.
     */
    fun onPhysicalOrientation(degrees: Int, systemAutoRotate: Boolean, inPip: Boolean) {
        if (fullscreenState.updateOrientation(degrees, systemAutoRotate, inPip)) applyAutoFullscreen()
    }

    /**
     * Apply enter/exit using the last sensor band. Watch can become visible
     * while already landscape; the listener would otherwise skip a same-band
     * event and leave the player embedded.
     */
    private fun applyAutoFullscreen() {
        val s = _uiState.value
        fullscreenState.evaluate(
            FullscreenState.Window(
                visible = s.visible,
                fullscreen = s.fullscreen,
                mini = s.mini,
                casting = s.casting,
                locked = s.locked,
            ),
        )?.let { setFullscreen(it) }
    }

    override fun onLockToggle() = setLocked(!_uiState.value.locked)

    override fun onResize(mode: ResizeMode) {
        _uiState.update { it.copy(resizeMode = mode) }
        if (prefs.isEnabled(PreferenceKeys.REMEMBER_RESIZE_MODE)) {
            cache.put(KEY_RESIZE, mode.name, PREF_TTL_MS)
        }
    }

    override fun onRetry() = engine.retry()
    override fun onErrorDetails() {
        _uiState.update { it.copy(failureDialogVisible = it.failureReport != null) }
    }
    fun dismissPlaybackFailure() {
        _uiState.update { it.copy(failureDialogVisible = false) }
    }

    override fun onQueueEnabled(enabled: Boolean) {
        playlistActive = false
        playlistItems = emptyList()
        queue.setEnabled(enabled)
    }
    override fun onQueueClear() = queue.clear()
    override fun onQueueRemove(videoId: String) = queue.remove(videoId)
    /** [owner] is the queue owner captured when the drag started; null applies to the active one. */
    override fun onQueueMove(fromIndex: Int, toIndex: Int, owner: QueueOwner?) = queue.move(fromIndex, toIndex, owner)

    /** Page "Add to queue" (media menu) — the manual queue population path. */
    override fun onQueueAdd(item: QueueItem) {
        playlistActive = false
        playlistItems = emptyList()
        if (!queue.state.value.enabled) queue.setEnabled(true)
        queue.add(item)
    }

    /** "Play next" — same manual population path, but positioned after the playing entry. */
    override fun onQueueAddNext(item: QueueItem) {
        playlistActive = false
        playlistItems = emptyList()
        if (!queue.state.value.enabled) queue.setEnabled(true)
        queue.addNext(item)
    }

    /** True when [videoId] is already in the manual queue (dedupe feedback for menus). */
    fun queueContains(videoId: String): Boolean = queue.state.value.items.any { it.videoId == videoId }

    override fun onCastStop() {
        castReloadJob?.cancel()
        castReloadJob = null
        if (dlnaActive() || dlnaTransfer?.isActive == true) {
            userCastStop = true
            dlnaTransfer?.cancel()
            dlnaTransfer = null
            detachCastAndResumeLocal(dlna?.stopCasting() ?: 0L, resume = true)
            dlna?.endSession()
            cast.stopLink()
            return
        }
        // Guard against a stop tap after the session already ended: the
        // detached castPlayer keeps its last position, and seeking the local
        // player there would jump the video backward (or to 0) unprompted.
        // chromecastSession==false means the session-ended teardown already
        // ran, so there is nothing to stop.
        if (!cast.state.value.chromecastSession) return
        userCastStop = true
        detachCastAndResumeLocal(cast.stopCasting(), resume = true)
        cast.endSession()
    }

    override fun onCastDevice(id: String) {
        onCastFailureDismiss()
        if (id.startsWith(DlnaController.ID_PREFIX)) {
            startDlnaCast(id)
            return
        }
        if (dlnaActive() || dlnaTransfer?.isActive == true) onCastStop()
        // Only select the route; onSessionStarted performs the single load
        // once the CastSession actually exists (startCasting before it is a
        // silent no-op and races the session-started path).
        cast.selectDevice(id)
    }

    override fun onCastFailureDismiss() {
        dlna?.clearFailure()
        cast.clearFailure()
        _uiState.update { it.copy(castFailure = null) }
    }

    override fun onCastDiscovery(enabled: Boolean) {
        cast.setDiscoveryEnabled(enabled)
        dlna?.setDiscoveryEnabled(enabled)
    }

    override fun onShareCastLink(onReady: () -> Unit) {
        // A DLNA session owns the proxy for its own stream; reconfiguring it
        // for the link page would retarget the renderer's byte requests.
        if (dlnaActive()) return
        val token = playbackRequestSeq
        viewModelScope.launch {
            var stage = "media"
            try {
                val source = engine.prepareCastSource()
                if (token != playbackRequestSeq || dlnaActive() || dlnaTransfer?.isActive == true) return@launch
                checkNotNull(source) { "No compatible media is available for local sharing" }
                stage = "proxy"
                checkNotNull(cast.ensureProxy(source)) { "Could not publish media on the local network" }
                onReady()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token != playbackRequestSeq) return@launch
                val report = com.hhst.youtubelite.cast.CastFailureReport.create(
                    "LOCAL_LINK", null, _uiState.value.videoId, stage, error)
                _uiState.update { it.copy(castFailure = report) }
            }
        }
    }

    override fun onCloseCastLink() {
        if (isCasting()) return
        cast.stopLink()
    }

    override fun onSponsorSkipCancel() {
        engine.cancelSponsorSkip()
    }

    override fun onSponsorSkipNow() {
        engine.skipSponsorNow()
    }

    override fun onSponsorChipSkip() {
        engine.skipSponsorChipSegment()
    }

    fun onEdgeSlider(volume: Boolean, percent: Int) {
        _gestureState.value = GestureUi.EdgeSlider(volume, percent)
    }

    override fun onGestureEnd() {
        scrubAnchorMs = null
        _gestureState.value = null
    }

    override fun onMiniClose() {
        val casting = _uiState.value.casting
        if (casting) engine.pauseLocal()
        stopAndHide(pausePlayback = !casting)
    }

    override fun onMiniRestore() {
        val id = _uiState.value.videoId ?: return
        appContext?.let { context ->
            prepareWindowHandoff {
                if (_uiState.value.visible && _uiState.value.mini && _uiState.value.videoId == id)
                    com.hhst.youtubelite.navigation.WatchTransition.restore(context, id)
            }
        }
    }

    fun prepareWindowHandoff(onReady: () -> Unit) =
        com.hhst.youtubelite.navigation.WatchTransition.captureVideo(_uiState.value.videoId, attachedSurface?.get(), onReady)

    fun hasRenderedFrame(surface: SurfaceView): Boolean =
        attachedSurface?.get() === surface && surface.holder.surface.isValid &&
            engine.snapshot.value.let { it.firstFrameRendered && it.videoId == _uiState.value.videoId }

    fun currentCastLink(): String? = cast.state.value.linkUrl

    private fun teardownCast() {
        cast.stopCasting()
        cast.endSession()
        onHint(hintCastFailed)
    }

    private fun dlnaActive(): Boolean = dlna?.state?.value?.session == true

    private fun isCasting(): Boolean = cast.state.value.chromecastSession || dlnaActive()

    /**
     * Ends whichever route is active after a receiver error. The engine has
     * already detached (and resumed local unless a recast was in flight).
     */
    private fun teardownCasting() {
        castReloadJob?.cancel()
        castReloadJob = null
        if (dlnaActive()) {
            dlna?.stopCasting()
            dlna?.endSession()
            cast.stopLink()
            onHint(hintCastFailed)
        } else {
            teardownCast()
        }
    }

    /**
     * Loads the current video on whichever route is active. DLNA needs the
     * muxed pick and a range-served URL from the shared proxy; chromecast
     * keeps the adaptive DASH path. Null = load failed, caller tears down.
     */
    private suspend fun recastToActiveRoute(positionMs: Long): CastSource? {
        val token = playbackRequestSeq
        if (dlnaActive()) {
            val dlna = dlna ?: return null
            val source = engine.prepareMuxedCastSource() ?: return null
            if (token != playbackRequestSeq || !dlnaActive()) return null
            val streamUrl = cast.dlnaStreamUrl(source, dlna.selectedHost()) ?: return null
            return source.takeIf { dlna.startCasting(source, positionMs, streamUrl) && token == playbackRequestSeq }
        }
        if (!cast.state.value.chromecastSession) return null
        val source = engine.prepareCastSource() ?: return null
        return source.takeIf { cast.startCasting(source, positionMs) }
    }

    /**
     * DLNA route selection: no session handshake exists, so the load happens
     * immediately. One active route — a chromecast session and any live link
     * proxy yield before the muxed URL is published.
     */
    private fun startDlnaCast(deviceId: String) {
        val dlna = dlna ?: return
        if (dlna.state.value.session || dlnaTransfer?.isActive == true) onCastStop()
        dlna.selectDevice(deviceId)
        val token = playbackRequestSeq
        val resumeOnFailure = engine.snapshot.value.userWantsPlay
        dlnaTransfer = viewModelScope.launch {
            var stage = "media"
            try {
                if (cast.state.value.chromecastSession) {
                    userCastStop = true
                    detachCastAndResumeLocal(cast.stopCasting(), resume = false)
                    cast.endSession()
                }
                cast.stopLink()
                val source = engine.prepareMuxedCastSource()
                if (token != playbackRequestSeq) return@launch
                if (source == null) {
                    showCastFailure(deviceId, "media", IllegalStateException("No compatible combined audio and video stream is available"))
                    dlna.endSession()
                    onHint(hintCastFailed)
                    return@launch
                }
                stage = "proxy"
                val streamUrl = cast.dlnaStreamUrl(source, dlna.selectedHost())
                if (streamUrl == null) {
                    showCastFailure(deviceId, "proxy", IllegalStateException("Could not publish media on the local network"))
                    dlna.endSession()
                    onHint(hintCastFailed)
                    return@launch
                }
                engine.pause()
                stage = "start"
                if (dlna.startCasting(source, _positionState.longValue, streamUrl)) {
                    if (token != playbackRequestSeq) {
                        dlna.stopCasting()
                        dlna.endSession()
                        cast.stopLink()
                        return@launch
                    }
                    // Re-arm the remote-stop echo for the new DLNA session: the
                    // stale userCastStop from a previous user stop (or from the
                    // chromecast yield above) would suppress TV-side teardown.
                    userCastStop = false
                    engine.attachCastPlayer(dlna.remotePlayer())
                    engine.markReceiverSource(source)
                } else {
                    val report = dlna.state.value.failure
                    if (report != null) _uiState.update { it.copy(castFailure = report) }
                    else showCastFailure(deviceId, "start", IllegalStateException("The selected renderer is no longer available"))
                    dlna.stopCasting()
                    dlna.endSession()
                    cast.stopLink()
                    onHint(hintCastFailed)
                    if (resumeOnFailure) engine.play()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token != playbackRequestSeq) return@launch
                showCastFailure(deviceId, stage, error)
                dlna.stopCasting()
                dlna.endSession()
                cast.stopLink()
                if (resumeOnFailure) engine.play()
            }
        }
    }

    private fun showCastFailure(deviceId: String, stage: String, error: Throwable) {
        val device = _uiState.value.castDevices.firstOrNull { it.id == deviceId }
        val report = com.hhst.youtubelite.cast.CastFailureReport.create("DLNA", device, _uiState.value.videoId, stage, error)
        _uiState.update { it.copy(castFailure = report) }
    }

    private fun detachCastAndResumeLocal(positionMs: Long, resume: Boolean) {
        val keepLocal = engine.attachCastPlayer(null)
        if (!keepLocal && resume) {
            if (positionMs > 0L) engine.seekTo(positionMs)
            engine.play()
        }
    }

    fun refreshPipAvailability() {
        _uiState.update { it.copy(pipAvailable = pipEnabled()) }
    }

    private fun pipEnabled(): Boolean = appContext?.let(PipSupport::isSupported) == true &&
        prefs.isEnabled(PreferenceKeys.ENABLE_PIP)

    fun refreshCastState() {
        cast.publishRoutes()
        cast.refreshLinkBanner()
    }

    /**
     * Session established: push the current video onto the receiver. Control
     * delegation attaches only on a successful load — a failed load resumes
     * local playback instead of delegating to a dead receiver.
     */
    private fun onCastSessionStarted() {
        // A fresh session re-arms the session-end echo: the user is casting
        // again, so a later TV-side disconnect must hand playback back.
        userCastStop = false
        val token = playbackRequestSeq
        viewModelScope.launch {
            // One active route: a DLNA session yields the proxy to the receiver.
            if (dlnaActive()) {
                dlna?.stopCasting()
                dlna?.endSession()
            }
            val source = engine.prepareCastSource()
            if (token != playbackRequestSeq || !cast.state.value.chromecastSession) return@launch
            if (source == null) { teardownCast(); return@launch }
            engine.pause()
            if (cast.startCasting(source, _positionState.longValue)) {
                engine.attachCastPlayer(cast.remotePlayer())
                engine.markReceiverSource(source)
            } else {
                teardownCast()
                engine.play()
            }
        }
    }

    /**
     * Quality/audio change while casting: publish a new proxy generation and
     * reload the active receiver at the current position — the adaptive
     * receiver-capable pick for chromecast, the best muxed file for DLNA.
     */
    private fun recastPublishedSource() {
        castReloadJob?.cancel()
        castReloadJob = null
        if (dlnaActive()) {
            val dlna = dlna ?: return
            val token = playbackRequestSeq
            val remote = dlna.remotePlayer() ?: return
            val position = remote.currentPosition
            val autoplay = remote.playWhenReady
            castReloadJob = viewModelScope.launch {
                val source = engine.currentMuxedCastSource() ?: return@launch
                val streamUrl = cast.dlnaStreamUrl(source, dlna.selectedHost()) ?: return@launch
                if (dlna.startCasting(source, position, streamUrl, autoplay)) {
                    if (token == playbackRequestSeq) engine.markReceiverSource(source)
                } else if (token == playbackRequestSeq) {
                    teardownCasting()
                }
            }
            return
        }
        if (!cast.state.value.chromecastSession) return
        val remote = cast.remotePlayer() ?: return
        val source = engine.currentCastSource() ?: return
        if (cast.startCasting(source, remote.currentPosition, remote.playWhenReady)) {
            engine.markReceiverSource(source)
        } else {
            teardownCast()
        }
    }

    private fun onPlaybackEnded() {
        // The Shorts host advances its own feed and pager, never the watch queue.
        if (mediaKind == MediaKind.SHORTS) return
        if (!engine.snapshot.value.ended || handledEndSeq == playbackRequestSeq) return
        handledEndSeq = playbackRequestSeq
        // Queue and playlist playback take priority over page autoplay; both
        // navigate strictly inside their own context (no stale-queue hijack).
        val queueContext = queueActive() && currentInQueue()
        val current = playbackQueueId()
        val target = when (_uiState.value.loopMode) {
            LoopMode.QUEUE_NEXT ->
                if (queueContext) queue.nextStrict(current) else null
            LoopMode.QUEUE_RANDOM ->
                if (queueContext) queue.random(current) else null
            LoopMode.LOOP_ONE, LoopMode.PAUSE_AT_END -> null
        }
        if (target != null) {
            // Auto-advance keeps the current surface (stays mini while suspended).
            startPlayback(target.url, keepMini = true)
            return
        }
        if (!queueContext && playlistItems.any { it.videoId == current } && _uiState.value.loopMode == LoopMode.QUEUE_NEXT) {
            playlistNeighbor(1)?.let { startPlayback(it.url, keepMini = true) }
            return
        }
        if (!queueContext && !playlistActive && mediaKind == MediaKind.WATCH && _uiState.value.loopMode == LoopMode.QUEUE_NEXT) {
            current?.let { startRelatedNext(it, requireEnded = true, honorAutoplay = true) }
        }
    }

    // Weak on purpose: this VM is a process-scoped singleton; a missed
    // dispose must not pin the activity's view in memory. The engine holds
    // the surface until the next attach/detach (stop() does not clear it),
    // so only the normal dispose path releases a dead activity's view.
    private var attachedSurface: WeakReference<SurfaceView>? = null

    fun attachSurface(surface: SurfaceView) {
        attachedSurface = WeakReference(surface)
        engine.setVideoSurface(surface)
    }

    /**
     * A same-frame surface swap (mini -> embedded) attaches the replacement
     * before the old view is disposed; only the surface still attached may
     * clear the player output, otherwise the fresh one goes black.
     */
    fun detachSurface(surface: SurfaceView) {
        if (attachedSurface?.get() !== surface) return
        attachedSurface = null
        engine.setVideoSurface(null)
    }

    fun setFullscreen(fullscreen: Boolean, keepOrientation: Boolean = false, restoreEntryOrientation: Boolean = false) =
        _uiState.update { it.copy(fullscreen = fullscreen,
            fullscreenKeepsOrientation = keepOrientation,
            fullscreenRestoresEntryOrientation = restoreEntryOrientation, controlsVisible = true) }

    fun setLocked(locked: Boolean) =
        _uiState.update { it.copy(locked = locked, controlsVisible = !locked) }

    fun pauseForBackground() = engine.pauseLocal()

    fun onPipDismissed() {
        val remote = isCasting() || cast.state.value.linkConnected
        stopAndHide(pausePlayback = !remote)
        if (remote) engine.pauseLocal() else engine.stop()
    }

    /** Explicit PiP headphone action; applies to this background session only. */
    var audioOnlyBackground: Boolean = false
        private set

    fun playAudioOnlyInBackground() {
        audioOnlyBackground = true
        fullscreenState.clearAutoEntry()
        _uiState.update { it.copy(fullscreen = false, locked = false) }
        engine.setAudioOnly(true)
        engine.setVideoVisible(false)
    }

    fun returnFromAudioOnlyBackground() {
        if (!audioOnlyBackground) return
        audioOnlyBackground = false
        _uiState.update { it.copy(mini = false, controlsVisible = true) }
        engine.setAudioOnly(false)
        engine.setVideoVisible(_uiState.value.visible)
    }

    /**
     * Double-back exit. With background play enabled this mirrors the official
     * app: only the surface hides, playback (local or cast) continues and the
     * media notification owns control — a later receiver disconnect still
     * resumes local playback because [userCastStop] stays false. With the pref
     * off, leaving the app is silent: stop local/cast playback entirely.
     */
    fun stopOnExit() {
        if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) {
            stopAndHide(pausePlayback = false)
            return
        }
        stopPlayback()
    }

    /** Explicit Stop ends local and remote playback regardless of background-play preferences. */
    fun stopPlayback() {
        userCastStop = true
        dlnaTransfer?.cancel()
        dlnaTransfer = null
        castReloadJob?.cancel()
        castReloadJob = null
        dlna?.stopCasting()
        dlna?.endSession()
        // A selected receiver owns a pending session before chromecastSession becomes true.
        cast.stopCasting()
        cast.endSession()
        stopAndHide()
        engine.stop()
    }

    fun initializeCast() {
        cast.initialize()
    }

    companion object {
        private const val SEEK_ACCUM_WINDOW_MS = 600L
        private const val KEY_RESIZE = "player:resize_mode"
        private const val KEY_SUBTITLE_STYLE = "player:subtitle_style"

        /** Cast handoff: never wait on a stuck load forever. */
        private const val PREPARED_WAIT_TIMEOUT_MS = 30_000L

        /** Previous tap past this position restarts the current video. */
        private const val PREVIOUS_RESTART_MS = 3_000L

        /** Bound on the plain-watch Previous trail. */
        private const val MAX_SESSION_TRAIL = 50

        /** Cast handoff readiness: the target video prepared or failed. */
        internal fun loadSettledFor(snapshot: PlaybackSnapshot, videoId: String?): Boolean =
            videoId != null && snapshot.videoId == videoId &&
                (snapshot.prepared || snapshot.error != null || snapshot.notice != null)

        internal fun gesturePrefKey(zone: GestureZone, fullscreen: Boolean): String = when (zone) {
            GestureZone.TAP ->
                if (fullscreen) PreferenceKeys.GESTURE_TAP_FULLSCREEN
                else PreferenceKeys.GESTURE_TAP_WINDOWED
            GestureZone.DOUBLE_TAP ->
                if (fullscreen) PreferenceKeys.GESTURE_DOUBLE_TAP_FULLSCREEN
                else PreferenceKeys.GESTURE_DOUBLE_TAP_WINDOWED
            GestureZone.LONG_PRESS ->
                if (fullscreen) PreferenceKeys.GESTURE_LONG_PRESS_FULLSCREEN
                else PreferenceKeys.GESTURE_LONG_PRESS_WINDOWED
            GestureZone.BRIGHTNESS ->
                if (fullscreen) PreferenceKeys.GESTURE_BRIGHTNESS_FULLSCREEN
                else PreferenceKeys.GESTURE_BRIGHTNESS_WINDOWED
            GestureZone.VOLUME ->
                if (fullscreen) PreferenceKeys.GESTURE_VOLUME_FULLSCREEN
                else PreferenceKeys.GESTURE_VOLUME_WINDOWED
            GestureZone.SEEK ->
                if (fullscreen) PreferenceKeys.GESTURE_SEEK_FULLSCREEN
                else PreferenceKeys.GESTURE_SEEK_WINDOWED
            GestureZone.FULLSCREEN_SWIPE ->
                if (fullscreen) PreferenceKeys.GESTURE_FULLSCREEN_FULLSCREEN
                else PreferenceKeys.GESTURE_FULLSCREEN_WINDOWED
        }
    }
}
