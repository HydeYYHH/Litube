package com.hhst.youtubelite.player.surface

import androidx.compose.ui.platform.testTag

import androidx.compose.animation.AnimatedVisibility
import com.hhst.youtubelite.ui.design.AppMotion
import com.hhst.youtubelite.ui.design.LocalMotionScale
import com.hhst.youtubelite.ui.design.motionDuration
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.hhst.youtubelite.ui.components.AppTextButton as TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extractor.Chapter
import com.hhst.youtubelite.player.PlayerUiState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

private val ScrimTop = Brush.verticalGradient(
    listOf(PlayerUi.Scrim, Color.Transparent),
)
private val ScrimBottom = Brush.verticalGradient(
    listOf(Color.Transparent, PlayerUi.Scrim),
)

@Composable
fun PlayerIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PlayerUi.Icon,
    enabled: Boolean = true,
    iconSize: Int = PlayerUi.CHROME_ICON_DP,
    onLongClick: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
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
                        indication = ripple(bounded = false, radius = 24.dp),
                        onClick = { haptic(); onClick() },
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = ripple(bounded = false, radius = 24.dp),
                        onClick = { haptic(); onClick() },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            PlaybackLoadingIndicator(Modifier.size(iconSize.dp), tint)
        } else Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 0.92f else 0.35f),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

@Composable
internal fun PlaybackLoadingIndicator(modifier: Modifier = Modifier, color: Color = Color.White) {
    val description = stringResource(R.string.a11y_loading)
    CircularProgressIndicator(
        color = color,
        strokeWidth = 3.dp,
        modifier = modifier.testTag("player-buffering").semantics { contentDescription = description },
    )
}

@Composable
fun TopBar(
    state: PlayerUiState,
    positionState: State<Long>,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onSubtitle: () -> Unit,
    modifier: Modifier = Modifier,
    menu: PlayerAnchorMenu? = null,
    onDismissMenu: () -> Unit = {},
    callbacks: PlayerSurfaceCallbacks? = null,
    onSubtitleStyle: () -> Unit = {},
    showAuthor: Boolean = true,
    sizing: PlayerUi.ChromeSizing = PlayerUi.CompactChromeSizing,
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag("player-topbar")
            .padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = R.drawable.ic_arrow_back,
            contentDescription = stringResource(R.string.navigate_back),
            onClick = onBack,
            iconSize = sizing.iconDp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PlayerUi.TITLE_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = state.title,
                style = PlayerUi.TitleStyle.copy(fontSize = (sizing.textSp + 2).sp,
                    lineHeight = (sizing.textSp + 8).sp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            state.author?.takeIf { showAuthor }?.let { author ->
                Text(
                    text = author,
                    style = PlayerUi.AuthorStyle.copy(fontSize = (sizing.textSp - 2).sp,
                        lineHeight = (sizing.textSp + 4).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Box {
                PlayerIconButton(
                    icon = if (state.subtitleEnabled) {
                        R.drawable.ic_subtitles_on
                    } else {
                        R.drawable.ic_subtitles_off
                    },
                    contentDescription = stringResource(R.string.subtitles),
                    onClick = onSubtitle,
                    modifier = Modifier.width(40.dp),
                    iconSize = sizing.iconDp,
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
            Box {
                PlayerIconButton(
                    icon = R.drawable.ic_settings,
                    contentDescription = stringResource(R.string.more_options),
                    onClick = onMore,
                    modifier = Modifier.width(40.dp),
                    iconSize = (sizing.iconDp * 5 / 6).coerceAtLeast(20),
                )
            }
        }
    }
}

@Composable
fun CenterControls(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    playSizeDp: Int = PlayerUi.CENTER_PLAY_DP,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    iconSize: Int = PlayerUi.CHROME_ICON_DP,
    skipSizeDp: Int = 48,
    gapDp: Int = 12,
) {
    if (state.notice != null) return
    val action = PlayerUi.playPauseAction(state)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PlayerIconButton(
            icon = R.drawable.ic_player_previous,
            contentDescription = stringResource(R.string.action_previous),
            onClick = onPrevious,
            enabled = state.hasPrevious,
            modifier = Modifier.size(skipSizeDp.dp),
            iconSize = iconSize,
        )
        Box(
            modifier = Modifier
                .padding(horizontal = gapDp.dp)
                .size(playSizeDp.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = (playSizeDp / 2).dp),
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (action == PlayerUi.PlayPauseAction.Loading) {
                PlaybackLoadingIndicator(Modifier.size(minOf(36, playSizeDp - 16).dp))
            } else {
                Icon(
                    painter = painterResource(action.playerIcon),
                    contentDescription = stringResource(action.label),
                    tint = Color.White,
                    modifier = Modifier.size((playSizeDp * 0.65f).dp),
                )
            }
        }
        PlayerIconButton(
            icon = R.drawable.ic_player_next,
            contentDescription = stringResource(R.string.action_next),
            onClick = onNext,
            enabled = state.hasNext,
            modifier = Modifier.size(skipSizeDp.dp),
            iconSize = iconSize,
        )
    }
}

/**
 * Position/buffered arrive as [State]s and are consumed only by the time bar
 * and [PositionText], so 4 Hz playback ticks never recompose this bar.
 * [onTrackAnchor] reports the px distance from this bar's bottom edge to the
 * visible track center so overlays anchor to the measured track, not a guessed
 * constant (the controls row grows with font scale and wrapping).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomBar(
    state: PlayerUiState,
    positionState: State<Long>,
    bufferedPositionState: State<Long>,
    onSeek: (Long) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
    hasSegments: Boolean = false,
    onOpenChapters: () -> Unit = {},
    callbacks: PlayerSurfaceCallbacks? = null,
    compact: Boolean = false,
    sizing: PlayerUi.ChromeSizing = PlayerUi.CompactChromeSizing,
    onTrackAnchor: (Int) -> Unit = {},
) {
    val trackMeasurements = remember { IntArray(2) }
    fun reportTrackAnchor() = onTrackAnchor((trackMeasurements[0] - trackMeasurements[1]).coerceAtLeast(0))
    Column(modifier = modifier.fillMaxWidth().onSizeChanged { trackMeasurements[0] = it.height; reportTrackAnchor() }) {
        PlayerTimeBar(
            positionState = positionState,
            bufferedPositionState = bufferedPositionState,
            durationMs = state.durationMs,
            segments = state.sponsorSegments,
            chapterStarts = state.chapters.map { it.startSeconds.toLong() * 1000 },
            onSeek = onSeek,
            onPreview = { target -> callbacks?.onTimeBarPreview(target) },
            onCommit = { callbacks?.onTimeBarCommit() },
            // Shares the 48 dp slot's lower half with the controls row; the visible
            // track sits at the halved slot's bottom edge. onGloballyPositioned must
            // precede the layout modifier — after it, size.height would be the full
            // 48 dp canvas.
            modifier = Modifier.onGloballyPositioned { coords ->
                trackMeasurements[1] = coords.positionInParent().y.roundToInt() + coords.size.height
                reportTrackAnchor()
            }.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height / 2) { placeable.place(0, 0) }
            },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chipMaxWidth = maxWidth * 0.4f
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.heightIn(min = PlayerUi.BOTTOM_ROW_DP.dp).align(Alignment.CenterVertically),
                    verticalAlignment = Alignment.CenterVertically) {
                    PositionText(
                        positionState = positionState,
                        durationMs = state.durationMs,
                        isLive = state.isLive,
                        textSizeSp = sizing.textSp,
                    )
                    // Part of the always-shown bottom readout; compact mode drops only
                    // the secondary action buttons, never this chip.
                    ChapterEntryChip(
                        chapters = chapters,
                        hasSegments = hasSegments,
                        positionState = positionState,
                        onOpen = onOpenChapters,
                        textSizeSp = sizing.textSp,
                        modifier = Modifier.padding(start = 6.dp).widthIn(max = chipMaxWidth),
                    )
                }
                Row(
                    modifier = Modifier.heightIn(min = PlayerUi.BOTTOM_ROW_DP.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconButton(
                        icon = if (state.fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                        contentDescription = stringResource(R.string.action_fullscreen),
                        onClick = onFullscreen,
                        iconSize = sizing.iconDp,
                    )
                }
            }
        }
    }
}

/** Isolated so position ticks stay inside this leaf. */
@Composable
private fun PositionText(
    positionState: State<Long>,
    durationMs: Long,
    isLive: Boolean,
    textSizeSp: Int = PlayerUi.CHROME_TEXT_SP,
) {
    val positionMs = positionState.value
    Text(
        text = PlayerUi.formatTime(positionMs),
        color = Color.White,
        fontSize = textSizeSp.sp,
        maxLines = 1,
    )
    Text(" / ", color = Color.White, fontSize = textSizeSp.sp, maxLines = 1)
    if (isLive) {
        Text(
            text = stringResource(R.string.player_live),
            color = PlayerUi.YtRed,
            fontSize = textSizeSp.sp,
            maxLines = 1,
            fontWeight = FontWeight.Medium,
        )
    } else {
        Text(
            text = PlayerUi.formatTime(durationMs),
            color = Color.White,
            fontSize = textSizeSp.sp,
            maxLines = 1,
        )
    }
}

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

/** Keeps the last non-null text so the exit fade doesn't collapse the pill; reads [hintState] here so hint writes recompose only this overlay. */
@Composable
fun HintOverlay(hintState: State<String?>, enabled: Boolean, modifier: Modifier = Modifier) {
    val hint = if (enabled) hintState.value else null
    var lastText by remember { mutableStateOf("") }
    SideEffect { if (hint != null) lastText = hint }
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(tween(motionDuration(AppMotion.ChromeInMs, LocalMotionScale.current))),
        exit = fadeOut(tween(motionDuration(AppMotion.ChromeOutMs, LocalMotionScale.current))),
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
        // The link banner needs an actually connected receiver/browser (30 s liveness
        // window in the proxy), not merely an opened cast dialog; a Chromecast session
        // is a separate mode whose proxy serves the receiver, so no banner then.
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

@Composable
fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onDetails: (() -> Unit)? = null,
) {
    val title = stringResource(R.string.player_error)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank() && !message.trim().equals(title.trim(), ignoreCase = true)) Text(
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
                fontWeight = FontWeight.Medium,
            )
        }
        if (onDetails != null) TextButton(onClick = onDetails, modifier = Modifier.testTag("playback-error-details")) {
            Text(stringResource(R.string.info), color = Color.White)
        }
    }
}

/** Fullscreen-only lock control; never inside More. */
@Composable
fun LockAffordance(
    locked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Int = PlayerUi.CHROME_ICON_DP,
) {
    PlayerIconButton(
        icon = PlayerUi.lockIconRes(locked),
        contentDescription = stringResource(PlayerUi.lockContentDescriptionRes(locked)),
        onClick = onToggle,
        modifier = modifier,
        iconSize = iconSize,
    )
}

/**
 * Compact in-app mini-player chrome: background tap toggles chrome, the center
 * restore button returns to watch; drags/pinches are claimed before child controls.
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
    val playPauseAction = PlayerUi.playPauseAction(state)
    val handle = LocalMiniPlayerHandle.current
    val collapsedSide = handle?.collapsedSide
    val suppressControls = handle?.interacting == true || collapsedSide != null
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(if (handle != null) Modifier.miniPlayerInteract(handle) else Modifier),
    ) {
        if (collapsedSide != null) {
            Box(Modifier.align(if (collapsedSide == MiniPlayerLayout.CollapsedSide.LEFT) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
                .width(MiniPlayerLayout.COLLAPSED_PEEK_DP.dp).fillMaxHeight()
                .background(Color(0xE6303030))
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_expand_miniplayer),
                    onClick = { handle?.tap?.invoke() })
                .testTag("mini-expand"), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.action_expand_miniplayer),
                    tint = Color.White, modifier = Modifier.size(24.dp)
                        .rotate(if (collapsedSide == MiniPlayerLayout.CollapsedSide.RIGHT) 180f else 0f))
            }
        }
        val gap = MiniPlayerLayout.controlGap(maxWidth.value.toInt(), screenWidthDp)
        AnimatedVisibility(
            visible = !suppressControls && (state.controlsVisible || playPauseAction == PlayerUi.PlayPauseAction.Loading),
            modifier = if (suppressControls) Modifier.clearAndSetSemantics {} else Modifier,
            enter = fadeIn(tween(motionDuration(AppMotion.ChromeInMs, LocalMotionScale.current))),
            exit = fadeOut(tween(motionDuration(AppMotion.ChromeOutMs, LocalMotionScale.current))),
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
                    enabled = !suppressControls,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(48.dp),
                    iconSize = 24,
                )
                PlayerIconButton(
                    icon = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onClose,
                    enabled = !suppressControls,
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
                    enabled = !suppressControls,
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
                        icon = R.drawable.ic_player_previous,
                        contentDescription = stringResource(R.string.action_previous),
                        onClick = onPrevious,
                        enabled = state.hasPrevious && !suppressControls,
                        modifier = Modifier.size(MiniPlayerLayout.CONTROL_SIDE_DP.dp),
                        iconSize = 20,
                    )
                    Spacer(Modifier.width(gap.dp))
                    PlayerIconButton(
                        icon = playPauseAction.playerIcon,
                        contentDescription = stringResource(playPauseAction.label),
                        loading = playPauseAction == PlayerUi.PlayPauseAction.Loading,
                        onClick = onPlayPause,
                        enabled = !suppressControls,
                        modifier = Modifier.size(MiniPlayerLayout.CONTROL_PLAY_DP.dp),
                        iconSize = 24,
                    )
                    Spacer(Modifier.width(gap.dp))
                    PlayerIconButton(
                        icon = R.drawable.ic_player_next,
                        contentDescription = stringResource(R.string.action_next),
                        onClick = onNext,
                        enabled = state.hasNext && !suppressControls,
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
    fun rootPos(change: PointerInputChange): Offset {
        val windowOrigin = coords?.localToWindow(Offset.Zero) ?: Offset.Zero
        return change.position + windowOrigin
    }

    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(handle) {
            awaitEachGesture {
                try {
                // Initial pass observes button presses and takes ownership as
                // soon as movement crosses slop, before any child handles it.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                handle.begin()
                val slop = viewConfiguration.touchSlop
                var dragging = false
                var pinching = false
                var pinchStartDist = 0f
                // True when the final up stayed unconsumed (no mini control
                // handled the tap) — only then it counts as a background tap.
                var backgroundTap = false
                val start = rootPos(down)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.isConsumed }) {
                        handle.cancel()
                        return@awaitEachGesture
                    }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val p0 = rootPos(pressed[0])
                        val p1 = rootPos(pressed[1])
                        val dist = hypot(
                            (p0.x - p1.x).toDouble(),
                            (p0.y - p1.y).toDouble(),
                        ).toFloat()
                        if (pinchStartDist <= 0f) pinchStartDist = dist
                        if (dist > 0f && pinchStartDist > 0f &&
                            (pinching || abs(dist - pinchStartDist) > slop)) {
                            pinching = true
                            handle.interacting = true
                            dragging = false
                            pressed.forEach { it.consume() }
                            handle.pinchScale(dist / pinchStartDist)
                        }
                    } else if (pressed.isNotEmpty() && !pinching) {
                        val current = rootPos(pressed[0])
                        val delta = current - start
                        if (!dragging && (abs(delta.x) > slop || abs(delta.y) > slop)) {
                            dragging = true
                            handle.interacting = true
                        }
                        if (dragging) {
                            pressed[0].consume()
                            handle.dragFromStart(delta.x, delta.y)
                        }
                    }
                    // Consume UP too, even with no position change: a drag
                    // release must never click a control beneath the finger.
                    if (dragging || pinching) event.changes.forEach { it.consume() }
                    // Children handle ordinary taps in Main before the parent
                    // decides whether an unhandled tap toggles the chrome.
                    val mainEvent = awaitPointerEvent(PointerEventPass.Main)
                    backgroundTap = mainEvent.changes.none { it.isConsumed }
                } while (event.changes.any { it.pressed })
                when {
                    dragging || pinching -> {
                        if (dragging && !pinching) handle.release() else handle.end(true)
                    }
                    backgroundTap -> {
                        handle.end(false)
                        handle.tap()
                    }
                    else -> handle.end(false)
                }
                } catch (cancelled: CancellationException) {
                    handle.cancel()
                    throw cancelled
                }
            }
        }
}
