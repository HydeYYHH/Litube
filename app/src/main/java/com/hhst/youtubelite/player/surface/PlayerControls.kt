package com.hhst.youtubelite.player.surface

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.hypot
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.engine.LoopMode

private val ScrimTop = Brush.verticalGradient(
    listOf(PlayerUi.Scrim, Color.Transparent),
)
private val ScrimBottom = Brush.verticalGradient(
    listOf(Color.Transparent, PlayerUi.Scrim),
)

/** Chrome button. Default hit target is 32 dp; pass [modifier] size to override. */
@Composable
fun PlayerIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PlayerUi.Icon,
    enabled: Boolean = true,
    iconSize: Int = 24,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(PlayerUi.TOP_ACTION_DP.dp)
            .clip(CircleShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = ripple(bounded = false, radius = 18.dp),
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = ripple(bounded = false, radius = 18.dp),
                        onClick = onClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 0.92f else 0.35f),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** Top: back + title/author + queue / segments / CC / loop / cast / more. */
@Composable
fun TopBar(
    state: PlayerUiState,
    positionState: State<Long>,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onSegments: () -> Unit,
    onLoop: () -> Unit,
    onMore: () -> Unit,
    onSubtitle: () -> Unit,
    modifier: Modifier = Modifier,
    menu: PlayerAnchorMenu? = null,
    onDismissMenu: () -> Unit = {},
    callbacks: PlayerSurfaceCallbacks? = null,
    onCast: () -> Unit = {},
    onSubtitleStyle: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = R.drawable.ic_arrow_back,
            contentDescription = stringResource(R.string.navigate_back),
            onClick = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PlayerUi.TITLE_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = state.title,
                style = PlayerUi.TitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            state.author?.let { author ->
                Text(
                    text = author,
                    style = PlayerUi.AuthorStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-PlayerUi.TOP_ACTION_OVERLAP_DP).dp),
        ) {
            PlayerIconButton(
                icon = R.drawable.ic_queue,
                contentDescription = stringResource(R.string.queue),
                onClick = onQueue,
            )
            Box {
                PlayerIconButton(
                    icon = R.drawable.ic_segment,
                    contentDescription = stringResource(R.string.segments),
                    onClick = onSegments,
                )
                if (menu == PlayerAnchorMenu.Segments && callbacks != null) {
                    PlayerAnchorDropdown(menu, state, positionState, callbacks, onDismissMenu)
                }
            }
            Box {
                PlayerIconButton(
                    icon = if (state.subtitleEnabled) {
                        R.drawable.ic_subtitles_on
                    } else {
                        R.drawable.ic_subtitles_off
                    },
                    contentDescription = stringResource(R.string.subtitles),
                    onClick = onSubtitle,
                    onLongClick = onSubtitleStyle,
                    tint = if (state.subtitleEnabled) PlayerUi.YtRed else PlayerUi.Icon,
                )
                if (menu == PlayerAnchorMenu.Subtitles && callbacks != null) {
                    PlayerAnchorDropdown(
                        menu,
                        state,
                        positionState,
                        callbacks,
                        onDismissMenu,
                        onSubtitleStyle = {
                            onDismissMenu()
                            onSubtitleStyle()
                        },
                    )
                }
            }
            PlayerIconButton(
                icon = PlayerUi.loopIcon(state.loopMode),
                contentDescription = loopDescription(state.loopMode),
                onClick = onLoop,
                tint = if (state.loopMode == LoopMode.QUEUE_NEXT) PlayerUi.Icon else PlayerUi.YtRed,
            )
            // Cast shortcut: only once a route exists or a session is live —
            // a permanent cast button is noise for device-less users.
            if (state.casting || state.castDevices.isNotEmpty()) {
                PlayerIconButton(
                    icon = if (state.casting) R.drawable.ic_cast_connected else R.drawable.ic_cast,
                    contentDescription = stringResource(R.string.cast),
                    onClick = onCast,
                    tint = if (state.casting) PlayerUi.YtRed else PlayerUi.Icon,
                )
            }
            PlayerIconButton(
                icon = R.drawable.ic_more,
                contentDescription = stringResource(R.string.more_options),
                onClick = onMore,
            )
        }
    }
}

@Composable
private fun loopDescription(mode: LoopMode): String =
    stringResource(PlayerUi.loopLabelRes(mode))

/** Center prev / play-pause / next. Hidden with the rest of the chrome when locked or auto-hidden; the play slot becomes a spinner while loading. */
@Composable
fun CenterControls(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PlayerIconButton(
            icon = R.drawable.ic_previous,
            contentDescription = stringResource(R.string.action_previous),
            onClick = onPrevious,
            enabled = state.hasPrevious,
            modifier = Modifier.size(PlayerUi.CENTER_SKIP_DP.dp),
            iconSize = 32,
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .size(PlayerUi.CENTER_PLAY_DP.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 40.dp),
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                val replay = state.ended && !state.isPlaying
                Icon(
                    painter = painterResource(
                        when {
                            state.isPlaying -> R.drawable.ic_pause
                            replay -> R.drawable.ic_replay
                            else -> R.drawable.ic_play
                        },
                    ),
                    contentDescription = stringResource(
                        when {
                            state.isPlaying -> R.string.action_pause
                            replay -> R.string.action_replay
                            else -> R.string.action_play
                        },
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        PlayerIconButton(
            icon = R.drawable.ic_next,
            contentDescription = stringResource(R.string.action_next),
            onClick = onNext,
            enabled = state.hasNext,
            modifier = Modifier.size(PlayerUi.CENTER_SKIP_DP.dp),
            iconSize = 32,
        )
    }
}

/**
 * Bottom: 2 dp time bar (drawn just above the controls row), then
 * position / duration | speed | quality | fullscreen.
 *
 * Position/buffered arrive as [State]s and are consumed only by the time bar
 * and [PositionText], so 4 Hz playback ticks never recompose this bar.
 */
@Composable
fun BottomBar(
    state: PlayerUiState,
    positionState: State<Long>,
    bufferedPositionState: State<Long>,
    onSeek: (Long) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onSpeed: () -> Unit = {},
    onQuality: () -> Unit = {},
    menu: PlayerAnchorMenu? = null,
    onDismissMenu: () -> Unit = {},
    callbacks: PlayerSurfaceCallbacks? = null,
) {
    val autoLabel = stringResource(R.string.player_quality_auto)
    Column(modifier = modifier.fillMaxWidth()) {
        PlayerTimeBar(
            positionState = positionState,
            bufferedPositionState = bufferedPositionState,
            durationMs = state.durationMs,
            segments = state.sponsorSegments,
            onSeek = onSeek,
            onPreview = { target -> callbacks?.onTimeBarPreview(target) },
            onCommit = { callbacks?.onTimeBarCommit() },
            modifier = Modifier.offset(y = PlayerUi.TIME_BAR_OVERLAP_DP.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerUi.BOTTOM_ROW_DP.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PositionText(
                positionState = positionState,
                durationMs = state.durationMs,
                isLive = state.isLive,
            )
            Spacer(Modifier.weight(1f))
            Box {
                ChromeTextButton(label = PlayerUi.speedLabel(state.speed), onClick = onSpeed)
                if (menu == PlayerAnchorMenu.Speed && callbacks != null) {
                    PlayerAnchorDropdown(menu, state, positionState, callbacks, onDismissMenu)
                }
            }
            Box {
                ChromeTextButton(
                    label = PlayerUi.qualityButtonLabel(
                        pinned = state.qualityLabel,
                        active = state.activeQuality,
                        autoPrefix = autoLabel,
                        videoHeight = state.videoHeight,
                    ),
                    onClick = onQuality,
                )
                if (menu == PlayerAnchorMenu.Quality && callbacks != null) {
                    PlayerAnchorDropdown(menu, state, positionState, callbacks, onDismissMenu)
                }
            }
            PlayerIconButton(
                icon = if (state.fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                contentDescription = stringResource(R.string.action_fullscreen),
                onClick = onFullscreen,
            )
        }
    }
}

/** Position / duration text, isolated so position ticks stay inside this leaf. */
@Composable
private fun PositionText(
    positionState: State<Long>,
    durationMs: Long,
    isLive: Boolean,
) {
    val positionMs = positionState.value
    Text(
        text = PlayerUi.formatTime(positionMs),
        color = Color.White,
        fontSize = 12.sp,
    )
    Text(" / ", color = Color.White, fontSize = 12.sp)
    if (isLive) {
        Text(
            text = stringResource(R.string.player_live),
            color = PlayerUi.YtRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    } else {
        Text(
            text = PlayerUi.formatTime(durationMs),
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

/** 100 dp edge fades used behind top/bottom chrome. */
@Composable
fun ControlScrims(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PlayerUi.GRADIENT_DP.dp)
                .align(Alignment.TopCenter)
                .background(ScrimTop),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(PlayerUi.GRADIENT_DP.dp)
                .align(Alignment.BottomCenter)
                .background(ScrimBottom),
        )
    }
}

/** Compact speed / quality label that opens the anchor dropdown menu. */
@Composable
fun ChromeTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .widthIn(min = 46.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PlayerUi.Icon,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** One-shot hint: top-center, 12 sp. The last non-null text is kept so the
 *  exit fade does not collapse the pill before it animates out. Reads
 *  [hintState] here (not in the caller) so hint writes recompose only this
 *  overlay. */
@Composable
fun HintOverlay(hintState: State<String?>, enabled: Boolean, modifier: Modifier = Modifier) {
    val hint = if (enabled) hintState.value else null
    var lastText by remember { mutableStateOf("") }
    // SideEffect (not a composition-time write): keeps the last non-null text
    // so the exit fade does not collapse the pill, without scheduling an
    // extra recomposition from inside composition.
    SideEffect { if (hint != null) lastText = hint }
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Text(
            text = lastText,
            color = Color.White,
            fontSize = PlayerUi.HINT_TEXT_SP.sp,
            modifier = Modifier
                .background(PlayerUi.HintBg, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Chromecast + link-cast banners. */
@Composable
fun CastStatusBanners(
    casting: Boolean,
    deviceName: String?,
    linkUrl: String?,
    linkConnected: Boolean,
    onCast: () -> Unit,
    onCloseLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (casting) {
            Row(
                modifier = Modifier
                    .background(PlayerUi.HintBg, RoundedCornerShape(12.dp))
                    .clickable(onClick = onCast)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_cast),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp),
                )
                Text(
                    text = stringResource(R.string.casting_to, deviceName ?: ""),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
        // The link banner appears only while a receiver/browser is actually
        // connected (30 s liveness window in the proxy) — not merely because
        // the cast dialog once opened. A Chromecast session is a separate
        // mode: its proxy serves the receiver, so no link banner then.
        if (linkUrl != null && linkConnected && !casting) {
            Row(
                modifier = Modifier
                    .background(PlayerUi.HintBg, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_cast_link),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp),
                )
                Text(
                    text = stringResource(R.string.cast_link_banner),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                PlayerIconButton(
                    icon = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.cast_link_close),
                    onClick = onCloseLink,
                    modifier = Modifier.size(24.dp),
                    iconSize = 16,
                )
            }
        }
    }
}

/** Playback failure: keep chrome usable, offer a one-tap retry. */
@Composable
fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.player_error),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.retry),
                color = PlayerUi.YtRed,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Fullscreen-only lock control; never inside More. */
@Composable
fun LockAffordance(
    locked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerIconButton(
        icon = PlayerUi.lockIconRes(locked),
        contentDescription = stringResource(PlayerUi.lockContentDescriptionRes(locked)),
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * Compact in-app mini-player chrome.
 *
 * Background tap toggles chrome; the center restore button returns to watch.
 * Drag / pinch are handled on the background layer so control buttons stay tappable.
 */
@Composable
fun MiniPlayerChrome(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val handle = LocalMiniPlayerHandle.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(if (handle != null) Modifier.miniPlayerInteract(handle) else Modifier),
    ) {
        val gap = MiniPlayerLayout.controlGap(maxWidth.value.toInt(), screenWidthDp)
        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(MiniPlayerLayout.SCRIM_COLOR)),
                )
                PlayerIconButton(
                    icon = R.drawable.ic_queue,
                    contentDescription = stringResource(R.string.queue),
                    onClick = onQueue,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(48.dp),
                    iconSize = 24,
                )
                PlayerIconButton(
                    icon = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onClose,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp),
                    iconSize = 24,
                )
                PlayerIconButton(
                    icon = R.drawable.ic_fullscreen,
                    contentDescription = stringResource(R.string.action_restore_watch),
                    onClick = onRestore,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                    iconSize = 20,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-4).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconButton(
                        icon = R.drawable.ic_previous,
                        contentDescription = stringResource(R.string.action_previous),
                        onClick = onPrevious,
                        enabled = state.hasPrevious,
                        modifier = Modifier.size(MiniPlayerLayout.CONTROL_SIDE_DP.dp),
                        iconSize = 20,
                    )
                    Spacer(Modifier.width(gap.dp))
                    PlayerIconButton(
                        icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.action_pause else R.string.action_play,
                        ),
                        onClick = onPlayPause,
                        modifier = Modifier.size(MiniPlayerLayout.CONTROL_PLAY_DP.dp),
                        iconSize = 24,
                    )
                    Spacer(Modifier.width(gap.dp))
                    PlayerIconButton(
                        icon = R.drawable.ic_next,
                        contentDescription = stringResource(R.string.action_next),
                        onClick = onNext,
                        enabled = state.hasNext,
                        modifier = Modifier.size(MiniPlayerLayout.CONTROL_SIDE_DP.dp),
                        iconSize = 20,
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.miniPlayerInteract(handle: MiniPlayerHandle): Modifier {
    // The chrome moves with the window, so its local coordinates are a moving
    // frame: deltas measured in it shrink to half the finger distance while
    // dragging. Anchor drags and pinches in the window frame instead.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    fun rootPos(change: PointerInputChange): Offset {
        val windowOrigin = coords?.localToWindow(Offset.Zero) ?: Offset.Zero
        return change.position + windowOrigin
    }

    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(handle) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                handle.begin()
                val slop = viewConfiguration.touchSlop
                var dragging = false
                var pinching = false
                var pinchStartDist = 0f
                // True when the final up stayed unconsumed (no mini control
                // handled the tap) — only then it counts as a background tap.
                var backgroundTap = false
                val start = rootPos(down)
                // Swipe-down dismiss tracking: pointer samples smoothed into a
                // vertical fling velocity so one jitter cannot fake a fling.
                var lastX = start.x
                var lastY = start.y
                var lastT = 0L
                var velocityY = 0f
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    backgroundTap = event.changes.any { !it.isConsumed }
                    if (pressed.size >= 2) {
                        val p0 = rootPos(pressed[0])
                        val p1 = rootPos(pressed[1])
                        val dist = hypot(
                            (p0.x - p1.x).toDouble(),
                            (p0.y - p1.y).toDouble(),
                        ).toFloat()
                        if (pinchStartDist <= 0f) pinchStartDist = dist
                        if (dist > 0f && pinchStartDist > 0f) {
                            pinching = true
                            dragging = false
                            pressed.forEach { it.consume() }
                            handle.pinchScale(dist / pinchStartDist)
                        }
                    } else if (pressed.isNotEmpty() && !pinching) {
                        val current = rootPos(pressed[0])
                        val delta = current - start
                        val now = SystemClock.uptimeMillis()
                        if (lastT > 0L) {
                            val dt = (now - lastT).coerceAtLeast(1L)
                            val instant = (current.y - lastY) / dt * 1000f
                            velocityY =
                                if (velocityY == 0f) instant else velocityY * 0.5f + instant * 0.5f
                        }
                        lastX = current.x
                        lastY = current.y
                        lastT = now
                        if (!dragging && (abs(delta.x) > slop || abs(delta.y) > slop)) {
                            dragging = true
                        }
                        if (dragging) {
                            pressed[0].consume()
                            handle.dragFromStart(delta.x, delta.y)
                        }
                    }
                    event.changes.forEach { change ->
                        if ((dragging || pinching) && change.positionChanged()) change.consume()
                    }
                } while (event.changes.any { it.pressed })
                when {
                    dragging || pinching -> {
                        val dy = lastY - start.y
                        val dx = lastX - start.x
                        if (!pinching &&
                            MiniPlayerLayout.shouldDismiss(dy, dx, velocityY, density.density)
                        ) {
                            handle.dismiss()
                        } else {
                            handle.end(true)
                        }
                    }
                    backgroundTap -> {
                        handle.end(false)
                        handle.tap()
                    }
                    else -> handle.end(false)
                }
            }
        }
}
