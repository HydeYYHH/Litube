@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.R
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.PageOrigin
import com.hhst.youtubelite.browser.PlayerHooks
import com.hhst.youtubelite.browser.WatchPage
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.CastDevice
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
import com.hhst.youtubelite.player.engine.PlaybackDiagnostics
import com.hhst.youtubelite.player.engine.PlaybackSnapshot
import com.hhst.youtubelite.player.engine.SubtitleTrack
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.QueueRepository
import com.hhst.youtubelite.player.service.PlaybackCommandRouter
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import com.hhst.youtubelite.player.surface.AutoFullscreen
import com.hhst.youtubelite.player.surface.GestureMath.GestureZone
import com.hhst.youtubelite.player.surface.PlayerPlaybackActions
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.player.surface.ResizeMode
import com.hhst.youtubelite.player.surface.SubtitleStyle
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Structured per-gesture overlay state shown on the player surface. */
sealed interface GestureUi {
    /** Double-tap seek arcs: side + accumulating offset. */
    data class DoubleTapSeek(val forward: Boolean, val accumMs: Long) : GestureUi
    /** Long-press 2x hold. Only set while the hold is live; cleared on end. */
    data object SpeedHold : GestureUi
    /** Scrub / timebar drag target bubble. */
    data class Scrub(val targetMs: Long, val deltaMs: Long) : GestureUi
    /** Edge slider for volume or brightness. */
    data class EdgeSlider(val volume: Boolean, val percent: Int) : GestureUi
}

/** Player-facing UI state: engine snapshot + surface-only flags. */
data class PlayerUiState(
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
    val visible: Boolean = false,
    val fullscreen: Boolean = false,
    val controlsVisible: Boolean = true,
    val loading: Boolean = false,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val title: String = "",
    val author: String? = null,
    val isLive: Boolean = false,
    val videoId: String? = null,
    val url: String? = null,
    val loopMode: LoopMode = LoopMode.QUEUE_NEXT,
    val qualityLabel: String? = null,
    val qualities: List<String> = emptyList(),
    val subtitleKey: String? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrackChoice> = emptyList(),
    val audioTrackKey: String? = null,
    val error: String? = null,
    /** True while the time bar is being dragged; keeps chrome from auto-hiding. */
    val scrubbing: Boolean = false,
    /** Queue navigation availability for prev/next buttons. */
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val locked: Boolean = false,
    val queueItems: List<QueueItem> = emptyList(),
    val queueEnabled: Boolean = false,
    val sponsorSegments: List<SponsorBlockManager.Segment> = emptyList(),
    val resizeMode: ResizeMode = ResizeMode.Fit,
    val pipAvailable: Boolean = false,
    val ended: Boolean = false,
    val mini: Boolean = false,
    val pageHeightDp: Int? = null,
    /** Page player's viewport-relative top from player-hook.js, in dp. */
    val pageTopDp: Int? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    /** Quality actually rendering; badge shows "Auto · 720p" when unpinned. */
    val activeQuality: String? = null,
    val subtitleEnabled: Boolean = false,
    /** Info-sheet stream details. */
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoItag: Int? = null,
    val videoBitrate: Int = 0,
    val videoFps: Int = 0,
    val audioBitrate: Int = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    /** Whole seconds left on the sponsor pre-skip card; null hides it. */
    val sponsorCountdownSec: Int? = null,
    /** Manual skip chip while inside a suppressed segment. */
    val sponsorChip: Boolean = false,
    /** True when [sponsorChip] targets a highlight point (chip jumps TO it). */
    val sponsorChipHighlight: Boolean = false,
    val casting: Boolean = false,
    val castingDeviceName: String? = null,
    val castLinkUrl: String? = null,
    val castLinkConnected: Boolean = false,
    val castAvailable: Boolean = false,
    val castDevices: List<CastDevice> = emptyList(),
    /** User-adjustable subtitle appearance (persists across videos). */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
)

/**
 * Thin reducer between the surface and [PlaybackApi].
 *
 * The engine is the single source of truth for playback; this class keeps
 * UI-only state (visibility, fullscreen, controls, hints) and forwards intents.
 * Queue navigation and end-of-playback auto-advance live here.
 *
 * Registered as a Koin single (process scope): background play, MediaSession
 * keys and queue auto-advance must outlive the activity, so [onCleared] never
 * runs and nothing here is torn down on activity death.
 *
 * Composition wiring contract: BrowserScreen attaches/detaches
 * [onRestoreWatch], [onWatchClosed] and [watchPage] in DisposableEffects.
 * All of them may be null whenever the browser is not composed (activity
 * destroyed with background play still running); page-playlist navigation
 * and the WebView-back "previous" fallback then degrade to queue-only until
 * the browser re-attaches.
 */
class PlayerViewModel(
    private val engine: PlaybackApi,
    private val queue: QueueRepository,
    private val prefs: ExtensionManager,
    private val cache: JsonCache,
    private val cast: CastController,
    private val appContext: Context? = null,
    private val haptics: HapticsController? = null,
) : ViewModel(), PlayerHooks, PlayerPlaybackActions {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Layout fallback keeps the watch tab active; it must not suspend the tab
    // or persist the user's mini-player mode when the window grows again.
    @Volatile private var compactPlayer = false

    fun setCompactPlayer(compact: Boolean) {
        if (compactPlayer == compact) return
        compactPlayer = compact
        watchPage?.evaluate("window.__syncPlayerCompact && window.__syncPlayerCompact();") { }
    }

    override fun isPlayerCompact(origin: PageOrigin): Boolean {
        val state = _uiState.value
        return compactPlayer && state.visible && !state.fullscreen && !state.mini &&
            acceptsPageCallback(playbackOrigin, origin)
    }

    // Position lives outside [PlayerUiState]: the ~4 Hz snapshot tick must
    // not recompose the whole surface tree, only the time readouts that
    // observe these States. (PlayerSurface receives them as parameters.)
    private val _positionState = mutableLongStateOf(0L)
    private val _bufferedPositionState = mutableLongStateOf(0L)

    // Transient overlays live outside [PlayerUiState] for the same reason:
    // pointer-rate gesture updates (~60-120 Hz) and subtitle cue swaps
    // (1-4 Hz) must recompose only the leaf overlay that renders them.
    private val _gestureState = mutableStateOf<GestureUi?>(null)
    private val _hintState = mutableStateOf<String?>(null)
    private val _subtitleCuesState = mutableStateOf<List<String>>(emptyList())

    /** Current playback position in ms, updated on every engine snapshot. */
    val positionState: State<Long> get() = _positionState

    /** Buffered-ahead position in ms, updated on every engine snapshot. */
    val bufferedPositionState: State<Long> get() = _bufferedPositionState

    /** Structured gesture overlay (DoubleTapSeek/Scrub/SpeedHold/EdgeSlider); null hides. */
    val gestureState: State<GestureUi?> get() = _gestureState

    /** One-shot hint text (e.g. "2x", "Copied"); null hides. */
    val hintState: State<String?> get() = _hintState

    /** Current subtitle cue lines rendered by SubtitleOverlay. */
    val subtitleCuesState: State<List<String>> get() = _subtitleCuesState

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cast failure hint, resolved lazily (context may be null in tests). */
    private val hintCastFailed: String by lazy {
        appContext?.getString(R.string.cast_failed) ?: "Cast failed"
    }

    /** Unavailable-subtitle hint, resolved lazily (context may be null in tests). */
    private val hintSubtitleUnavailable: String by lazy {
        appContext?.getString(R.string.subtitle_unavailable) ?: "Subtitle unavailable"
    }

    /** Avoid rewriting the queue on every 4 Hz snapshot tick. */
    private var queuedSignature: String? = null

    /** Speed captured when the long-press 2x gesture starts. */
    private var speedBeforeHold: Float? = null

    /** Auto-rotate entered fullscreen (not a tap/swipe). Portrait exits only this. */
    private var autoEnteredFullscreen = false
    /** After a manual exit, wait for portrait so landscape does not bounce back in. */
    private var suppressAutoEnter = false
    private var lastOrientationBand = AutoFullscreen.Band.OTHER
    private var lastSystemAutoRotate = true
    private var lastInPip = false

    /** Double-tap seek accumulation. */
    private var seekAccumMs = 0L
    private var lastSeekTapAt = 0L

    /** Live-scrub anchor: position captured on the first preview of a scrub. */
    private var scrubAnchorMs: Long? = null

    /** WebView document that started the current media; filters stale callbacks. */
    private var playbackOrigin: PageOrigin? = null

    /** Supersedes in-flight cast-handoff waiters when a newer play/close wins. */
    private var playbackRequestSeq = 0

    /** Last notification nav push; snapshots tick 4x/s, refresh is not free. */
    private var lastNavNext: Boolean? = null
    private var lastNavPrev: Boolean? = null

    /**
     * Set when the user stops casting and kept until the next session starts:
     * a session-end echo arriving late (weak network) must never resume local
     * playback behind the user's back, and any later receiver-side disconnect
     * implies a fresh session, which clears the flag in [onCastSessionStarted].
     */
    private var userCastStop = false

    /** Browser navigates back to the watch URL when restoring the mini-player. */
    var onRestoreWatch: ((String) -> Unit)? = null

    /** Mini-player closed; the browser drops the suspended watch tab. */
    var onWatchClosed: (() -> Unit)? = null

    /** Watch-page operations (JS eval + back stack) provided by the browser. */
    var watchPage: WatchPage? = null

    /** Last "playlist present" answer from the page (Bridge push). */
    private var pageHasPlaylist = false

    /** Installed once in the init block; the process-scoped router is never re-pointed. */
    private val commandHandler = object : PlaybackCommandRouter.Handler {
        override fun onPlayPause() { this@PlayerViewModel.onPlayPause() }
        override fun onPlay() { engine.play() }
        override fun onPause() { engine.pause() }
        override fun onNext() { skipToNext() }
        override fun onPrevious() { skipToPrevious() }
    }

    /** True when [zone] is enabled in the current fullscreen state (prefs). */
    fun gestureEnabled(zone: GestureZone): Boolean =
        prefs.isEnabled(gesturePrefKey(zone, _uiState.value.fullscreen))

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
        viewModelScope.launch {
            queue.state.collect { q ->
                val nav = queueNavAvailability()
                pushNavIfChanged(nav.next, nav.previous)
                _uiState.update {
                    it.copy(
                        queueItems = q.items,
                        queueEnabled = q.enabled,
                        hasNext = nav.next,
                        hasPrevious = nav.previous,
                    )
                }
            }
        }
        viewModelScope.launch {
            cast.state.collect { c ->
                _uiState.update {
                    it.copy(
                        casting = c.chromecastSession,
                        castingDeviceName = c.deviceName,
                        castLinkUrl = c.linkUrl,
                        castLinkConnected = c.linkConnected,
                        castAvailable = c.available,
                        castDevices = c.devices,
                    )
                }
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
        // Receiver error: engine already detached (and resumed local unless
        // a recast was in flight). End the Cast session.
        engine.onCastError = {
            if (!userCastStop) teardownCast()
        }
        cast.urlRefresher = object : LocalStreamProxy.UrlRefresher {
            override fun refreshUrl(token: String, videoId: String?, itag: Int?) = engine.refreshCastUrl(token, videoId, itag)
            override fun backupUrl(token: String, videoId: String?, itag: Int?, failedProfile: String?) = engine.backupCastUrl(token, videoId, itag, failedProfile)
        }
        PlaybackCommandRouter.handler = commandHandler
        // Link banner: poll receiver/browser liveness only while a link is live.
        // 5 s keeps the connected banner snappy against the proxy's 30 s
        // liveness window without busy-polling an idle session.
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

    /** Pushes nav availability to the notification when it changed. */
    private fun pushNavIfChanged(next: Boolean, prev: Boolean) {
        if (next == lastNavNext && prev == lastNavPrev) return
        lastNavNext = next
        lastNavPrev = prev
        engine.setQueueNavigation(next, prev)
    }

    private fun mergeSnapshot(s: PlaybackSnapshot) {
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
                durationMs = s.durationMs,
                speed = s.speed,
                title = s.title,
                author = s.author,
                isLive = s.isLive,
                videoId = s.videoId,
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
                hasNext = nav.next,
                hasPrevious = nav.previous,
                sponsorSegments = s.sponsorSegments,
                // Controls are forced on only when the screen ENTERS the
                // error/ended state (the retry/replay affordances must show);
                // afterwards the user's hide toggle must stick instead of
                // being re-asserted by every snapshot that still carries
                // error/ended.
                controlsVisible = if ((s.error != null || s.ended) && !(it.error != null || it.ended)) {
                    true
                } else {
                    it.controlsVisible
                },
                ended = s.ended,
                videoWidth = s.videoWidth,
                videoHeight = s.videoHeight,
                chapters = s.chapters,
                videoCodec = s.videoFormat?.codec,
                audioCodec = s.audioFormat?.codec,
                videoItag = s.videoFormat?.itag,
                videoBitrate = s.videoFormat?.bitrate ?: 0,
                videoFps = s.videoFormat?.fps ?: 0,
                audioBitrate = s.audioFormat?.bitrate ?: 0,
                audioSampleRate = s.audioFormat?.sampleRate ?: 0,
                audioChannels = s.audioFormat?.audioChannels ?: 0,
                sponsorCountdownSec = s.sponsorCountdownSec,
                sponsorChip = s.sponsorChip,
                sponsorChipHighlight = s.sponsorChipHighlight,
            )
        }
        maybeSyncQueue(s)
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

    // -- PlayerHooks (from JS bridge) --

    /** A web feed suspends local playback until the user resumes the preserved watch item. */
    private var shortsPausedId: String? = null
    private var inShorts = false

    override fun playVideo(url: String, origin: PageOrigin) {
        if (inShorts || PageKind.isShorts(url)) return
        if (VideoId.parse(url) == shortsPausedId) { onReturnToWatch(url); return }
        shortsPausedId = null
        if (!origin.isHost) playbackOrigin = origin
        startPlayback(url, keepMini = _uiState.value.mini)
    }

    override fun prepareVideo(url: String) {
        if (!inShorts && !PageKind.isShorts(url)) engine.preparePlayback(url)
    }

    fun setVideoVisible(visible: Boolean) = engine.setVideoVisible(visible)

    fun onShortsOpened() {
        if (inShorts) return
        inShorts = true
        shortsPausedId = _uiState.value.videoId
        playbackRequestSeq++
        engine.cancelPending(pausePlayback = !_uiState.value.casting)
        engine.pauseLocal()
        if (speedBeforeHold != null) onSpeedHoldEnd()
        autoEnteredFullscreen = false
        _uiState.update { it.copy(visible = false, mini = false, fullscreen = false, locked = false) }
    }

    fun onShortsClosed() { inShorts = false }

    /**
     * Page left the active watch surface (in-place navigation or tab switch).
     * While suspended/mini the hook is a no-op — the mini-player
     * owns the player; otherwise the player hides completely.
     *
     * With background play enabled the hide is UI-only: the engine keeps
     * playing and the media notification owns control (there is no mini
     * surface left to represent it).
     */
    override fun hidePlayer(origin: PageOrigin) {
        if (!acceptsPageCallback(playbackOrigin, origin)) return
        val state = _uiState.value
        if (state.mini) return
        if (!state.visible) return
        if (prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY) || state.casting) {
            autoEnteredFullscreen = false
            _uiState.update {
                it.copy(visible = false, fullscreen = false, locked = false, mini = false)
            }
            if (state.casting) engine.pauseLocal()
            return
        }
        stopAndHide()
    }

    /** Page left watch (JS or tab URL). Same path as [hidePlayer]. */
    fun onLeaveWatch() = hidePlayer(PageOrigin.HOST)

    override fun setPlayerLayout(topDp: Int, heightDp: Int, origin: PageOrigin) {
        if (!acceptsPageCallback(playbackOrigin, origin)) return
        // Viewport-relative page-player box from player-hook.js. The top is
        // consumed by PlayerSurface (converted to window space with the
        // system-bar inset — the overlay sits one level above the inset-padded
        // WebView); the height sizes the embedded overlay.
        _uiState.update {
            it.copy(
                pageTopDp = topDp,
                pageHeightDp = heightDp.coerceAtLeast(0).takeIf { h -> h > 0 },
            )
        }
    }

    /** An active watch page expands the preserved local player. */
    fun onReturnToWatch(pageUrl: String? = null) {
        if (PageKind.isShorts(pageUrl)) return
        val state = _uiState.value
        if (shortsPausedId != null && VideoId.parse(pageUrl) == shortsPausedId) {
            inShorts = false
            _uiState.update { it.copy(visible = true, mini = false, fullscreen = false, controlsVisible = true) }
            return
        }
        if (state.mini) {
            if (shouldExpandMiniOnReturn(pageUrl)) {
                _uiState.update {
                    it.copy(mini = false, visible = true, controlsVisible = true)
                }
                applyAutoFullscreen()
            }
            return
        }
        if (state.visible) return
        val pageId = VideoId.parse(pageUrl) ?: return
        if (pageId != state.videoId) return
        val resumeUrl = state.url ?: return
        startPlayback(resumeUrl, keepMini = false)
    }

    /** Watch tab suspended by the browser. */
    fun enterMiniPlayer() {
        autoEnteredFullscreen = false
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

    private fun stopAndHide(pausePlayback: Boolean = true) {
        playbackRequestSeq++
        autoEnteredFullscreen = false
        _uiState.update {
            it.copy(visible = false, fullscreen = false, locked = false, mini = false)
        }
        engine.cancelPending(pausePlayback)
    }

    /** Queue navigation keeps the current surface. */
    private fun startPlayback(url: String, keepMini: Boolean) {
        playbackRequestSeq++
        val token = playbackRequestSeq
        val id = VideoId.parse(url)
        // A speed-hold or scrub interrupted by surface mode swaps (PiP enter,
        // mini collapse) never sees its End/Commit; drop the stale gesture
        // state so the next video starts clean.
        val switching = id != null && id != _uiState.value.videoId
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
                locked = false,
                url = url,
                videoId = id ?: it.videoId,
                loading = if (switching) true else it.loading,
                pageHeightDp = null,
                pageTopDp = null,
                sponsorCountdownSec = null,
                sponsorChip = false,
                sponsorChipHighlight = false,
                pipAvailable = pipEnabled(),
            )
        }
        engine.play(url)
        applyAutoFullscreen()
        // Queue advance mid-cast: the receiver must switch videos too, or the
        // TV keeps the old stream under the new title.
        // Skipped when the receiver already plays this exact video (user
        // re-opened its watch page mid-cast): engine.play short-circuited and
        // resumed the receiver — re-handing off would reload it at 0.
        if (
            cast.state.value.chromecastSession &&
            id != null &&
            engine.currentCastSource()?.videoId != id
        ) {
            viewModelScope.launch {
                val ready = withTimeoutOrNull(PREPARED_WAIT_TIMEOUT_MS) {
                    engine.snapshot.first { loadSettledFor(it, id) }
                }
                if (token != playbackRequestSeq) return@launch
                if (ready == null) return@launch
                if (engine.snapshot.value.error != null) return@launch
                fun handoffFailed() {
                    teardownCast()
                    onHint(hintCastFailed)
                }
                val source = engine.prepareCastSource()
                if (token != playbackRequestSeq || !cast.state.value.chromecastSession) return@launch
                if (source == null) {
                    handoffFailed()
                    return@launch
                }
                if (cast.startCasting(source, 0L)) {
                    engine.markReceiverIdentity(
                        source.videoId, source.title, engine.snapshot.value.author,
                    )
                    return@launch
                }
                handoffFailed()
            }
        }
    }

    override fun seekLoadedVideo(url: String, positionMs: Long): Boolean {
        val currentId = _uiState.value.videoId ?: return false
        val targetId = VideoId.parse(url) ?: return false
        if (currentId != targetId) return false
        mainHandler.post { engine.seekTo(positionMs) }
        return true
    }

    /** JS `Bridge.setPageHasPlaylist(bool)` lands here via Bridge. */
    fun onPageHasPlaylist(has: Boolean) {
        if (pageHasPlaylist == has) return
        pageHasPlaylist = has
        val nav = queueNavAvailability()
        pushNavIfChanged(nav.next, nav.previous)
        _uiState.update {
            it.copy(
                hasNext = nav.next,
                hasPrevious = nav.previous,
            )
        }
    }

    // -- PlayerPlaybackActions --

    override fun onPlayPause() = engine.playOrPause()
    override fun onSeek(positionMs: Long) = engine.seekTo(positionMs)

    /** Double-tap seek with accumulation (±10 s per tap, 600 ms window). */
    override fun onDoubleTapSeek(offsetMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekTapAt > SEEK_ACCUM_WINDOW_MS || (seekAccumMs > 0) != (offsetMs > 0)) seekAccumMs = 0L
        lastSeekTapAt = now
        seekAccumMs += offsetMs
        engine.seekRelative(offsetMs)
        _gestureState.value = GestureUi.DoubleTapSeek(forward = offsetMs > 0, accumMs = seekAccumMs)
        _hintState.value = null
    }

    /**
     * Scrub preview: the bubble tracks the finger; the seek lands on commit.
     */
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
        engine.seekTo(scrubTarget(anchor + offsetMs))
    }

    /** Time-bar drag: preview shows the target, seek lands on release. */
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

    /** Long-press 2x: save the current speed so release restores it. */
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
        engine.setSpeedHold(false)
        engine.setSpeed(speedBeforeHold ?: 1f)
        speedBeforeHold = null
        _gestureState.value = null
    }

    override fun onLoop() {
        engine.setLoopMode(_uiState.value.loopMode.next())
    }

    override fun onQuality(label: String?) { haptics?.perform(HapticsController.Event.SELECTION); engine.setQuality(label) }

    /**
     * Subtitle menu: no "off" row. Tapping the active language
     * turns captions off; any other pick enables and switches.
     */
    override fun onSubtitle(trackKey: String?) {
        haptics?.perform(HapticsController.Event.SELECTION)
        if (trackKey == null) return
        val current = _uiState.value
        val off = current.subtitleEnabled && current.subtitleKey == trackKey
        val applied = engine.setSubtitle(if (off) null else trackKey)
        onHint(if (applied || off) null else hintSubtitleUnavailable)
    }

    override fun onAudioTrack(trackKey: String?) = engine.setAudioTrack(trackKey)

    /** Applies the subtitle appearance live and persists it for next sessions. */
    override fun onSubtitleStyle(style: SubtitleStyle) {
        val snapped = style.clamped()
        if (snapped == _uiState.value.subtitleStyle) return
        _uiState.update { it.copy(subtitleStyle = snapped) }
        cache.put(KEY_SUBTITLE_STYLE, snapped.encode(), PREF_TTL_MS)
    }

    // -- queue / playlist navigation --

    private fun queueActive(): Boolean {
        val q = queue.state.value
        return q.enabled && q.items.isNotEmpty()
    }

    private fun playbackQueueId(): String? =
        engine.snapshot.value.videoId ?: _uiState.value.videoId

    private fun currentInQueue(): Boolean {
        val id = playbackQueueId() ?: return false
        return queue.state.value.items.any { it.videoId == id }
    }

    /** canGoBack is a synchronous WebView read; the 4 Hz tick must not re-ask it. */
    private var pageCanGoBackCache = false
    private var pageCanGoBackAtMs = 0L

    private fun pageCanGoBack(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - pageCanGoBackAtMs >= CAN_GO_BACK_REFRESH_MS) {
            pageCanGoBackAtMs = now
            pageCanGoBackCache = watchPage?.canGoBack() == true
        }
        return pageCanGoBackCache
    }

    /**
     * Navigation availability: local queue (enabled + non-empty) wins over the
     * YouTube playlist in the watch page; WebView back is the previous-only
     * fallback.
     */
    private fun queueNavAvailability(): QueueNav {
        val canGoBack = pageCanGoBack()
        val id = playbackQueueId()
        val queueActive = queueActive()
        return queueNav(
            queueActive = queueActive,
            queueHasNext = queueActive && queue.next(id) != null,
            inQueueAndHasPrevious = queueActive && currentInQueue() && queue.previous(id) != null,
            pageHasPlaylist = pageHasPlaylist,
            pageCanGoBack = canGoBack,
        )
    }

    internal data class QueueNav(val next: Boolean, val previous: Boolean)

    /** Menu picks and notification buttons land here. */
    fun skipToNext() {
        if (queueActive()) {
            queue.next(playbackQueueId())?.url?.let { startPlayback(it, keepMini = true) }
            return
        }
        if (pageHasPlaylist) navigatePlaylist(1)
    }

    fun skipToPrevious() {
        // A previous tap past the
        // first seconds restarts the current video; only near the start does
        // it actually go back — queue first, then the page playlist, then
        // WebView history.
        if (engine.snapshot.value.positionMs > PREVIOUS_RESTART_MS) {
            engine.seekTo(0)
            return
        }
        val canGoBack = watchPage?.canGoBack() == true
        if (queueActive()) {
            val prev = if (currentInQueue()) queue.previous(playbackQueueId()) else null
            if (prev != null) {
                startPlayback(prev.url, keepMini = true)
                return
            }
            if (canGoBack) watchPageGoBack()
            return
        }
        if (pageHasPlaylist) {
            navigatePlaylist(-1, fallbackToBack = canGoBack)
            return
        }
        if (canGoBack) watchPageGoBack()
    }

    /** Runs the page's playlist nav; sentinel answers route to WebView back. */
    private fun navigatePlaylist(dir: Int, fallbackToBack: Boolean = false) {
        val page = watchPage ?: return
        page.evaluate("window.__playlistNav && window.__playlistNav($dir);") { result ->
            when (result?.trim()) {
                // Any no-op answer falls back to WebView back navigation.
                null, "\"missing-playlist\"", "\"missing-current-video-id\"",
                "\"missing-current-video\"", "\"missing-target\"", "\"playlist-head\"",
                "\"playlist-end\"",
                -> if (fallbackToBack) watchPageGoBack()
                // "navigating": the page changes URL; the watch hook replays it.
            }
        }
    }

    private fun watchPageGoBack() {
        watchPage?.goBack()
    }

    override fun onNext() = skipToNext()

    override fun onPrevious() = skipToPrevious()

    override fun onQueueItem(url: String) = startPlayback(url, keepMini = true)

    override fun onToggleControls() =
        _uiState.update { it.copy(controlsVisible = !it.controlsVisible) }

    override fun onHideControls() =
        _uiState.update { it.copy(controlsVisible = false) }

    override fun onHint(text: String?) {
        _hintState.value = text
    }

    override fun onFullscreenToggle() {
        val next = !_uiState.value.fullscreen
        autoEnteredFullscreen = false
        if (!next) suppressAutoEnter = true
        setFullscreen(next)
    }

    /**
     * Physical device tilt (OrientationEventListener). Landscape + system
     * auto-rotate enters; only an auto-entered session leaves on portrait.
     */
    fun onPhysicalOrientation(degrees: Int, systemAutoRotate: Boolean, inPip: Boolean) {
        val band = AutoFullscreen.band(degrees)
        val rotateChanged = systemAutoRotate != lastSystemAutoRotate
        val pipChanged = inPip != lastInPip
        lastSystemAutoRotate = systemAutoRotate
        lastInPip = inPip
        if (band == lastOrientationBand && !rotateChanged && !pipChanged) return
        lastOrientationBand = band
        applyAutoFullscreen()
    }

    /**
     * Apply enter/exit using the last sensor band. Watch can become visible
     * while already landscape; the listener would otherwise skip a same-band
     * event and leave the player embedded.
     */
    private fun applyAutoFullscreen() {
        val band = lastOrientationBand
        val s = _uiState.value
        val landscape = band == AutoFullscreen.Band.LANDSCAPE
        val portrait = band == AutoFullscreen.Band.PORTRAIT
        if (portrait) suppressAutoEnter = false
        if (AutoFullscreen.shouldEnter(
                watchVisible = s.visible && !s.mini,
                systemAutoRotate = lastSystemAutoRotate,
                fullscreen = s.fullscreen,
                pip = lastInPip,
                mini = s.mini,
                casting = s.casting,
                locked = s.locked,
                physicalLandscape = landscape,
                suppressed = suppressAutoEnter,
            )
        ) {
            autoEnteredFullscreen = true
            setFullscreen(true)
        } else if (AutoFullscreen.shouldExit(
                fullscreen = s.fullscreen,
                autoEntered = autoEnteredFullscreen,
                locked = s.locked,
                physicalPortrait = portrait,
            )
        ) {
            autoEnteredFullscreen = false
            setFullscreen(false)
        }
    }

    override fun onLockToggle() = setLocked(!_uiState.value.locked)

    override fun onResize(mode: ResizeMode) {
        _uiState.update { it.copy(resizeMode = mode) }
        if (prefs.isEnabled(PreferenceKeys.REMEMBER_RESIZE_MODE)) {
            cache.put(KEY_RESIZE, mode.name, PREF_TTL_MS)
        }
    }

    override fun onRetry() = engine.retry()

    override fun onQueueEnabled(enabled: Boolean) = queue.setEnabled(enabled)
    override fun onQueueClear() = queue.clear()
    override fun onQueueRemove(videoId: String) = queue.remove(videoId)
    override fun onQueueMove(fromIndex: Int, toIndex: Int) = queue.move(fromIndex, toIndex)

    /** Page "Add to queue" (media menu) — the manual queue population path. */
    override fun onQueueAdd(item: QueueItem) {
        if (!queue.state.value.enabled) queue.setEnabled(true)
        queue.add(item)
    }

    override fun onCastStop() {
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
        // Only select the route; onSessionStarted performs the single load
        // once the CastSession actually exists (startCasting before it is a
        // silent no-op and races the session-started path).
        cast.selectDevice(id)
    }

    /** Cast dialog open/close toggles the MediaRouter discovery window. */
    override fun onCastDiscovery(enabled: Boolean) = cast.setDiscoveryEnabled(enabled)

    override fun onShareCastLink() {
        val token = playbackRequestSeq
        viewModelScope.launch {
            val source = engine.prepareCastSource()
            if (token != playbackRequestSeq) return@launch
            if (source == null || cast.ensureProxy(source) == null) onHint(hintCastFailed)
        }
    }

    override fun onCloseCastLink() = cast.stopLink()

    /** User cancelled the pre-skip countdown card. */
    override fun onSponsorSkipCancel() {
        engine.cancelSponsorSkip()
    }

    /** Manual skip via the chip (suppressed segment). */
    override fun onSponsorChipSkip() {
        engine.skipSponsorChipSegment()
    }

    /** Edge-slider (volume/brightness) gesture feedback from the surface. */
    fun onEdgeSlider(volume: Boolean, percent: Int) {
        _gestureState.value = GestureUi.EdgeSlider(volume, percent)
    }

    /** Gesture overlays clear (finger up / gesture end). */
    override fun onGestureEnd() {
        scrubAnchorMs = null
        _gestureState.value = null
    }

    override fun onMiniClose() {
        val casting = _uiState.value.casting
        if (casting) engine.pauseLocal()
        stopAndHide(pausePlayback = !casting)
        onWatchClosed?.invoke()
    }

    /** Restore expands the mini-player back into the suspended watch tab. */
    override fun onMiniRestore() {
        val url = _uiState.value.url ?: return
        onRestoreWatch?.invoke(url)
    }

    fun currentCastLink(): String? = cast.state.value.linkUrl

    private fun teardownCast() {
        cast.stopCasting()
        cast.endSession()
        onHint(hintCastFailed)
    }

    private fun detachCastAndResumeLocal(positionMs: Long, resume: Boolean) {
        val keepLocal = engine.attachCastPlayer(null)
        if (!keepLocal && resume) {
            if (positionMs > 0L) engine.seekTo(positionMs)
            engine.play()
        }
    }

    /** Re-reads the PiP pref after a settings flip (wired from MainActivity). */
    fun refreshPipAvailability() {
        _uiState.update { it.copy(pipAvailable = pipEnabled()) }
    }

    private fun pipEnabled(): Boolean = appContext?.let(PipSupport::isSupported) == true &&
        prefs.isEnabled(PreferenceKeys.ENABLE_PIP)

    /** Polls device discovery + link liveness (cast dialog open). */
    fun refreshCastState() {
        cast.publishRoutes()
        cast.refreshLinkBanner()
    }

    /**
     * Session established: push the current video onto the receiver.
     * Control delegation only attaches on a
     * successful load — a failed load resumes local playback instead of
     * delegating to a dead receiver.
     */
    private fun onCastSessionStarted() {
        // A fresh session re-arms the session-end echo: the user is casting
        // again, so a later TV-side disconnect must hand playback back.
        userCastStop = false
        val token = playbackRequestSeq
        viewModelScope.launch {
            val source = engine.prepareCastSource()
            if (token != playbackRequestSeq || !cast.state.value.chromecastSession) return@launch
            if (source == null) { teardownCast(); return@launch }
            engine.pause()
            if (cast.startCasting(source, _positionState.longValue)) {
                engine.attachCastPlayer(cast.remotePlayer())
            } else {
                teardownCast()
                engine.play()
            }
        }
    }

    /**
     * Quality/audio change while casting: publish a new proxy generation and
     * reload the receiver at the current position with the receiver-capable
     * tracks from [PlaybackApi.currentCastSource].
     */
    internal fun recastPublishedSource() {
        if (!cast.state.value.chromecastSession) return
        val source = engine.currentCastSource() ?: return
        if (!cast.startCasting(source, engine.snapshot.value.positionMs)) {
            teardownCast()
        }
    }

    private fun onPlaybackEnded() {
        // Auto-advance honors the same priority as manual skip (queue first,
        // then the YouTube playlist), never WebView back. The queue does NOT
        // wrap here: at the tail playback simply ends;
        // only the manual next button wraps.
        val queueContext = queueActive()
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
        if (!queueContext && pageHasPlaylist &&
            _uiState.value.loopMode == LoopMode.QUEUE_NEXT
        ) {
            watchPage?.evaluate("window.__playlistNav && window.__playlistNav(1);") { }
        }
    }

    // Weak on purpose: this VM is a process-scoped singleton and the view
    // belongs to an activity window. Normal attach/detach pairs it, but a
    // missed dispose must not pin the activity in memory. The engine holds
    // the surface until the next attach/detach — stop() does not clear it —
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

    fun setFullscreen(fullscreen: Boolean) =
        _uiState.update { it.copy(fullscreen = fullscreen, controlsVisible = true) }

    fun setLocked(locked: Boolean) =
        _uiState.update { it.copy(locked = locked, controlsVisible = !locked) }

    fun pauseForBackground() = engine.pauseLocal()

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
        userCastStop = true
        if (cast.state.value.chromecastSession) {
            cast.stopCasting()
            cast.endSession()
        } else {
            cast.stopLink()
        }
        stopAndHide()
        engine.stop()
    }

    fun initializeCast(activity: Activity) {
        cast.initialize(activity)
    }

    companion object {
        private const val SEEK_ACCUM_WINDOW_MS = 600L
        /** Rate limit for the synchronous WebView canGoBack read. */
        private const val CAN_GO_BACK_REFRESH_MS = 1_000L
        private const val KEY_RESIZE = "player:resize_mode"
        private const val KEY_SUBTITLE_STYLE = "player:subtitle_style"

        /** Cast handoff: never wait on a stuck load forever. */
        private const val PREPARED_WAIT_TIMEOUT_MS = 30_000L

        /** Previous tap past this position restarts the current video. */
        private const val PREVIOUS_RESTART_MS = 3_000L

        /** Cast handoff readiness: the target video prepared or failed. */
        internal fun loadSettledFor(snapshot: PlaybackSnapshot, videoId: String?): Boolean =
            videoId != null && snapshot.videoId == videoId &&
                (snapshot.prepared || snapshot.error != null)

        /**
         * Pure priority rules behind [queueNavAvailability]: the local queue
         * wins over the YouTube page playlist, and the watch page's back
         * history is a previous-only fallback. Static so the rules stay
         * unit-testable.
         */
        internal fun queueNav(
            queueActive: Boolean,
            queueHasNext: Boolean,
            inQueueAndHasPrevious: Boolean,
            pageHasPlaylist: Boolean,
            pageCanGoBack: Boolean,
        ): QueueNav = when {
            queueActive -> QueueNav(next = queueHasNext, previous = inQueueAndHasPrevious || pageCanGoBack)
            else -> QueueNav(next = pageHasPlaylist, previous = pageCanGoBack)
        }

        /**
         * Page callbacks apply when they come from the host, from the playing
         * tab's current-or-newer document, or when no page owns playback yet.
         * Another tab, or an older document on the same tab, is ignored.
         */
        internal fun acceptsPageCallback(owner: PageOrigin?, incoming: PageOrigin): Boolean {
            if (incoming.isHost) return true
            if (owner == null || owner.isHost) return true
            return incoming.tabId == owner.tabId &&
                incoming.documentGeneration >= owner.documentGeneration
        }

        /** Only a watch page can expand the native mini-player. */
        internal fun shouldExpandMiniOnReturn(pageUrl: String?): Boolean =
            !PageKind.isShorts(pageUrl) && VideoId.parse(pageUrl) != null

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
