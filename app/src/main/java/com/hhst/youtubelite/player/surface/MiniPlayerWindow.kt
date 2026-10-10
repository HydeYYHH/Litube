package com.hhst.youtubelite.player.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.HapticsController
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val SnapEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

internal val LocalMiniPlayerHandle = compositionLocalOf<MiniPlayerHandle?> { null }
internal val LocalFullscreenSwipeHandle = compositionLocalOf<FullscreenSwipeHandle?> { null }

internal class FullscreenSwipeHandle {
    var drag: (Float) -> Unit = {}
    var end: (Boolean) -> Unit = {}
}

internal class MiniPlayerHandle {
    var begin: () -> Unit = {}
    var dragFromStart: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    var pinchScale: (scale: Float) -> Unit = {}
    var end: (moved: Boolean) -> Unit = {}
    var tap: () -> Unit = {}
    var release: () -> Unit = {}
    var cancel: () -> Unit = {}
    /** Finish the exit animation before releasing the surface. */
    var dismiss: () -> Unit = {}
}

/**
 * In-app mini-player window: dock, pinch-resize, drag, snap, persist,
 * a drop target accepts an explicit drag release.
 */
@Composable
fun MiniPlayerWindow(
    screenWidthDp: Int,
    bottomInsetDp: Int,
    store: MiniPlayerStore,
    showStroke: Boolean,
    onBackgroundTap: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    mini: Boolean = true,
    fillsWindow: Boolean = false,
    fullscreenSwipeEnabled: Boolean = true,
    embeddedTopDp: Int = 0,
    embeddedHeightDp: Int = 0,
    embeddedLeftDp: Int = 0,
    embeddedWidthDp: Int = screenWidthDp,
    topInsetDp: Int = 0,
    content: @Composable () -> Unit,
) {
    val haptics: HapticsController = koinInject()
    val density = LocalDensity.current
    val loaded = remember(store) { store.load() }
    var widthOverrideDp by remember {
        mutableIntStateOf(
            if (loaded.widthDp > 0) {
                MiniPlayerLayout.clampWidthDp(screenWidthDp, loaded.widthDp)
            } else {
                MiniPlayerLayout.defaultWidthDp(screenWidthDp)
            },
        )
    }
    val spec = MiniPlayerLayout.computeSpec(screenWidthDp, bottomInsetDp, widthOverrideDp)
    var measuredParentSize by remember { mutableStateOf(IntSize.Zero) }
    // Effects must use the same bounds as restX/restY from this composition.
    // A size callback can otherwise update the state before an effect starts,
    // while its captured dock coordinates still belong to the zero-size frame.
    val parentSize = measuredParentSize
    var dragX by remember { mutableFloatStateOf(with(density) { loaded.translationXDp.dp.toPx() }) }
    var dragY by remember { mutableFloatStateOf(with(density) { loaded.translationYDp.dp.toPx() }) }
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    var pinchStartWidthDp by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // The snap animation keeps writing dragX/dragY for its whole duration; a
    // new grab during it must cancel the job, or the finish block yanks the
    // window back to the old dock point and persists that.
    var snapJob by remember { mutableStateOf<Job?>(null) }
    // The window exits only after a release in the close target; then
    // [onDismiss] tears the surface down (it leaves composition, so no
    // drag/pinch/persist may run in between).
    var dismissing by remember { mutableStateOf(false) }
    var dragAccepted by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var closeArmed by remember { mutableStateOf(false) }
    var dismissScale by remember { mutableFloatStateOf(1f) }
    var dismissAlpha by remember { mutableFloatStateOf(1f) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var previousMini by remember { mutableStateOf(mini) }
    var previousFillsWindow by remember { mutableStateOf(fillsWindow) }
    var swiping by remember { mutableStateOf(false) }
    var swipeParentSize by remember { mutableStateOf(IntSize.Zero) }
    var swipeStartedFullscreen by remember { mutableStateOf(false) }
    var awaitingFullscreen by remember { mutableStateOf<Boolean?>(null) }
    var swipeRelease by remember { mutableIntStateOf(0) }
    var transitionRunning by remember { mutableStateOf(false) }
    var placementRestored by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(MiniPlayerLayout.CORNER_DP.dp)

    val widthPx = with(density) { spec.widthDp.dp.toPx() }
    val heightPx = with(density) { spec.heightDp.dp.toPx() }
    val restX = if (parentSize.width > 0) {
        parentSize.width - with(density) { spec.rightMarginDp.dp.toPx() } - widthPx
    } else 0f
    val restY = if (parentSize.height > 0) {
        parentSize.height - with(density) { spec.bottomMarginDp.dp.toPx() } - heightPx
    } else 0f

    val closeWidth = with(density) { 96.dp.toPx() }
    val closeHeight = with(density) { 72.dp.toPx() }
    val closeBottom = parentSize.height - with(density) { (bottomInsetDp + 16).dp.toPx() }
    val closeBounds = Rect((parentSize.width - closeWidth) / 2f, closeBottom - closeHeight,
        (parentSize.width + closeWidth) / 2f, closeBottom)

    fun persist(x: Float, y: Float) {
        store.save(spec.widthDp, x / density.density, y / density.density)
    }

    LaunchedEffect(mini, spec, parentSize, topInsetDp, density.density) {
        if (parentSize.width <= 0 || parentSize.height <= 0 || dismissing) return@LaunchedEffect
        dragAccepted = false
        dragging = false
        closeArmed = false
        // A layout change invalidates the snap's captured width and dock.
        // In particular a pinch ending in the same frame must not let its
        // older snap completion overwrite the new stored width.
        snapJob?.cancel()
        snapJob = null
        // Other window modes must not clamp the saved placement to their bounds.
        if (!mini) return@LaunchedEffect
        if (!placementRestored) {
            val saved = store.load()
            dragX = saved.translationXDp * density.density
            dragY = saved.translationYDp * density.density
            placementRestored = true
        }
        val x = MiniPlayerLayout.snapX(dragX, restX.toInt(), widthPx.toInt(), parentSize.width)
        val y = maxOf(with(density) { topInsetDp.dp.toPx() } - restY, MiniPlayerLayout.clampTranslation(
            dragY, restY.toInt(), heightPx.toInt(), parentSize.height,
        ))
        dragX = x
        dragY = y
        // A pinch drives a width change per frame: keep re-docking/clamping
        // continuously, but persist only when no gesture holds the window —
        // the drag/pinch end handler persists the final placement, so the
        // store is not written on every pointer event.
        if (mini && pinchStartWidthDp == 0) persist(x, y)
    }

    val fullscreenBounds = Rect(0f, 0f, parentSize.width.toFloat(), parentSize.height.toFloat())
    val embeddedBounds = with(density) { Rect(embeddedLeftDp.dp.toPx(), embeddedTopDp.dp.toPx(),
        (embeddedLeftDp + embeddedWidthDp).dp.toPx(),
        embeddedTopDp.dp.toPx() + embeddedHeightDp.dp.toPx()) }
    val target = if (mini) Rect(restX + dragX, restY + dragY, restX + dragX + widthPx, restY + dragY + heightPx)
        else if (fillsWindow) fullscreenBounds else embeddedBounds
    LaunchedEffect(mini, fillsWindow, target, parentSize, swiping, swipeRelease, fullscreenSwipeEnabled) {
        if (parentSize.width <= 0 || parentSize.height <= 0) return@LaunchedEffect
        if (swiping && !mini && fullscreenSwipeEnabled && swipeParentSize == parentSize) return@LaunchedEffect
        if (awaitingFullscreen != null && !mini && fullscreenSwipeEnabled && fillsWindow != awaitingFullscreen) return@LaunchedEffect
        swiping = false
        awaitingFullscreen = null
        val changed = previousMini != mini
        val windowChanged = previousFillsWindow != fillsWindow
        previousMini = mini
        previousFillsWindow = fillsWindow
        if (changed && !mini) {
            dragging = false
            closeArmed = false
            snapJob?.cancel()
            snapJob = null
        }
        val from = viewport
        if (fullscreenSwipeEnabled && (changed || windowChanged || transitionRunning) && from.width > 0 && from.height > 0) {
            transitionRunning = true
            Animatable(0f).animateTo(1f, tween(MiniPlayerLayout.TRANSITION_MS,
                easing = if (changed) SnapEasing else FastOutSlowInEasing)) {
                viewport = lerp(from, target, value)
            }
        } else viewport = target
        transitionRunning = false
    }

    val handle = remember { MiniPlayerHandle() }
    val fullscreenSwipe = remember { FullscreenSwipeHandle() }
    SideEffect {
        fullscreenSwipe.drag = fun(distance) {
            if (mini || !fullscreenSwipeEnabled || dismissing || parentSize.width <= 0 || parentSize.height <= 0) return
            if (!swiping) {
                if ((fillsWindow && distance <= 0f) || (!fillsWindow && distance >= 0f)) return
                swiping = true
                swipeStartedFullscreen = fillsWindow
                swipeParentSize = parentSize
                awaitingFullscreen = null
            }
            val progress = ((if (swipeStartedFullscreen) distance else -distance) /
                (parentSize.height * 0.4f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            viewport = if (swipeStartedFullscreen) lerp(fullscreenBounds, embeddedBounds, progress)
                else lerp(embeddedBounds, fullscreenBounds, progress)
        }
        fullscreenSwipe.end = { committed ->
            if (swiping) {
                awaitingFullscreen = if (committed) !swipeStartedFullscreen else null
                transitionRunning = true
                swiping = false
                // A move and cancellation in one frame still need a settling animation.
                swipeRelease++
            }
        }
        // A plain tap also fires begin(): cancelling the snap here would
        // freeze a mid-flight docking animation (end(false) does not resume
        // it). The snap is cancelled on the first real DRAG move instead —
        // see dragFromStart.
        handle.begin = {
            if (mini && !dismissing && !transitionRunning) {
                dragAccepted = true
                originX = dragX
                originY = dragY
                pinchStartWidthDp = spec.widthDp
            }
        }
        handle.dragFromStart = fun(dx, dy) {
            if (!mini || !dragAccepted || parentSize.width <= 0 || dismissing || transitionRunning) return
            // First drag move: cancel the in-flight snap and re-anchor the
            // origin to the CURRENT (mid-animation) position, so the drag
            // does not jump from a stale pre-cancel origin.
            if (snapJob != null) {
                snapJob?.cancel()
                snapJob = null
                originX = dragX
                originY = dragY
            }
            dragging = true
            dragX = MiniPlayerLayout.clampTranslation(
                originX + dx, restX.toInt(), widthPx.toInt(), parentSize.width,
            )
            // Partial off-screen movement lets every window size reach the bottom target.
            dragY = (originY + dy).coerceIn(with(density) { topInsetDp.dp.toPx() } - restY,
                maxOf(with(density) { topInsetDp.dp.toPx() } - restY, closeBounds.center.y - restY - heightPx / 2))
            val hit = MiniPlayerLayout.hitsCloseTarget(restX + dragX + widthPx / 2,
                restY + dragY + heightPx / 2, closeBounds.left, closeBounds.top, closeBounds.right, closeBounds.bottom)
            if (hit && !closeArmed) haptics.perform(HapticsController.Event.DROP_TARGET)
            closeArmed = hit
        }
        handle.pinchScale = { scale ->
            dragging = false
            closeArmed = false
            if (mini && !dismissing && !transitionRunning) {
                snapJob?.cancel()
                snapJob = null
                val start = pinchStartWidthDp.takeIf { it > 0 } ?: spec.widthDp
                widthOverrideDp = MiniPlayerLayout.clampWidthDp(
                    screenWidthDp,
                    (start * scale).roundToInt(),
                )
            }
        }
        handle.end = fun(moved) {
            dragging = false
            closeArmed = false
            if (!mini || dismissing || transitionRunning) return
            pinchStartWidthDp = 0
            if (!moved || parentSize.width <= 0) return
            val x = MiniPlayerLayout.snapX(dragX, restX.toInt(), widthPx.toInt(), parentSize.width)
            val y = MiniPlayerLayout.clampTranslation(
                dragY, restY.toInt(), heightPx.toInt(), parentSize.height,
            )
            val snapSpec = tween<Float>(MiniPlayerLayout.TRANSITION_MS, easing = SnapEasing)
            snapJob = scope.launch {
                val ax = Animatable(dragX)
                val ay = Animatable(dragY)
                coroutineScope {
                    launch { ax.animateTo(x, snapSpec) { dragX = value } }
                    launch { ay.animateTo(y, snapSpec) { dragY = value } }
                }
                dragX = x
                dragY = y
                persist(x, y)
            }
        }
        handle.cancel = { dragging = false; closeArmed = false; handle.end(true) }
        handle.release = { if (dragging && closeArmed) handle.dismiss() else handle.end(true) }
        handle.tap = onBackgroundTap
        handle.dismiss = {
            if (mini && !dismissing && parentSize.width > 0 && parentSize.height > 0) {
                dragging = false
                closeArmed = false
                dismissing = true
                snapJob?.cancel()
                snapJob = null
                scope.launch {
                    coroutineScope {
                        launch { Animatable(1f).animateTo(0.5f, tween(MiniPlayerLayout.DISMISS_MS)) { dismissScale = value } }
                        launch { Animatable(1f).animateTo(0f, tween(MiniPlayerLayout.DISMISS_MS)) { dismissAlpha = value } }
                    }
                    onDismiss()
                }
            }
        }
    }

    CompositionLocalProvider(LocalMiniPlayerHandle provides if (mini) handle else null,
        LocalFullscreenSwipeHandle provides if (!mini && fullscreenSwipeEnabled) fullscreenSwipe else null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { if (it.width > 0 && it.height > 0) measuredParentSize = it },
        ) {
            if (!mini && fullscreenSwipeEnabled && (swiping || transitionRunning || fillsWindow)) {
                val shade = ((viewport.height - embeddedBounds.height) /
                    (fullscreenBounds.height - embeddedBounds.height).coerceAtLeast(1f)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade)))
            }
            if (mini && dragging && !dismissing) {
                val shade = if (closeArmed) MaterialTheme.colorScheme.error else Color(0xFF606060)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height((bottomInsetDp + 156).dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Brush.verticalGradient(
                        listOf(shade.copy(alpha = 0.56f), shade.copy(alpha = 0.72f)))))
                Box(Modifier.zIndex(2f).absoluteOffset { IntOffset(closeBounds.left.roundToInt(), closeBounds.top.roundToInt()) }
                    .size(96.dp, 72.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.close_player),
                        tint = Color.White.copy(alpha = if (closeArmed) 1f else 0.95f),
                        modifier = Modifier.size(36.dp))
                }
            }
            // Wait until the parent reports its size. With restX and restY still
            // at 0, the window would appear at the parent's top-left corner
            // before moving to its dock.
            if (parentSize.width > 0 && parentSize.height > 0 && viewport.width > 0 && viewport.height > 0) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { alpha = dismissAlpha; scaleX = dismissScale; scaleY = dismissScale }
                        .absoluteOffset {
                            IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt())
                        }
                        .size(with(density) { viewport.width.coerceAtLeast(1f).toDp() }, with(density) { viewport.height.coerceAtLeast(1f).toDp() })
                        .clip(if (mini || transitionRunning) shape else RoundedCornerShape(0.dp))
                        .then(
                            if (mini && showStroke) {
                                Modifier.border(1.dp, Color(MiniPlayerLayout.STROKE_COLOR), shape)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    content()
                }
            }
        }
    }
}
