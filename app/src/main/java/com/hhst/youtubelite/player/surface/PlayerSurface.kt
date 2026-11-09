package com.hhst.youtubelite.player.surface

import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import com.hhst.youtubelite.ui.design.AppMotion
import com.hhst.youtubelite.ui.design.LocalMotionScale
import com.hhst.youtubelite.ui.design.motionDuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.GestureUi
import com.hhst.youtubelite.player.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val HINT_AUTO_HIDE_MS = 1_200L
private const val SEEK_ARC_HIDE_MS = 500L

/**
 * Position ticks arrive as [positionState] / [bufferedPositionState] (not fields on
 * [state]) so ~4 Hz updates recompose only the time bar, not the whole surface.
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
    transitionFrame: android.graphics.Bitmap? = null,
    chromeAlpha: () -> Float = { 1f },
    showPipLoadingIndicator: Boolean = true,
) {
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var menu by remember { mutableStateOf<PlayerAnchorMenu?>(null) }
    val miniTransition = LocalMiniTransitionActive.current
    val miniChromeAlpha = LocalMiniChromeAlpha.current
    state.castFailure?.let { report -> CastFailureDialog(report, callbacks::onCastFailureDismiss) }
    LaunchedEffect(state.castFailure?.id) {
        if (state.castFailure != null) { sheet = null; dialog = null; menu = null }
    }
    LaunchedEffect(state.failureReport?.id) {
        if (state.failureReport != null) { sheet = null; dialog = null; menu = null }
    }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    // Measured track anchor (not a hardcoded bar height) shared by sponsor cards
    // and subtitle clearance.
    var trackClearancePx by remember { mutableIntStateOf(0) }
    // Visible height only: the 48 dp touch box is invisible and must not push subtitles up.
    var skipCardPx by remember { mutableIntStateOf(0) }
    val zoom = remember { VideoZoom() }
    val miniHandle = LocalMiniPlayerHandle.current
    val fullscreenSwipe = LocalFullscreenSwipeHandle.current
    SideEffect {
        fullscreenSwipe?.let {
            it.pictureAspectRatio = if (state.videoWidth > 0 && state.videoHeight > 0)
                state.videoWidth.toFloat() / state.videoHeight else 16f / 9f
            it.pictureResizeMode = state.resizeMode
        }
    }
    val fillsHostWindow = managedByHost || state.fullscreen || pip || state.mini
    // Host-managed surfaces resize the same view; only system PiP swaps to a
    // separate geometry, so it alone gets its own key.
    val surfaceId = when {
        pip -> "pip"
        managedByHost -> "local"
        state.fullscreen -> "fullscreen"
        else -> "local"
    }
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val embeddedBounds = PlayerUi.embeddedBounds(state, screenWidthDp)
    val embeddedHeightDp = embeddedBounds.height
    val leftInsetDp = with(density) {
        WindowInsets.safeDrawing.getLeft(this, layoutDir).toDp().value.toInt()
    }
    val rightInsetDp = with(density) {
        WindowInsets.safeDrawing.getRight(this, layoutDir).toDp().value.toInt()
    }
    val topInsetDp = with(density) {
        WindowInsets.safeDrawing.getTop(this).toDp().value.toInt()
    }
    val topDp = PlayerUi.playerTopOffsetDp(state.fullscreen, embeddedBounds.top, topInsetDp)
    val chromeLeft = if (state.fullscreen && !pip) PlayerUi.fullscreenSideDp(leftInsetDp) else 0
    val chromeRight = if (state.fullscreen && !pip) PlayerUi.fullscreenSideDp(rightInsetDp) else 0
    val hudTop = if (state.fullscreen && !pip) PlayerUi.fullscreenSideDp(topInsetDp) else 0
    // Already inside the fullscreen chrome inset; add only enough to keep the
    // countdown 12 dp from the safe edge, not the gesture rail's 12%.
    val sponsorEnd = (maxOf(12, rightInsetDp) - chromeRight).coerceAtLeast(0)
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
            // Queue survives in mini (MiniPlayerChrome has its own button);
            // other sheets would cover the docked player.
            if (sheet != PlayerSheet.Queue) sheet = null
            dialog = null
            menu = null
        }
    }
    LaunchedEffect(state.fullscreen, state.casting, state.mini, state.videoId, pip) {
        // PiP included: it has no controls to reset a leftover pinch-zoom and
        // its aspect is computed from raw videoSize.
        if (!state.fullscreen || state.casting || state.mini || pip) zoom.reset()
    }
    // New video starts clean even mid-fullscreen autoplay (the effect above only
    // covers geometry swaps); the queue sheet survives — it drives the advance.
    LaunchedEffect(state.videoId) {
        zoom.reset()
        if (sheet != PlayerSheet.Queue) sheet = null
        dialog = null
        menu = null
    }

    // Boolean projection: the auto-hide timer keys on gesture presence, not on
    // each pointer-rate gesture update.
    val gestureActive by remember { derivedStateOf { gestureState.value != null } }
    val seekGestureActive by remember { derivedStateOf { gestureState.value is GestureUi.Scrub } }
    val previewPosition = remember(positionState, gestureState) {
        derivedStateOf { (gestureState.value as? GestureUi.Scrub)?.targetMs ?: positionState.value }
    }

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
    // Read States inside snapshotFlow: reading them in composition would
    // subscribe this whole surface to the pointer-rate writes.
    LaunchedEffect(Unit) {
        snapshotFlow { hintState.value }.collectLatest { hint ->
            if (hint != null) {
                delay(HINT_AUTO_HIDE_MS)
                callbacks.onHint(null)
            }
        }
    }
    // One-shot feedback: nothing else clears the arcs, so they would stick
    // without this timer.
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

    BoxWithConstraints(
        modifier = modifier
            .then(
                when {
                    fillsHostWindow -> Modifier.fillMaxSize()
                    else -> Modifier
                        .padding(top = topDp.dp)
                        .absoluteOffset(x = embeddedBounds.left.dp)
                        .width(embeddedBounds.width.dp)
                        .height(embeddedHeightDp.dp)
                },
            )
            .background(Color.Black),
    ) {
        val compactChrome = PlayerUi.compactChrome(maxWidth.value - chromeLeft - chromeRight,
            density.fontScale, state.casting || state.castDevices.isNotEmpty())
        val chromeSizing = PlayerUi.chromeSizing(maxWidth.value - chromeLeft - chromeRight, maxHeight.value)
        val centerHeightDp = maxHeight.value - with(density) { (topBarHeightPx + bottomBarHeightPx).toDp().value }
        val showChromeAuthor = maxHeight >= 280.dp || density.fontScale < 1.3f
        val subtitleScale = if (state.mini || miniTransition || pip) (maxWidth.value / 390f).coerceIn(0.35f, 1f) else 1f
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
                        scaleX = zoom.scale * (fullscreenSwipe?.pictureScale ?: 1f)
                        scaleY = zoom.scale * (fullscreenSwipe?.pictureScale ?: 1f)
                        translationX = zoom.offsetX + (fullscreenSwipe?.pictureTranslationX ?: 0f)
                        translationY = zoom.offsetY + (fullscreenSwipe?.pictureTranslationY ?: 0f)
                    },
            ) {
                key(surfaceId) {
                    // Attach/detach via DisposableEffect, not AndroidView
                    // factory/onRelease: in a same-frame swap the new factory
                    // runs before the old onRelease, so releasing there would
                    // drop the new view. Creating the SurfaceView in composition
                    // lets the effect attach it in the same frame the old view
                    // is disposed; it has no side effects until attached, so a
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
                                // Fixed buffer size: layout can animate without
                                // reallocating the decoder output.
                                surface.holder.setFixedSize(w, h)
                                frame.setAspectRatio(w.toFloat() / h.toFloat())
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Sits inside the transform layer so it travels with the picture
                // instead of pinning an old frame over a translated/zoomed Surface.
                if (!state.hasRenderedVideoFrame && !state.casting) {
                    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player-frame-shutter"))
                }
                transitionFrame?.let { frame -> PlayerTransitionFrame(frame, state.resizeMode) }
            }
        }
        if (state.casting && !pip) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        if (pip && showPipLoadingIndicator && PlayerUi.playPauseAction(state) == PlayerUi.PlayPauseAction.Loading) {
            PlaybackLoadingIndicator(Modifier.align(Alignment.Center).size(24.dp))
        }

        if (state.mini && !pip) {
            SubtitleCuesLayer(subtitleCuesState, showChrome = false,
                style = state.subtitleStyle.copy(textScale = state.subtitleStyle.textScale * subtitleScale),
                bottomClearDp = 4f)
            MiniPlayerChrome(
                state = state,
                onPlayPause = callbacks::onPlayPause,
                onPrevious = callbacks::onPrevious,
                onNext = callbacks::onNext,
                onClose = { miniHandle?.dismiss?.invoke() ?: callbacks.onMiniClose() },
                onRestore = callbacks::onMiniRestore,
                onQueue = { sheet = PlayerSheet.Queue },
                modifier = Modifier.graphicsLayer { alpha = miniChromeAlpha() },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (miniTransition) 0f else chromeAlpha() }
                    .then(if (miniTransition) Modifier.clearAndSetSemantics {} else Modifier)
                    .playerGestures(
                        callbacks = rememberGestureCallbacks(
                            callbacks = callbacks,
                            locked = state.locked,
                            fullscreen = state.fullscreen,
                            zoom = zoom,
                            fullscreenSwipe = fullscreenSwipe,
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
                        restartKey = state.casting,
                        fullscreenEntry = !state.fullscreen && !state.mini,
                    ),
            ) {
                val showChrome = (state.controlsVisible || seekGestureActive) && !state.locked && !pip
                val trackClearanceDp = with(density) { trackClearancePx.toDp() }
                val skipCardDp = with(density) { skipCardPx.toDp() }
                val skipCardsVisible = !pip && !state.locked &&
                    state.sponsorCountdownSec != null
                val chipBandBottom by animateDpAsState(
                    if (showChrome) trackClearanceDp + PlayerUi.TRACK_CHIP_GAP_DP.dp else PlayerUi.SUBTITLE_CLEAR_DP.dp,
                    tween(250),
                    label = "chipBandBottom",
                )
                // Subtitles clear measured bounds (controls row + visible skip
                // cards), not a flat reserve; invisible touch boxes don't count.
                val subtitleBottomClearDp = maxOf(
                    if (showChrome) trackClearanceDp.value + 6f else PlayerUi.SUBTITLE_CLEAR_DP.dp.value,
                    if (skipCardsVisible) chipBandBottom.value + skipCardDp.value + PlayerUi.TRACK_CHIP_GAP_DP else 0f,
                )
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(tween(motionDuration(AppMotion.ChromeInMs, LocalMotionScale.current))),
                    exit = fadeOut(tween(motionDuration(AppMotion.ChromeOutMs, LocalMotionScale.current))),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ControlScrims()
                        Box(Modifier.fillMaxSize().then(sidePad)) {
                            TopBar(
                                state = state,
                                positionState = previewPosition,
                                menu = menu,
                                onBack = callbacks::onBack,
                                onMore = { sheet = PlayerSheet.More },
                                onSubtitle = { menu = PlayerAnchorMenu.Subtitles },
                                onSubtitleStyle = { sheet = PlayerSheet.SubtitleStyle },
                                onDismissMenu = { menu = null },
                                callbacks = callbacks,
                                showAuthor = showChromeAuthor,
                                sizing = chromeSizing,
                                modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topBarHeightPx = it.height },
                            )
                            BottomBar(
                                state = state,
                                positionState = previewPosition,
                                bufferedPositionState = bufferedPositionState,
                                onSeek = callbacks::onSeek,
                                onFullscreen = callbacks::onFullscreenToggle,
                                chapters = state.chapters,
                                hasSegments = state.sponsorSegments.isNotEmpty(),
                                onOpenChapters = { sheet = PlayerSheet.Chapters },
                                callbacks = callbacks,
                                compact = compactChrome,
                                sizing = chromeSizing,
                                onTrackAnchor = { trackClearancePx = it },
                                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomBarHeightPx = it.height },
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxSize().then(sidePad)) {
                    val countdown = state.sponsorCountdownSec
                    SubtitleCuesLayer(
                        cuesState = subtitleCuesState,
                        showChrome = showChrome,
                        style = state.subtitleStyle.copy(textScale = state.subtitleStyle.textScale * subtitleScale),
                        bottomClearDp = subtitleBottomClearDp,
                    )

                    if (!gestureActive) AnimatedVisibility(
                        visible = (showChrome || state.loading) && !state.locked &&
                            state.error == null && !pip,
                        enter = fadeIn(tween(motionDuration(AppMotion.ChromeInMs, LocalMotionScale.current))),
                        exit = fadeOut(tween(motionDuration(AppMotion.ChromeOutMs, LocalMotionScale.current))),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        CenterControls(
                            state = state,
                            modifier = Modifier.testTag("player-center-controls"),
                            onPlayPause = callbacks::onPlayPause,
                            onPrevious = callbacks::onPrevious,
                            onNext = callbacks::onNext,
                            playSizeDp = centerHeightDp.coerceIn(48f, chromeSizing.playDp.toFloat()).toInt(),
                            iconSize = chromeSizing.iconDp,
                            skipSizeDp = minOf(chromeSizing.skipDp, centerHeightDp.coerceAtLeast(48f).toInt()),
                            gapDp = chromeSizing.centerGapDp,
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

                    if (!pip && !state.locked && countdown != null) {
                        SponsorCountdownCard(
                            seconds = countdown,
                            onCancel = callbacks::onSponsorSkipCancel,
                            onVisualHeight = { skipCardPx = it },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = sponsorEnd.dp, bottom = chipBandBottom),
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

                    if (state.notice != null && !state.locked && !pip) {
                        Text(
                            text = stringResource(R.string.player_payment_required),
                            color = Color.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp)
                                .testTag("player-access-notice"),
                        )
                    }

                    if (state.error != null && !state.locked && !pip) {
                        ErrorOverlay(
                            message = state.error,
                            onDetails = if (state.failureReport == null) null else callbacks::onErrorDetails,
                            onRetry = callbacks::onRetry,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    if (PlayerUi.lockVisible(state.fullscreen, state.mini, state.locked, state.controlsVisible) && !pip) {
                        LockAffordance(
                            locked = state.locked,
                            onToggle = callbacks::onLockToggle,
                            iconSize = chromeSizing.iconDp,
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

        if (miniTransition && !pip) {
            SubtitleCuesLayer(subtitleCuesState, showChrome = false,
                style = state.subtitleStyle.copy(textScale = state.subtitleStyle.textScale * subtitleScale), bottomClearDp = 4f)
            MiniPlayerChrome(state = state.copy(mini = true, controlsVisible = true), onPlayPause = {}, onPrevious = {}, onNext = {},
                onClose = {}, onRestore = {}, onQueue = {}, modifier = Modifier.graphicsLayer { alpha = miniChromeAlpha() })
        }

        PlayerSheetHost(
            sheet = sheet,
            state = state,
            positionState = positionState,
            callbacks = callbacks,
            onDismiss = { sheet = null },
            onOpenDialog = { dialog = it },
            onNavigate = { sheet = it },
            onOpenQueue = { sheet = PlayerSheet.Queue },
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

@Composable
internal fun PlayerTransitionFrame(frame: android.graphics.Bitmap, resizeMode: ResizeMode = ResizeMode.Fit) {
    val scale = when (resizeMode) {
        ResizeMode.Fit -> ContentScale.Fit
        ResizeMode.Fill -> ContentScale.FillBounds
        ResizeMode.Zoom -> ContentScale.Crop
        ResizeMode.FixedWidth -> ContentScale.FillWidth
        ResizeMode.FixedHeight -> ContentScale.FillHeight
    }
    // PixelCopy returns the full buffer without the parent viewport's clipping;
    // apply the live Surface's resize policy, incl. letterbox cover.
    androidx.compose.foundation.Image(frame.asImageBitmap(), contentDescription = null,
        contentScale = scale,
        modifier = Modifier.fillMaxSize().clipToBounds().background(Color.Black).testTag("player-handoff-frame"))
}

/** Reads [cuesState] here so cue swaps (1-4 Hz) recompose only this layer, not the whole surface tree. */
@Composable
private fun SubtitleCuesLayer(
    cuesState: State<List<String>>,
    showChrome: Boolean,
    style: SubtitleStyle,
    bottomClearDp: Float,
) {
    val cues = cuesState.value
    if (cues.isEmpty()) return
    Box(
        Modifier
            .fillMaxSize()
            .padding(
                top = if (showChrome) PlayerUi.SUBTITLE_CHROME_CLEAR_DP.dp else PlayerUi.SUBTITLE_CLEAR_DP.dp,
                bottom = bottomClearDp.dp,
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
    fullscreenSwipe: FullscreenSwipeHandle?,
): GestureCallbacks {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.GESTURE_THRESHOLD)
    return remember(callbacks, locked, fullscreen, zoom, fullscreenSwipe, haptic) {
    if (locked) {
        object : GestureCallbacks {
            override fun onTap() = Unit
        }
    } else {
        ForwardingGestures(callbacks, fullscreen, zoom, fullscreenSwipe, haptic)
    }
}
}

private class ForwardingGestures(
    private val c: PlayerSurfaceCallbacks,
    private val fullscreen: Boolean,
    private val zoom: VideoZoom,
    private val fullscreenSwipe: FullscreenSwipeHandle?,
    private val fullscreenHaptic: () -> Unit,
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
    override fun onGestureEnd() { fullscreenSwipe?.end?.invoke(false); c.onGestureEnd() }
    override fun onFullscreenSwipeProgress(distancePx: Float) { fullscreenSwipe?.drag?.invoke(distancePx) }
    override fun onWindowSwipeProgress(dx: Float, dy: Float) {
        fullscreenSwipe?.horizontal = dx
        fullscreenSwipe?.drag?.invoke(dy)
    }
    override fun onFullscreenSwipeCancel() { fullscreenSwipe?.end?.invoke(false) }
    override fun onFullscreenSwipeRelease(distancePx: Float, velocityPxPerSec: Float): Boolean =
        fullscreenSwipe?.release?.invoke(distancePx, velocityPxPerSec) ?: false
    override fun onFullscreenSwipe(up: Boolean) {
        if (fullscreen || up) {
            fullscreenHaptic()
            if (fullscreen) {
                if (up) c.onFullscreenSwipeExit() else c.onFullscreenSwipeRestore()
            } else c.onFullscreenToggle()
        }
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

    // derivedStateOf caches the boolean flip so pointer-rate scale/pan writes
    // don't recompose the layer the reset chip reads in.
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
