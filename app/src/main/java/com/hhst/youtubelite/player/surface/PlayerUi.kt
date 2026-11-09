package com.hhst.youtubelite.player.surface

import androidx.compose.ui.graphics.Color
import com.hhst.youtubelite.extractor.Chapter
import java.util.Locale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.engine.LoopMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure player-UI helpers (layout tokens, labels, back-step).
 * Keep Android View / Compose state out of here so unit tests stay JVM-cheap.
 */
object PlayerUi {

    enum class PlayPauseAction(val icon: Int, val label: Int) {
        Play(R.drawable.ic_play, R.string.action_play),
        Pause(R.drawable.ic_pause, R.string.action_pause),
        Replay(R.drawable.ic_replay, R.string.action_replay),
        Loading(R.drawable.ic_loading, R.string.a11y_loading);

        /** Filled transport glyphs for the player controls; menus keep the outline resources. */
        val playerIcon: Int get() = when (this) {
            Play -> R.drawable.ic_player_play
            Pause -> R.drawable.ic_player_pause
            Replay, Loading -> icon
        }
    }

    /** Shared by the full player, mini-player and system PiP actions. */
    fun playPauseAction(state: PlayerUiState): PlayPauseAction = when {
        state.ended -> PlayPauseAction.Replay
        state.error == null && state.notice == null && (!state.prepared || state.loading ||
            (state.userWantsPlay && !state.firstFrameRendered && !state.hasRenderedVideoFrame && !state.casting &&
                (state.videoWidth > 0 || state.videoItag != null || state.qualities.isNotEmpty()))) -> PlayPauseAction.Loading
        state.isPlaying -> PlayPauseAction.Pause
        else -> PlayPauseAction.Play
    }

    val YtRed = Color(0xFFFF0000)
    val Icon = Color.White.copy(alpha = 0.92f)
    val Unplayed = Color(0x99FFFFFF)
    val Buffered = Color.White
    val HintBg = Color(0xCC000000)
    private val Author = Color.White.copy(alpha = 0.7f)
    /** Top/bottom chrome scrim. */
    val Scrim = Color(0x80000000)
    /**
     * Gesture-overlay glass: light translucency instead of a heavy black slab —
     * the video stays visible through the feedback, and the hairline keeps the
     * pill legible on bright frames.
     */
    val GlassBg = Color(0x66000000)
    val GlassStroke = Color(0x24FFFFFF)

    /** Time bar: 2 dp track, 48 dp minimum accessibility hit area, 100 dp edge gradients. */
    const val TIME_BAR_HIT_DP = 48
    const val TIME_BAR_THICKNESS_DP = 2
    const val TIME_BAR_OVERLAP_DP = 2
    const val GRADIENT_DP = 100
    const val TOP_ACTION_DP = 48
    const val CHROME_ICON_DP = 24
    const val CHROME_TEXT_SP = 13
    const val TITLE_PADDING_DP = 6
    const val CENTER_PLAY_DP = 80
    const val CENTER_SKIP_DP = 56
    const val BOTTOM_ROW_DP = 48
    const val HINT_TEXT_SP = 12
    /** Fallback embedded top: page-header height in page space (the window offset adds the system-bar inset). */
    private const val EMBEDDED_TOP_MARGIN_DP = 48
    /** Fullscreen chrome / lock: keep clear of rounded corners and cutouts. */
    private const val FULLSCREEN_SIDE_DP = 16
    /** Subtitle block bottom clearance: extra when the chrome is visible. */
    const val SUBTITLE_CHROME_CLEAR_DP = 52
    const val SUBTITLE_CLEAR_DP = 12
    /** Top hint inset; grows to clear the cast / link banner when shown. */
    const val HINT_TOP_PAD_DP = 4
    const val CAST_BANNER_CLEAR_DP = 44
    /** Bottom-end cards (sponsor skip, zoom reset) above the bottom HUD. */
    const val ZOOM_RESET_BOTTOM_DP = 80
    const val TRACK_CHIP_GAP_DP = 8

    val SpeedChoices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

    /** Player metadata stays readable above the video without extra font padding. */
    val TitleStyle = TextStyle(
        color = Color.White,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    val AuthorStyle = TextStyle(
        color = Author,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    enum class BackStep {
        Unlock,
        ExitFullscreen,
        BrowserBack,
    }

    /** Reserve metadata space instead of packing smaller, overlapping action buttons. */
    fun compactChrome(widthDp: Float, fontScale: Float, hasCast: Boolean): Boolean =
        widthDp < TOP_ACTION_DP * (if (hasCast) 7 else 6) + 24 + 128 * fontScale

    data class ChromeSizing(
        val iconDp: Int,
        val textSp: Int,
        val playDp: Int = 56,
        val skipDp: Int = 48,
        val centerGapDp: Int = 12,
    )

    val CompactChromeSizing = ChromeSizing(CHROME_ICON_DP, CHROME_TEXT_SP)
    /** Scale the visuals to the player slot, including split windows and embedded tablet players.
     * Touch targets remain at least 48 dp and text still follows the system font scale. */
    fun chromeSizing(widthDp: Float, heightDp: Float): ChromeSizing {
        val room = minOf((widthDp - 320f) / 520f, (heightDp - 180f) / 300f).coerceIn(0f, 1f)
        fun between(small: Int, large: Int) = (small + (large - small) * room).roundToInt()
        return ChromeSizing(between(24, 32), between(13, 16), between(56, CENTER_PLAY_DP),
            between(48, CENTER_SKIP_DP), (widthDp * .08f).roundToInt().coerceIn(12, 84))
    }

    /**
     * Same order for the overlay back button and system Back. The mini-player
     * is omitted: Back navigates the browser underneath it, and playback continues.
     */
    fun nextBackStep(locked: Boolean, fullscreen: Boolean): BackStep = when {
        locked -> BackStep.Unlock
        fullscreen -> BackStep.ExitFullscreen
        else -> BackStep.BrowserBack
    }

    fun playerTopOffsetDp(
        fullscreen: Boolean,
        reportedTopDp: Int? = null,
        insetTopDp: Int = 0,
    ): Int {
        if (fullscreen) return 0
        return (insetTopDp + (reportedTopDp ?: EMBEDDED_TOP_MARGIN_DP)).coerceAtLeast(0)
    }

    /** Inset for the seek / volume / brightness hints. */
    fun gestureSideInsetDp(widthDp: Int, insetDp: Int = 0): Int =
        maxOf((widthDp.coerceAtLeast(0) * 12 / 100).coerceAtLeast(24), insetDp.coerceAtLeast(0))

    /**
     * Extra start/end padding for a HUD already inside [chromePadDp] (fullscreen
     * chrome inset), so the widget still sits at [gestureSideInsetDp].
     */
    fun hudSidePadDp(widthDp: Int, chromePadDp: Int = 0, insetDp: Int = 0): Int =
        (gestureSideInsetDp(widthDp, insetDp) - chromePadDp.coerceAtLeast(0)).coerceAtLeast(8)

    /** [pageHeightDp] comes from the page's JS; absent it, fall back to 16:9. */
    fun embeddedHeightDp(pageHeightDp: Int?, widthDp: Int): Int {
        val reported = pageHeightDp ?: 0
        if (reported > 0) return reported
        return (widthDp * 9 / 16).coerceAtLeast(1)
    }

    data class EmbeddedBounds(val left: Int, val top: Int?, val width: Int, val height: Int)

    /** Convert the page viewport (including zoom/desktop mode) into the WebView's actual width. */
    fun embeddedBounds(state: PlayerUiState, viewportWidthDp: Int): EmbeddedBounds {
        val available = viewportWidthDp.coerceAtLeast(1)
        val scale = state.pageViewportWidthDp?.takeIf { it > 0 }?.let { available.toDouble() / it } ?: 1.0
        fun scaled(value: Int) = (value * scale).toInt()
        val width = state.pageWidthDp?.takeIf { it > 0 }?.let { scaled(it).coerceIn(1, available) } ?: available
        return EmbeddedBounds(scaled(state.pageLeftDp), state.pageTopDp?.let(::scaled), width,
            state.pageHeightDp?.takeIf { it > 0 }?.let { scaled(it).coerceAtLeast(1) } ?: embeddedHeightDp(null, width))
    }

    /** Leave room for the watch actions and a useful portion of the content. */
    private const val MIN_WATCH_CONTENT_HEIGHT_DP = 192

    fun useLandscapeMiniPlayer(
        viewportWidthDp: Int,
        viewportHeightDp: Int,
        pageHeightDp: Int?,
    ): Boolean {
        if (viewportHeightDp <= 0 || viewportWidthDp <= viewportHeightDp) return false
        // Use the uncollapsed slot and masthead, independent of page scrolling.
        // Measuring the collapsed slot would immediately expand it again.
        val remaining = viewportHeightDp - EMBEDDED_TOP_MARGIN_DP -
            embeddedHeightDp(pageHeightDp, viewportWidthDp)
        return remaining < MIN_WATCH_CONTENT_HEIGHT_DP
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Double-tap / relative-seek hint: `+10s` / `-10s` / `+0s`. */
    fun offsetHint(offsetMs: Long): String {
        // Round half-up on magnitude so a -900 ms drag is "-1s", not "+0s"
        // (integer division truncates toward zero and loses the sign).
        val sec = if (offsetMs >= 0) (offsetMs + 500) / 1000 else -((-offsetMs + 500) / 1000)
        return if (sec >= 0) "+${sec}s" else "${sec}s"
    }

    /** Speed label keeps one decimal for whole speeds (`1.0x`). */
    fun speedLabel(speed: Float): String {
        val whole = speed.toInt()
        return if (speed == whole.toFloat()) "$whole.0x" else "${trimFloat(speed)}x"
    }

    /**
     * Quality actually on screen. A pinned choice stays as labeled. In auto, a
     * decoded frame whose height disagrees with the playlist track wins, so a
     * master-playlist RESOLUTION cannot keep advertising another quality.
     */
    fun playingQuality(active: String?, videoHeight: Int): String? {
        val declared = active?.takeIf { it.isNotBlank() }
        val decoded = videoHeight.takeIf { it > 0 }?.let { "${it}p" }
        if (declared == null) return decoded
        if (decoded == null) return declared
        val declaredHeight = declared.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        return if (declaredHeight > 0 && declaredHeight != videoHeight) decoded else declared
    }

    fun qualityButtonLabel(
        pinned: String?,
        active: String?,
        autoPrefix: String,
        videoHeight: Int,
    ): String {
        if (pinned != null) return pinned
        val current = playingQuality(active, videoHeight)
        return if (current != null) "$autoPrefix $current" else autoPrefix
    }

    fun languageLabel(code: String): String {
        if (code.isBlank()) return code
        val name = Locale.forLanguageTag(code.replace('_', '-'))
            .getDisplayName(Locale.getDefault())
            .trim()
        return name.ifBlank { code }
    }

    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int {
        var index = -1
        chapters.forEachIndexed { i, chapter ->
            if (positionMs >= chapter.startSeconds * 1000L) index = i
        }
        return index
    }

    fun bufferedFraction(bufferedPositionMs: Long, durationMs: Long): Float =
        progressFraction(bufferedPositionMs, durationMs)

    /** Inclusive start, exclusive visual end on the 0..1 bar. Null if not drawable. */
    fun segmentRange(startMs: Long, endMs: Long, durationMs: Long, point: Boolean = false): Pair<Float, Float>? {
        if (durationMs <= 0L) return null
        if (point && startMs == endMs && startMs in 0L..durationMs) {
            val position = startMs.toFloat() / durationMs
            return position to position
        }
        val start = (startMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val end = (endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        if (end <= start) return null
        return start to end
    }

    fun segmentColor(category: String): Color = when (category) {
        "sponsor" -> Color(0xFF00D400)
        "selfpromo" -> Color(0xFFFFFF00)
        "interaction" -> Color(0xFFCC00FF)
        "intro" -> Color(0xFF00FFFF)
        "outro" -> Color(0xFF0202ED)
        "preview" -> Color(0xFF008FD6)
        "music_offtopic" -> Color(0xFFFF9900)
        "filler" -> Color(0xFF7300FF)
        "poi_highlight" -> Color(0xFFFF1684)
        else -> Color(0xFFAAAAAA)
    }

    fun loopIcon(mode: LoopMode): Int = when (mode) {
        LoopMode.QUEUE_NEXT -> R.drawable.ic_playback_end_next
        LoopMode.LOOP_ONE -> R.drawable.ic_playback_end_loop
        LoopMode.PAUSE_AT_END -> R.drawable.ic_playback_end_pause
        LoopMode.QUEUE_RANDOM -> R.drawable.ic_playback_end_shuffle
    }

    fun loopLabelRes(mode: LoopMode): Int = when (mode) {
        LoopMode.QUEUE_NEXT -> R.string.playback_end_next
        LoopMode.LOOP_ONE -> R.string.playback_end_loop
        LoopMode.PAUSE_AT_END -> R.string.playback_end_pause
        LoopMode.QUEUE_RANDOM -> R.string.playback_end_shuffle
    }

    fun segmentLabelRes(category: String): Int = when (category) {
        "sponsor" -> R.string.skip_sponsors
        "selfpromo" -> R.string.skip_sponsors_selfpromo
        "poi_highlight" -> R.string.skip_sponsors_highlight
        "intro" -> R.string.segment_intro
        "outro" -> R.string.segment_outro
        "interaction" -> R.string.segment_interaction
        "preview" -> R.string.segment_preview
        "music_offtopic" -> R.string.segment_music_offtopic
        "filler" -> R.string.skip_sponsors_filler
        else -> R.string.segments
    }

    /** Lock control lives on the fullscreen surface, never inside More. */
    fun lockVisible(fullscreen: Boolean, mini: Boolean, locked: Boolean, controlsVisible: Boolean): Boolean =
        fullscreen && !mini && (locked || controlsVisible)

    fun lockIconRes(locked: Boolean): Int =
        if (locked) R.drawable.ic_lock else R.drawable.ic_unlock

    fun lockContentDescriptionRes(locked: Boolean): Int =
        if (locked) R.string.unlock_screen else R.string.lock_screen

    /** Grows beyond [FULLSCREEN_SIDE_DP] to cover display cutouts / gesture insets. */
    fun fullscreenSideDp(insetDp: Int): Int = maxOf(FULLSCREEN_SIDE_DP, insetDp.coerceAtLeast(0))

    fun sheetMaxHeightDp(screenHeightDp: Int): Int =
        (screenHeightDp.coerceAtLeast(0) * 3 / 4).coerceIn(200, 520)

    /** Clamped to Android's 1:2.39 … 2.39:1 PiP range; unknown size falls back to 16:9. */
    fun pipAspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val w = width.toLong()
        val h = height.toLong()
        // Android requires 1:2.39 … 2.39:1. Use 1000:2390 so truncation of
        // (ratio * 1000) cannot fall below the platform minimum.
        val minN = 1000L
        val minD = 2390L
        val maxN = 2390L
        val maxD = 1000L
        return when {
            w * minD < minN * h -> minN.toInt() to minD.toInt()
            w * maxD > maxN * h -> maxN.toInt() to maxD.toInt()
            else -> {
                val g = gcd(width, height)
                (width / g) to (height / g)
            }
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x.coerceAtLeast(1)
    }

    private fun trimFloat(value: Float): String {
        val text = value.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
}

/** Mirrors [androidx.media3.ui.AspectRatioFrameLayout] resize modes. */
enum class ResizeMode {
    Fit,
    Fill,
    Zoom,
    FixedWidth,
    FixedHeight,
    ;

    fun exoResizeMode(): Int = when (this) {
        Fit -> 0
        FixedWidth -> 1
        FixedHeight -> 2
        Fill -> 3
        Zoom -> 4
    }

    fun labelRes(): Int = when (this) {
        Fit -> R.string.resize_fit
        Fill -> R.string.resize_fill
        Zoom -> R.string.resize_zoom
        FixedWidth -> R.string.resize_fixed_width
        FixedHeight -> R.string.resize_fixed_height
    }

    fun iconRes(): Int = when (this) {
        Fit -> R.drawable.ic_resize_fit
        Fill -> R.drawable.ic_resize_fill
        Zoom -> R.drawable.ic_resize_zoom
        FixedWidth -> R.drawable.ic_resize_width
        FixedHeight -> R.drawable.ic_resize_height
    }
}
