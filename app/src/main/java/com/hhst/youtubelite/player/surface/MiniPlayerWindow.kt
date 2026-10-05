package com.hhst.youtubelite.player.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val SnapEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

internal val LocalMiniPlayerHandle = compositionLocalOf<MiniPlayerHandle?> { null }

internal class MiniPlayerHandle {
    var begin: () -> Unit = {}
    var dragFromStart: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    var pinchScale: (scale: Float) -> Unit = {}
    var end: (moved: Boolean) -> Unit = {}
    var tap: () -> Unit = {}
    /** Swipe-down rule met: play the exit animation, then [MiniPlayerWindow.onDismiss]. */
    var dismiss: () -> Unit = {}
}

/**
 * In-app mini-player window: dock, pinch-resize, drag, snap, persist,
 * swipe-down dismiss (slide below the parent while fading out).
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
    embeddedTopDp: Int = 0,
    embeddedHeightDp: Int = 0,
    topInsetDp: Int = 0,
    content: @Composable () -> Unit,
) {
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
    // Swipe-down dismiss: the window animates below the parent while fading,
    // then [onDismiss] tears the surface down (it leaves composition, so no
    // drag/pinch/persist may run in between).
    var dismissing by remember { mutableStateOf(false) }
    var dismissAlpha by remember { mutableFloatStateOf(1f) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var previousMini by remember { mutableStateOf(mini) }
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

    fun persist(x: Float, y: Float) {
        store.save(spec.widthDp, x / density.density, y / density.density)
    }

    LaunchedEffect(spec, parentSize, topInsetDp, density.density) {
        if (parentSize.width <= 0 || parentSize.height <= 0 || dismissing) return@LaunchedEffect
        // A layout change invalidates the snap's captured width and dock.
        // In particular a pinch ending in the same frame must not let its
        // older snap completion overwrite the new stored width.
        snapJob?.cancel()
        snapJob = null
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

    val target = if (mini) Rect(restX + dragX, restY + dragY, restX + dragX + widthPx, restY + dragY + heightPx)
        else if (fillsWindow) Rect(0f, 0f, parentSize.width.toFloat(), parentSize.height.toFloat())
        else with(density) { Rect(0f, embeddedTopDp.dp.toPx(), parentSize.width.toFloat(),
            embeddedTopDp.dp.toPx() + embeddedHeightDp.dp.toPx()) }
    LaunchedEffect(mini, fillsWindow, target, parentSize) {
        if (parentSize.width <= 0 || parentSize.height <= 0) return@LaunchedEffect
        val changed = previousMini != mini
        previousMini = mini
        if (changed && !mini) {
            snapJob?.cancel()
            snapJob = null
            persist(dragX, dragY)
        }
        val from = viewport
        if ((changed || transitionRunning) && from.width > 0 && from.height > 0) {
            transitionRunning = true
            Animatable(0f).animateTo(1f, tween(MiniPlayerLayout.TRANSITION_MS, easing = SnapEasing)) {
                viewport = androidx.compose.ui.geometry.lerp(from, target, value)
            }
        } else viewport = target
        transitionRunning = false
    }

    val handle = remember { MiniPlayerHandle() }
    SideEffect {
        // A plain tap also fires begin(): cancelling the snap here would
        // freeze a mid-flight docking animation (end(false) does not resume
        // it). The snap is cancelled on the first real DRAG move instead —
        // see dragFromStart.
        handle.begin = {
            if (mini && !dismissing && !transitionRunning) {
                originX = dragX
                originY = dragY
                pinchStartWidthDp = spec.widthDp
            }
        }
        handle.dragFromStart = fun(dx, dy) {
            if (!mini || parentSize.width <= 0 || dismissing || transitionRunning) return
            // First drag move: cancel the in-flight snap and re-anchor the
            // origin to the CURRENT (mid-animation) position, so the drag
            // does not jump from a stale pre-cancel origin.
            if (snapJob != null) {
                snapJob?.cancel()
                snapJob = null
                originX = dragX
                originY = dragY
            }
            dragX = MiniPlayerLayout.clampTranslation(
                originX + dx, restX.toInt(), widthPx.toInt(), parentSize.width,
            )
            dragY = maxOf(with(density) { topInsetDp.dp.toPx() } - restY, MiniPlayerLayout.clampTranslation(
                originY + dy, restY.toInt(), heightPx.toInt(), parentSize.height,
            ))
        }
        handle.pinchScale = { scale ->
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
        handle.tap = onBackgroundTap
        handle.dismiss = {
            if (mini && !dismissing && parentSize.width > 0 && parentSize.height > 0) {
                persist(dragX, dragY)
                dismissing = true
                snapJob?.cancel()
                snapJob = null
                scope.launch {
                    // Official-style exit: slide below the parent while
                    // fading out, deceleration-free (accelerating ease).
                    coroutineScope {
                        launch {
                            Animatable(dragY).animateTo(
                                parentSize.height.toFloat(),
                                tween(MiniPlayerLayout.DISMISS_MS, easing = FastOutLinearInEasing),
                            ) { dragY = value }
                        }
                        launch {
                            Animatable(1f).animateTo(
                                0f,
                                tween(MiniPlayerLayout.DISMISS_MS),
                            ) { dismissAlpha = value }
                        }
                    }
                    onDismiss()
                }
            }
        }
    }

    CompositionLocalProvider(LocalMiniPlayerHandle provides if (mini) handle else null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { if (it.width > 0 && it.height > 0) measuredParentSize = it },
        ) {
            // Skip the first frame(s) until the parent reports its size: with
            // restX/restY still 0 the window would flash at the parent's
            // top-left corner before snapping to its dock.
            if (parentSize.width > 0 && parentSize.height > 0 && viewport.width > 0 && viewport.height > 0) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { alpha = dismissAlpha }
                        .offset {
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
