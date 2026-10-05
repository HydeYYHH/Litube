package com.hhst.youtubelite.player.surface

import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.hhst.youtubelite.R
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.player.GestureUi
import com.hhst.youtubelite.player.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val HINT_AUTO_HIDE_MS = 1_200L
private const val SEEK_ARC_HIDE_MS = 500L

/**
 * Player overlay: video + gestures + chrome.
 *
 * Embedded: JS-reported in-page box (fallback 48 dp masthead + 16:9).
 * Fullscreen: edge-to-edge. Mini-player is sized by the host.
 *
 * Playback position arrives as [positionState] / [bufferedPositionState]
 * (updated ~4 Hz) instead of fields on [state]: they are consumed only by the
 * time bar and the position text, so ticks never recompose the rest of the
 * surface (sheets, dialogs, menus, subtitle layout, gesture overlays).
 */
@UnstableApi
@Composable
fun PlayerSurface(
    state: PlayerUiState,
    positionState: State<Long>,
    bufferedPositionState: State<Long>,
    gestureState: State<GestureUi?>,
    hintState: State<String?>,
    subtitleCuesState: State<List<String>>,
    callbacks: PlayerSurfaceCallbacks,
    onAttachSurface: (SurfaceView) -> Unit,
    onDetachSurface: (SurfaceView) -> Unit,
    zoneEnabled: (GestureMath.GestureZone) -> Boolean = { true },
    pip: Boolean = false,
    modifier: Modifier = Modifier,
    onRefreshCastState: () -> Unit = {},
    managedByHost: Boolean = false,
) {
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var menu by remember { mutableStateOf<PlayerAnchorMenu?>(null) }
    val zoom = remember { VideoZoom() }
    val miniHandle = LocalMiniPlayerHandle.current
    val fillsHostWindow = managedByHost || state.fullscreen || pip || state.mini
    // The stable local host resizes the same view between embedded and mini.
    // Fullscreen and system PiP keep their separate window geometries.
    val surfaceId = when {
        pip -> "pip"
        state.fullscreen -> "fullscreen"
        else -> "local"
    }
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val embeddedHeightDp = PlayerUi.embeddedHeightDp(state.pageHeightDp, screenWidthDp)
    val leftInsetDp = with(density) {
        WindowInsets.safeDrawing.getLeft(this, layoutDir).toDp().value.toInt()
    }
    val rightInsetDp = with(density) {
        WindowInsets.safeDrawing.getRight(this, layoutDir).toDp().value.toInt()
    }
    val topInsetDp = with(density) {
        WindowInsets.safeDrawing.getTop(this).toDp().value.toInt()
    }
    // Embedded top in window space = WebView's inset top + the page player's
    // viewport-relative top (player-hook.js reports it): this overlay is a
    // child of the window root, one level above the inset-padded WebView.
    val topDp = PlayerUi.playerTopOffsetDp(state.fullscreen, state.pageTopDp, topInsetDp)
    val chromeLeft = if (state.fullscreen && !pip) PlayerUi.fullscreenSideDp(leftInsetDp) else 0
    val chromeRight = if (state.fullscreen && !pip) PlayerUi.fullscreenSideDp(rightInsetDp) else 0
    val hudTop = if (state.fullscreen && !pip) PlayerUi.fullscreenSideDp(topInsetDp) else 0
    val hudEnd = PlayerUi.hudSidePadDp(screenWidthDp, chromeRight, rightInsetDp)
    val sidePad = if (chromeLeft > 0 || chromeRight > 0) {
        Modifier.padding(start = chromeLeft.dp, end = chromeRight.dp)
    } else {
        Modifier
    }

    LaunchedEffect(pip) {
        if (pip) {
            sheet = null
            dialog = null
            menu = null
        }
    }
    LaunchedEffect(state.mini) {
        if (state.mini) {
            // Queue is a legal surface in mini (MiniPlayerChrome has its own
            // queue button); everything else (More included) would linger
            // over the docked player.
            if (sheet != PlayerSheet.Queue) sheet = null
            dialog = null
            menu = null
        }
    }
    LaunchedEffect(state.fullscreen, state.casting, state.mini, state.videoId, pip) {
        // pip (PiP) included: the PiP window has no controls to reset a
        // leftover pinch-zoom, and its aspect is computed from raw videoSize.
        if (!state.fullscreen || state.casting || state.mini || pip) zoom.reset()
    }
    // A new video starts clean even mid-fullscreen autoplay: no inherited
    // pinch-zoom (the effect above only covers surface geometry swaps) and no
    // dropdowns/dialogs left open over the autoplayed video. The queue sheet
    // survives — the queue is what drives the advance.
    LaunchedEffect(state.videoId) {
        zoom.reset()
        if (sheet != PlayerSheet.Queue) sheet = null
        dialog = null
        menu = null
    }

    // Boolean projection of the gesture state: the auto-hide timer keys on
    // gesture activity, not on each pointer-rate gesture update.
    val gestureActive by remember { derivedStateOf { gestureState.value != null } }

    LaunchedEffect(
        state.controlsVisible, state.isPlaying, state.locked, state.scrubbing,
        sheet, dialog, menu, state.error, state.videoId, state.fullscreen, gestureActive,
    ) {
        if (sheet != null || dialog != null || menu != null || state.locked ||
            state.error != null || state.scrubbing || gestureActive
        ) {
            return@LaunchedEffect
        }
        if (state.controlsVisible && state.isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            callbacks.onHideControls()
        }
    }
    // Hint and DoubleTapSeek read their States inside the effects (snapshotFlow):
    // reading them in composition would subscribe this whole surface to the
    // pointer-rate writes.
    LaunchedEffect(Unit) {
        snapshotFlow { hintState.value }.collectLatest { hint ->
            if (hint != null) {
                delay(HINT_AUTO_HIDE_MS)
                callbacks.onHint(null)
            }
        }
    }
    // Double-tap arcs are one-shot feedback; nothing
    // else clears them, so they would stick without this timer.
    LaunchedEffect(Unit) {
        snapshotFlow { gestureState.value }.collectLatest { gesture ->
            if (gesture is GestureUi.DoubleTapSeek) {
                delay(SEEK_ARC_HIDE_MS)
                callbacks.onGestureEnd()
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(state.isPlaying, state.loading, state.casting) {
        view.keepScreenOn = !state.casting && (state.isPlaying || state.loading)
        onDispose { view.keepScreenOn = false }
    }

    Box(
        modifier = modifier
            .then(
                when {
                    fillsHostWindow -> Modifier.fillMaxSize()
                    else -> Modifier
                        .padding(top = topDp.dp)
                        .fillMaxWidth()
                        .height(embeddedHeightDp.dp)
                },
            )
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged {
                    zoom.widthPx = it.width.toFloat()
                    zoom.heightPx = it.height.toFloat()
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offsetX
                        translationY = zoom.offsetY
                    },
            ) {
                key(surfaceId) {
                    // Attach and detach via DisposableEffect keyed on the view.
                    // AndroidView factory/onRelease are not used: in a same-frame
                    // swap the new factory runs before the old onRelease, so
                    // releasing the player surface there would drop the new view.
                    // onDispose only clears the player when this view is still
                    // attached. The SurfaceView is created in composition (not in
                    // a side effect) so the effect below can attach it within the
                    // same frame the old geometry's view is disposed; a SurfaceView
                    // has no external side effects until it is attached, so a
                    // discarded speculative composition is harmless.
                    val context = LocalContext.current
                    val surface = remember { SurfaceView(context) }
                    DisposableEffect(surface) {
                        onAttachSurface(surface)
                        onDispose { onDetachSurface(surface) }
                    }
                    AndroidView(
                        factory = { ctx ->
                            AspectRatioFrameLayout(ctx).apply {
                                setAspectRatio(16f / 9f)
                                resizeMode = state.resizeMode.exoResizeMode()
                                addView(
                                    surface,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        Gravity.CENTER,
                                    ),
                                )
                            }
                        },
                        update = { frame ->
                            frame.resizeMode = state.resizeMode.exoResizeMode()
                            val w = state.videoWidth
                            val h = state.videoHeight
                            if (w > 0 && h > 0) {
                                frame.setAspectRatio(w.toFloat() / h.toFloat())
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (state.casting && !pip) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        if (state.mini && !pip) {
            MiniPlayerChrome(
                state = state,
                onPlayPause = callbacks::onPlayPause,
                onPrevious = callbacks::onPrevious,
                onNext = callbacks::onNext,
                onClose = { miniHandle?.dismiss?.invoke() ?: callbacks.onMiniClose() },
                onRestore = callbacks::onMiniRestore,
                onQueue = { sheet = PlayerSheet.Queue },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .playerGestures(
                        callbacks = rememberGestureCallbacks(
                            callbacks = callbacks,
                            locked = state.locked,
                            fullscreen = state.fullscreen,
                            zoom = zoom,
                            shortsFeed = PageKind.isShorts(state.url) && !state.mini,
                        ),
                        zoneEnabled = { zone ->
                            if (state.casting && zone == GestureMath.GestureZone.BRIGHTNESS) {
                                false
                            } else {
                                zoneEnabled(zone)
                            }
                        },
                        enabled = !pip,
                        zoomEnabled = state.fullscreen && !state.locked &&
                            !state.mini && !state.casting && !pip,
                        restartKey = state.casting to (PageKind.isShorts(state.url) && !state.mini),
                        verticalFeed = PageKind.isShorts(state.url) && !state.mini,
                    ),
            ) {
                val showChrome = state.controlsVisible && !state.locked && !pip
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ControlScrims()
                        Box(Modifier.fillMaxSize().then(sidePad)) {
                            TopBar(
                                state = state,
                                positionState = positionState,
                                menu = menu,
                                onBack = callbacks::onBack,
                                onQueue = { sheet = PlayerSheet.Queue },
                                onSegments = { menu = PlayerAnchorMenu.Segments },
                                onLoop = callbacks::onLoop,
                                onMore = { sheet = PlayerSheet.More },
                                onSubtitle = { menu = PlayerAnchorMenu.Subtitles },
                                onSubtitleStyle = { dialog = PlayerDialog.SubtitleStyle },
                                onCast = { dialog = PlayerDialog.Cast },
                                onDismissMenu = { menu = null },
                                callbacks = callbacks,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                            BottomBar(
                                state = state,
                                positionState = positionState,
                                bufferedPositionState = bufferedPositionState,
                                menu = menu,
                                onSeek = callbacks::onSeek,
                                onFullscreen = callbacks::onFullscreenToggle,
                                onSpeed = { menu = PlayerAnchorMenu.Speed },
                                onQuality = { menu = PlayerAnchorMenu.Quality },
                                onDismissMenu = { menu = null },
                                callbacks = callbacks,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxSize().then(sidePad)) {
                    SubtitleCuesLayer(
                        cuesState = subtitleCuesState,
                        showChrome = showChrome,
                        style = state.subtitleStyle,
                    )

                    AnimatedVisibility(
                        visible = (showChrome || state.loading) && !state.locked &&
                            state.error == null && !pip,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        CenterControls(
                            state = state,
                            onPlayPause = callbacks::onPlayPause,
                            onPrevious = callbacks::onPrevious,
                            onNext = callbacks::onNext,
                        )
                    }

                    HintOverlay(
                        hintState = hintState,
                        enabled = !pip,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = hudTop.dp + if (state.casting || state.castLinkConnected) {
                                    PlayerUi.CAST_BANNER_CLEAR_DP.dp
                                } else {
                                    PlayerUi.HINT_TOP_PAD_DP.dp
                                },
                            ),
                    )

                    val countdown = state.sponsorCountdownSec
                    if (!pip && countdown != null) {
                        SponsorCountdownCard(
                            seconds = countdown,
                            onCancel = callbacks::onSponsorSkipCancel,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = hudEnd.dp, bottom = PlayerUi.SPONSOR_CARD_BOTTOM_DP.dp),
                        )
                    }
                    if (!pip && state.sponsorChip) {
                        SponsorSkipChip(
                            highlight = state.sponsorChipHighlight,
                            onSkip = callbacks::onSponsorChipSkip,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = hudEnd.dp, bottom = PlayerUi.SPONSOR_CARD_BOTTOM_DP.dp),
                        )
                    }

                    if ((state.casting || state.castLinkConnected) && !pip) {
                        CastStatusBanners(
                            casting = state.casting,
                            deviceName = state.castingDeviceName,
                            linkUrl = state.castLinkUrl,
                            linkConnected = state.castLinkConnected,
                            onCast = { dialog = PlayerDialog.Cast },
                            onCloseLink = callbacks::onCloseCastLink,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = (hudTop + 8).dp),
                        )
                    }

                    if (state.error != null && !state.locked && !pip) {
                        ErrorOverlay(
                            message = state.error,
                            onRetry = callbacks::onRetry,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    if (PlayerUi.lockVisible(state.fullscreen, state.mini, state.locked, state.controlsVisible) && !pip) {
                        LockAffordance(
                            locked = state.locked,
                            onToggle = callbacks::onLockToggle,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp),
                        )
                    }
                    if (zoom.zoomed && state.fullscreen && !pip && !state.locked) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = PlayerUi.ZOOM_RESET_BOTTOM_DP.dp)
                                .background(PlayerUi.HintBg, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClick = { zoom.reset() })
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_reset),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.reset),
                                color = Color.White,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }

                if (!pip && !state.locked) {
                    GestureOverlays(
                        gestureState = gestureState,
                        insetDp = maxOf(leftInsetDp, rightInsetDp),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        PlayerSheetHost(
            sheet = sheet,
            state = state,
            callbacks = callbacks,
            onDismiss = { sheet = null },
            onOpenDialog = { dialog = it },
        )
        PlayerDialogHost(
            dialog = dialog,
            state = state,
            callbacks = callbacks,
            onDismiss = { dialog = null },
            onCopyHint = { hint -> callbacks.onHint(hint) },
            onRefreshCastState = onRefreshCastState,
        )
    }
}

/**
 * Reads [cuesState] here instead of in [PlayerSurface]: cue swaps (1-4 Hz
 * while subtitles are on) then recompose only this layer, not the whole
 * surface tree.
 */
@Composable
private fun SubtitleCuesLayer(
    cuesState: State<List<String>>,
    showChrome: Boolean,
    style: SubtitleStyle,
) {
    val cues = cuesState.value
    if (cues.isEmpty()) return
    Box(
        Modifier
            .fillMaxSize()
            .padding(
                vertical = if (showChrome) {
                    PlayerUi.SUBTITLE_CHROME_CLEAR_DP.dp
                } else {
                    PlayerUi.SUBTITLE_CLEAR_DP.dp
                },
            ),
    ) {
        SubtitleOverlay(
            cues = cues,
            style = style,
            modifier = Modifier
                .align(
                    BiasAlignment(
                        horizontalBias = style.horizontal.coerceIn(-1f, 1f),
                        verticalBias = style.verticalBias(),
                    ),
                )
                .fillMaxWidth(0.92f),
        )
    }
}

@Composable
private fun rememberGestureCallbacks(
    callbacks: PlayerSurfaceCallbacks,
    locked: Boolean,
    fullscreen: Boolean,
    zoom: VideoZoom,
    shortsFeed: Boolean,
): GestureCallbacks = remember(callbacks, locked, fullscreen, zoom, shortsFeed) {
    if (locked) {
        object : GestureCallbacks {
            override fun onTap() = Unit
        }
    } else {
        ForwardingGestures(callbacks, fullscreen, zoom, shortsFeed)
    }
}

/** Unlocked: map gesture events onto playback/window callbacks. */
private class ForwardingGestures(
    private val c: PlayerSurfaceCallbacks,
    private val fullscreen: Boolean,
    private val zoom: VideoZoom,
    private val shortsFeed: Boolean,
) : GestureCallbacks {
    override fun onTap() = c.onToggleControls()
    override fun onDoubleTapSeek(offsetMs: Long) = c.onDoubleTapSeek(offsetMs)
    override fun onDoubleTapCenter() = c.onPlayPause()
    override fun onLongPressStart() = c.onSpeedHoldStart()
    override fun onLongPressEnd() = c.onSpeedHoldEnd()
    override fun onSeekPreview(offsetMs: Long) = c.onScrubPreview(offsetMs)
    override fun onSeekCommit(offsetMs: Long) = c.onScrubCommit(offsetMs)
    override fun onBrightness(delta: Float) = c.onBrightness(delta)
    override fun onVolume(delta: Float) = c.onVolume(delta)
    override fun onGestureEnd() = c.onGestureEnd()
    override fun onFullscreenSwipe(up: Boolean) {
        if (shortsFeed) {
            c.onShortsSwipe(up)
            return
        }
        if (up != fullscreen) c.onFullscreenToggle()
    }
    override fun onZoomScale(factor: Float) = zoom.applyScale(factor)
    override fun onZoomPan(dx: Float, dy: Float) = zoom.applyPan(dx, dy)
}

/** Local fullscreen pinch-zoom; not persisted. */
private class VideoZoom {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var widthPx by mutableFloatStateOf(0f)
    var heightPx by mutableFloatStateOf(0f)

    // derivedStateOf: the reset chip reads this inside a large recompose
    // scope; boolean-flip caching keeps pointer-rate scale/pan writes from
    // recomposing the whole layer.
    val zoomed: Boolean by derivedStateOf { ZoomMath.isZoomed(scale, offsetX, offsetY) }
    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    fun applyScale(factor: Float) {
        scale = ZoomMath.clampScale(scale * factor)
        clamp()
    }
    fun applyPan(dx: Float, dy: Float) {
        if (scale <= ZoomMath.ZOOMED_SCALE) return
        offsetX += dx
        offsetY += dy
        clamp()
    }
    private fun clamp() {
        offsetX = ZoomMath.clampTranslation(offsetX, widthPx, scale)
        offsetY = ZoomMath.clampTranslation(offsetY, heightPx, scale)
    }
}
