package com.hhst.youtubelite.player.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.ui.design.LocalMotionScale
import com.hhst.youtubelite.ui.design.motionDuration
import com.hhst.youtubelite.ui.design.GestureResistance
import com.hhst.youtubelite.ui.design.GestureThreshold
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

private const val SWIPE_TRAVEL_FRACTION = 0.4f

private const val MINI_ENTRY_DURATION_MS = 320
private const val WATCH_ENTRY_DURATION_MS = 300
// Material emphasized: decisive move, then settle without overshoot.
private val WindowEntryEasing = com.hhst.youtubelite.ui.design.AppMotion.Spatial

internal val LocalMiniPlayerHandle = compositionLocalOf<MiniPlayerHandle?> { null }
internal val LocalFullscreenSwipeHandle = compositionLocalOf<FullscreenSwipeHandle?> { null }
internal val LocalMiniTransitionActive = compositionLocalOf { false }
internal val LocalMiniChromeAlpha = compositionLocalOf<() -> Float> { { 1f } }

private data class DrawnWindowFrame(val viewport: Rect, val boundsInWindow: Rect)

private class WindowDrawRequest(val target: () -> Rect) {
    val drawn = CompletableDeferred<DrawnWindowFrame>()
}

/** The release clock survives Android relaying out the window after a rotation. */
private class WindowMotionClock(val durationMs: Int, val easing: Easing) {
    var fraction = 0f
}

class FullscreenSwipeHandle {
    var drag: (Float) -> Unit = {}
    /** Content-container pull; independent of player swipes. */
    var dragContentPull: (Float) -> Unit = {}
    var horizontal: Float = 0f
    var pictureScale by mutableFloatStateOf(1f)
    var pictureTranslationX by mutableFloatStateOf(0f)
    var pictureTranslationY by mutableFloatStateOf(0f)
    internal var pictureAspectRatio = 16f / 9f
    internal var pictureResizeMode = ResizeMode.Fit
    var end: (Boolean) -> Unit = {}
    /** Distance + velocity (px/s, y-down); true when this host consumed the release. */
    var release: (Float, Float) -> Boolean = { _, _ -> false }
    /** Same release contract for content pull; preserves orientation. */
    var releaseContentPull: (Float, Float) -> Boolean = { _, _ -> false }
}

internal class MiniPlayerHandle {
    var interacting by mutableStateOf(false)
    var collapsedSide by mutableStateOf<MiniPlayerLayout.CollapsedSide?>(null)
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
    fullscreenSwipeHandle: FullscreenSwipeHandle? = null,
    embeddedTopDp: Int = 0,
    embeddedHeightDp: Int = 0,
    embeddedLeftDp: Int = 0,
    embeddedWidthDp: Int = screenWidthDp,
    topInsetDp: Int = 0,
    /** Committed fullscreen swipe → target mode. */
    onFullscreenSwipe: (Boolean) -> Unit = {},
    /** Downward exit restores pre-fullscreen state. */
    onFullscreenSwipeRestore: () -> Unit = { onFullscreenSwipe(false) },
    onContentFullscreenPull: (Boolean) -> Unit = {},
    /** Committed embedded downward swipe → mini player. */
    onMiniSwipe: () -> Unit = {},
    onMiniDragChanged: (Boolean) -> Unit = {},
    onMiniDragProgress: (Float) -> Unit = {},
    onMiniSwipeFinished: () -> Unit = {},
    /** Local mini transition settled (incl. programmatic Back); bounds in window coords. */
    onMiniSettled: (Rect) -> Unit = {},
    /** Navigation handoff starts on destination at outgoing video bounds. */
    animateFromBounds: Rect? = null,
    /** Destination window visible; can present the collapse. */
    entryAnimationReady: Boolean = true,
    onBoundsChanged: (Rect) -> Unit = {},
    initialBounds: Rect? = null,
    entryKey: Int = 0,
    onReadyForDisplay: () -> Unit = {},
    /** Shares navigation clock with the surrounding page. */
    onEntryProgress: (Float) -> Unit = {},
    /** Shares picture geometry with the surrounding page. */
    onFullscreenProgress: (Float) -> Unit = {},
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
    // Effects must use this composition's restX/restY: a size callback can update
    // state before an effect starts while its captured dock is still zero-size.
    val parentSize = measuredParentSize
    var dragX by remember { mutableFloatStateOf(with(density) { loaded.translationXDp.dp.toPx() }) }
    var dragY by remember { mutableFloatStateOf(with(density) { loaded.translationYDp.dp.toPx() }) }
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    var pinchStartWidthDp by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Snap keeps writing dragX/dragY; a new grab must cancel the job or the
    // finish block yanks the window back to the old dock and persists it.
    var snapJob by remember { mutableStateOf<Job?>(null) }
    // Exit only after release in the close target; then onDismiss tears down
    // the surface (leaves composition — no drag/pinch/persist in between).
    var dismissing by remember { mutableStateOf(false) }
    var dragAccepted by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var collapsedSide by remember { mutableStateOf<MiniPlayerLayout.CollapsedSide?>(null) }
    var collapseCandidate by remember { mutableStateOf<MiniPlayerLayout.CollapsedSide?>(null) }
    var closeArmed by remember { mutableStateOf(false) }
    var dismissScale by remember { mutableFloatStateOf(1f) }
    var dismissAlpha by remember { mutableFloatStateOf(1f) }
    var attraction by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val displayedViewport = remember { arrayOf(Rect.Zero) }
    val windowMotionClock = remember { arrayOf<WindowMotionClock?>(null) }
    val laidOutFrame = remember { arrayOf<DrawnWindowFrame?>(null) }
    var pendingDrawRequest by remember { mutableStateOf<WindowDrawRequest?>(null) }
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    var windowPositioned by remember { mutableStateOf(false) }
    val miniEntryBounds = remember { initialBounds.takeIf { mini } }
    var previousMini by remember { mutableStateOf(mini) }
    var previousFillsWindow by remember { mutableStateOf(fillsWindow) }
    var previousParentSize by remember { mutableStateOf(parentSize) }
    var swiping by remember { mutableStateOf(false) }
    var swipeCancelled by remember { mutableStateOf(false) }
    var swipeParentSize by remember { mutableStateOf(IntSize.Zero) }
    var swipeStartedFullscreen by remember { mutableStateOf(false) }
    var swipeIsContentPull by remember { mutableStateOf(false) }
    var swipeDirection by remember { mutableFloatStateOf(1f) }
    var swipeOrigin by remember { mutableStateOf(Rect.Zero) }
    var swipeOriginScale by remember { mutableFloatStateOf(1f) }
    var swipeOriginTranslationX by remember { mutableFloatStateOf(0f) }
    var swipeOriginTranslationY by remember { mutableFloatStateOf(0f) }
    var pictureSwipe by remember { mutableStateOf(false) }
    var swipeSettling by remember { mutableStateOf(false) }
    var swipeHeightVelocity by remember { mutableFloatStateOf(0f) }
    var swipeScaleVelocity by remember { mutableFloatStateOf(0f) }
    var swipeTranslationVelocity by remember { mutableFloatStateOf(0f) }
    var swipeToMini by remember { mutableStateOf(false) }
    var awaitingFullscreen by remember { mutableStateOf<Boolean?>(null) }
    var swipeRelease by remember { mutableIntStateOf(0) }
    var transitionRunning by remember { mutableStateOf(false) }
    var transitionToMini by remember { mutableStateOf(false) }
    var miniSwipeCommitted by remember { mutableStateOf(false) }
    var miniSettlePending by remember { mutableStateOf(false) }
    var localMiniTransition by remember { mutableStateOf(false) }
    var localMiniProgress by remember { mutableFloatStateOf(0f) }
    var entryPending by remember(entryKey) { mutableStateOf(initialBounds != null && !mini || animateFromBounds != null && mini) }
    var entryProgress by remember(entryKey) { mutableFloatStateOf(if (entryPending) 0f else 1f) }
    // Animation clock can run ahead of the parent's last displayed frame; Back
    // reverses that frame, including backdrop/label opacity.
    val publishedEntryProgress = remember(entryKey) { floatArrayOf(entryProgress) }
    var entryLaidOut by remember(entryKey) { mutableStateOf(false) }
    var entryDrawn by remember(entryKey) { mutableStateOf(false) }
    var firstContentDrawn by remember(entryKey) { mutableStateOf(false) }
    val hostReady by rememberUpdatedState(entryAnimationReady)
    val currentOnMiniSettled by rememberUpdatedState(onMiniSettled)
    val motionScale = LocalMotionScale.current
    var placementRestored by remember { mutableStateOf(false) }
    val fullscreenSwipe = fullscreenSwipeHandle ?: remember { FullscreenSwipeHandle() }
    val currentOnFullscreenSwipe by rememberUpdatedState(onFullscreenSwipe)
    val currentOnFullscreenSwipeRestore by rememberUpdatedState(onFullscreenSwipeRestore)
    val currentOnContentFullscreenPull by rememberUpdatedState(onContentFullscreenPull)
    val currentOnMiniSwipe by rememberUpdatedState(onMiniSwipe)
    val swipeThreshold = remember { GestureThreshold() }
    val edgeThreshold = remember { GestureThreshold() }
    val closeThreshold = remember { GestureThreshold() }
    val displayedAttraction by animateFloatAsState(attraction,
        tween(motionDuration(100, motionScale), easing = LinearOutSlowInEasing), label = "mini-close-attraction")
    val closeTargetScale by animateFloatAsState(if (closeArmed) 64f / 56f else 1f,
        tween(motionDuration(140, motionScale), easing = WindowEntryEasing), label = "mini-close-target")

    val widthPx = with(density) { spec.widthDp.dp.toPx() }
    val heightPx = with(density) { spec.heightDp.dp.toPx() }
    val restX = if (parentSize.width > 0) {
        parentSize.width - with(density) { spec.rightMarginDp.dp.toPx() } - widthPx
    } else 0f
    val restY = if (parentSize.height > 0) {
        parentSize.height - with(density) { spec.bottomMarginDp.dp.toPx() } - heightPx
    } else 0f
    val edgeMarginPx = with(density) { spec.rightMarginDp.dp.toPx() }
    val topMarginPx = with(density) { topInsetDp.dp.toPx() } + edgeMarginPx
    val peekPx = with(density) { MiniPlayerLayout.COLLAPSED_PEEK_DP.dp.toPx() }

    fun collapsedX(side: MiniPlayerLayout.CollapsedSide) = MiniPlayerLayout.collapsedTranslationX(
        side, restX, widthPx, parentSize.width, peekPx,
    )

    fun snapMiniX(x: Float) = MiniPlayerLayout.snapX(
        x, restX.toInt(), widthPx.toInt(), parentSize.width, edgeMarginPx,
    )

    fun settledMiniX(x: Float) = collapsedSide?.let(::collapsedX) ?: snapMiniX(x)

    fun clampMiniY(y: Float) = MiniPlayerLayout.clampTranslation(
        y, restY.toInt(), heightPx.toInt(), parentSize.height, topMarginPx, edgeMarginPx,
    )

    val closeWidth = with(density) { 96.dp.toPx() }
    val closeHeight = with(density) { 72.dp.toPx() }
    val closeBottom = parentSize.height - with(density) { (bottomInsetDp + 16).dp.toPx() }
    val closeBounds = Rect((parentSize.width - closeWidth) / 2f, closeBottom - closeHeight,
        (parentSize.width + closeWidth) / 2f, closeBottom)

    fun persist(x: Float, y: Float) {
        // Persist the normal dock for restore after navigation or process death.
        store.save(spec.widthDp, snapMiniX(x) / density.density, y / density.density)
    }

    LaunchedEffect(mini, spec, parentSize, topInsetDp, density.density, windowOffset, windowPositioned) {
        if (parentSize.width <= 0 || parentSize.height <= 0 || dismissing) return@LaunchedEffect
        dragAccepted = false
        dragging = false
        closeArmed = false
        // Layout change invalidates the snap's captured width/dock; a pinch ending
        // this frame must not let an older snap completion overwrite the width.
        snapJob?.cancel()
        snapJob = null
        // Non-mini modes must not clamp the saved placement to their bounds.
        if (!mini) {
            collapsedSide = null
            return@LaunchedEffect
        }
        if (!placementRestored) {
            if (miniEntryBounds != null && !windowPositioned) return@LaunchedEffect
            val saved = store.load()
            dragX = miniEntryBounds?.let { it.left - windowOffset.x - restX } ?: (saved.translationXDp * density.density)
            dragY = miniEntryBounds?.let { it.top - windowOffset.y - restY } ?: (saved.translationYDp * density.density)
            placementRestored = true
        }
        val x = settledMiniX(dragX)
        val y = clampMiniY(dragY)
        dragX = x
        dragY = y
        // Pinch re-docks/clamps every frame but persists only when no gesture holds
        // the window — the end handler writes the final placement, not each event.
        if (mini && pinchStartWidthDp == 0) persist(x, y)
    }

    val fullscreenBounds = Rect(0f, 0f, parentSize.width.toFloat(), parentSize.height.toFloat())
    val embeddedBounds = with(density) { Rect(embeddedLeftDp.dp.toPx(), embeddedTopDp.dp.toPx(),
        (embeddedLeftDp + embeddedWidthDp).dp.toPx(),
        embeddedTopDp.dp.toPx() + embeddedHeightDp.dp.toPx()) }
    val target = if (mini) Rect(restX + dragX, restY + dragY, restX + dragX + widthPx, restY + dragY + heightPx)
        else if (fillsWindow) fullscreenBounds else embeddedBounds
    val latestTarget by rememberUpdatedState(target)
    val parentOrientation = if (parentSize == IntSize.Zero) -1 else if (parentSize.width > parentSize.height) 1 else 0
    val targetReady = target.width > 0 && target.height > 0 && parentSize != IntSize.Zero
    // Only navigation/gesture or actual rotation starts a new clock; system-bar
    // and slot updates amend the destination within that clock.
    LaunchedEffect(mini, fillsWindow, targetReady, parentOrientation, swiping, swipeRelease, fullscreenSwipeEnabled, entryKey, windowPositioned, placementRestored) {
        if (parentSize.width <= 0 || parentSize.height <= 0) return@LaunchedEffect
        // Watch publishes embedded bounds after first layout; do not consume the
        // origin or finish the page reveal against a temporary zero-height target.
        if (target.width <= 0 || target.height <= 0) return@LaunchedEffect
        if ((!mini && initialBounds != null || mini && animateFromBounds != null) && !windowPositioned) return@LaunchedEffect
        if (mini && animateFromBounds != null && (!windowPositioned || !placementRestored)) return@LaunchedEffect
        if (swiping && !mini && fullscreenSwipeEnabled && swipeParentSize == parentSize && fillsWindow == swipeStartedFullscreen) return@LaunchedEffect
        if (awaitingFullscreen != null && !mini && fullscreenSwipeEnabled && fillsWindow != awaitingFullscreen) return@LaunchedEffect
        if (swiping) swipeCancelled = true
        swiping = false
        awaitingFullscreen = null
        val changed = previousMini != mini
        val windowChanged = previousFillsWindow != fillsWindow
        // Android owns orientation; same-orientation inset settling retargets the
        // current picture transition instead.
        val parentChanged = previousParentSize != parentSize
        val sameOrientation = (previousParentSize.width > previousParentSize.height) == (parentSize.width > parentSize.height)
        val rotatedDuringMotion = parentChanged && !sameOrientation && transitionRunning &&
            previousParentSize.width > 0 && previousParentSize.height > 0
        val continuingClock = if (rotatedDuringMotion && !windowChanged && !changed) windowMotionClock[0] else null
        val oldParentSize = previousParentSize
        previousMini = mini
        previousFillsWindow = fillsWindow
        previousParentSize = parentSize
        if (changed && !mini) {
            dragging = false
            closeArmed = false
            snapJob?.cancel()
            snapJob = null
        }
        val displayed = displayedViewport[0]
        val previousFrame = if ((windowChanged || changed || rotatedDuringMotion) && transitionRunning && displayed.width > 0f && displayed.height > 0f)
            displayed else viewport
        // Live Surface stays attached: carry its visible rect into the new window
        // coords rather than snapping to the fullscreen/slot endpoint.
        val from = if (rotatedDuringMotion) Rect(
            previousFrame.left * parentSize.width / oldParentSize.width,
            previousFrame.top * parentSize.height / oldParentSize.height,
            previousFrame.right * parentSize.width / oldParentSize.width,
            previousFrame.bottom * parentSize.height / oldParentSize.height,
        ) else previousFrame
        // Animation clock can be one frame ahead of layout; reversing from that
        // future rect would first move farther in the old direction.
        if (from != viewport) viewport = from
        suspend fun awaitDrawnFrame(target: () -> Rect): DrawnWindowFrame {
            // A new request invalidates drawing even when the origin's bounds did not
            // change; it belongs to this effect, not an older entry.
            val request = WindowDrawRequest(target)
            pendingDrawRequest = request
            return try {
                request.drawn.await()
            } finally {
                if (pendingDrawRequest === request) pendingDrawRequest = null
                request.drawn.cancel()
            }
        }
        suspend fun travel(origin: Rect, duration: Int, initialSlope: Float? = null, morphBounds: Boolean = true, onProgress: (Float) -> Unit) {
            var segmentOrigin = origin
            var destination = latestTarget
            var segmentStart = 0f
            var previousProgress = 0f
            val clock = continuingClock ?: WindowMotionClock(motionDuration(duration, motionScale),
                initialSlope?.let { slope -> Easing { FullscreenSwipeMotion.settleProgress(it, slope) } } ?: WindowEntryEasing)
            windowMotionClock[0] = clock
            val startFraction = clock.fraction
            val startEased = clock.easing.transform(startFraction)
            Animatable(startFraction).animateTo(1f, tween(
                (clock.durationMs * (1f - startFraction)).roundToInt(), easing = androidx.compose.animation.core.LinearEasing,
            )) {
                // A grab takes ownership immediately, before recomposition can cancel
                // this effect on the next frame.
                if (swiping) return@animateTo
                clock.fraction = value
                val progress = (clock.easing.transform(value) - startEased) / (1f - startEased).coerceAtLeast(.00001f)
                if (destination != latestTarget) {
                    segmentOrigin = viewport
                    segmentStart = previousProgress
                    destination = latestTarget
                }
                val remaining = ((progress - segmentStart) / (1f - segmentStart).coerceAtLeast(0.00001f)).coerceIn(-.1f, 1f)
                viewport = if (morphBounds) lerp(segmentOrigin, destination, remaining) else latestTarget
                onProgress(progress)
                previousProgress = progress
            }
            if (swiping) return
            viewport = latestTarget
            windowMotionClock[0] = null
        }
        if (changed && mini) {
            entryProgress = minOf(entryProgress, publishedEntryProgress[0])
            // Keep pending across layout/inset restarts; Back uses the same live Surface
            // as a swipe, then navigates only after docking.
            miniSettlePending = true
            localMiniTransition = true
            localMiniProgress = if (swipeToMini) {
                ((embeddedBounds.width - from.width) / (embeddedBounds.width - widthPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
            } else 0f
        }
        val entering = entryPending && !fillsWindow &&
            (mini && animateFromBounds != null || !mini && initialBounds != null)
        entryPending = false
        // Insets can settle after first layout; keep a navigation collapse running
        // across that resize instead of docking it.
        if (!entering && !mini && pictureSwipe && (!swipeStartedFullscreen || fillsWindow) &&
            fullscreenSwipeEnabled && transitionRunning) {
            // Expand immediately while preserving the picture's visible origin; only
            // its pixels travel to the normal fullscreen destination.
            val oldPictureWidth = FullscreenSwipeMotion.pictureWidth(from.width, from.height,
                fullscreenSwipe.pictureAspectRatio, fullscreenSwipe.pictureResizeMode)
            val newPictureWidth = FullscreenSwipeMotion.pictureWidth(target.width, target.height,
                fullscreenSwipe.pictureAspectRatio, fullscreenSwipe.pictureResizeMode)
            val startScale = fullscreenSwipe.pictureScale * oldPictureWidth / newPictureWidth.coerceAtLeast(1f)
            val translationScaleX = if (rotatedDuringMotion) parentSize.width.toFloat() / oldParentSize.width else 1f
            val translationScaleY = if (rotatedDuringMotion) parentSize.height.toFloat() / oldParentSize.height else 1f
            val startTranslationX = fullscreenSwipe.pictureTranslationX * translationScaleX + from.center.x - target.center.x
            val startTranslation = fullscreenSwipe.pictureTranslationY * translationScaleY + from.center.y - target.center.y
            viewport = latestTarget
            fullscreenSwipe.pictureScale = startScale
            fullscreenSwipe.pictureTranslationX = startTranslationX
            fullscreenSwipe.pictureTranslationY = startTranslation
            val scaleRemaining = 1f - startScale
            val translationRemaining = -startTranslation
            val duration = FullscreenSwipeMotion.settleDuration(maxOf(kotlin.math.abs(scaleRemaining) * 2f,
                kotlin.math.abs(translationRemaining) / parentSize.height.coerceAtLeast(1)))
            val slope = if (kotlin.math.abs(translationRemaining) > 1f)
                FullscreenSwipeMotion.settleSlope(swipeTranslationVelocity, translationRemaining, motionDuration(duration, motionScale))
                else FullscreenSwipeMotion.settleSlope(swipeScaleVelocity, scaleRemaining, motionDuration(duration, motionScale))
            travel(viewport, duration, slope, morphBounds = false) { progress ->
                fullscreenSwipe.pictureScale = startScale + scaleRemaining * progress
                fullscreenSwipe.pictureTranslationX = startTranslationX * (1f - progress)
                fullscreenSwipe.pictureTranslationY = startTranslation + translationRemaining * progress
            }
        } else if (!entering && fullscreenSwipeEnabled && (!parentChanged || transitionRunning) &&
            (changed || windowChanged || transitionRunning) && from.width > 0 && from.height > 0 &&
            (from != target || fullscreenSwipe.pictureScale != 1f ||
                fullscreenSwipe.pictureTranslationX != 0f || fullscreenSwipe.pictureTranslationY != 0f)) {
            // Mode reversal starts at the displayed rect; layout changes during this
            // flight keep its clock and the original video buffer. A confirmed
            // downward exit restores the normal window on this same clock.
            transitionToMini = changed && mini
            transitionRunning = true
            val startScale = fullscreenSwipe.pictureScale
            val startTranslationX = fullscreenSwipe.pictureTranslationX
            val startTranslation = fullscreenSwipe.pictureTranslationY
            val startProgress = entryProgress
            val startMiniProgress = localMiniProgress
            if (mini && localMiniTransition) {
                // Settled Watch has entryProgress == 1 and no entry layout gate; do not
                // spend its Back clock before drawing the origin.
                snapshotFlow { hostReady }.first { it }
                awaitDrawnFrame { from }
            } else if (startProgress < 1f) snapshotFlow { entryDrawn && hostReady }.first { it }
            val gestureSettle = swipeSettling
            swipeSettling = false
            val heightRemaining = target.height - from.height
            val scaleRemaining = 1f - startScale
            val remainingFraction = maxOf(kotlin.math.abs(heightRemaining) /
                kotlin.math.abs(fullscreenBounds.height - embeddedBounds.height).coerceAtLeast(1f), kotlin.math.abs(scaleRemaining) * 2f)
            val duration = if (gestureSettle) FullscreenSwipeMotion.settleDuration(remainingFraction)
                else if (mini && animateFromBounds != null) MINI_ENTRY_DURATION_MS
                else if (windowChanged || !mini && !localMiniTransition) com.hhst.youtubelite.ui.design.AppMotion.WindowMs
                else MiniPlayerLayout.TRANSITION_MS
            val initialSlope = if (!gestureSettle) null else if (kotlin.math.abs(heightRemaining) > 1f)
                FullscreenSwipeMotion.settleSlope(swipeHeightVelocity, heightRemaining, motionDuration(duration, motionScale))
                else FullscreenSwipeMotion.settleSlope(swipeScaleVelocity, scaleRemaining, motionDuration(duration, motionScale))
            travel(from, duration, initialSlope) { progress ->
                fullscreenSwipe.pictureScale = startScale + (1f - startScale) * progress
                fullscreenSwipe.pictureTranslationX = startTranslationX * (1f - progress)
                fullscreenSwipe.pictureTranslationY = startTranslation * (1f - progress)
                // Reversing an unfinished entry must never brighten/reveal the page while
                // the video is already travelling back to mini.
                if (startProgress < 1f && !mini) entryProgress = startProgress + (1f - startProgress) * progress
                if (mini && localMiniTransition) localMiniProgress = startMiniProgress + (1f - startMiniProgress) * progress
            }
        } else if (entering) {
            transitionRunning = true
            val origin = (if (mini) requireNotNull(animateFromBounds) else requireNotNull(initialBounds))
                .translate(-windowOffset.x, -windowOffset.y)
            // Publish the origin before animateTo waits for its first frame; a layout
            // update in that gap must resume from this visible rect.
            viewport = origin
            // Layout alone can finish behind the outgoing activity; start the clock
            // only after drawing the origin in the destination window.
            snapshotFlow { entryDrawn && hostReady }.first { it }
            val duration = if (mini) MINI_ENTRY_DURATION_MS else WATCH_ENTRY_DURATION_MS
            travel(origin, duration) { entryProgress = it }
        } else viewport = target
        if (swiping) return@LaunchedEffect
        if (!mini || animateFromBounds != null) entryProgress = 1f
        if (mini && localMiniTransition) localMiniProgress = 1f
        fullscreenSwipe.pictureScale = 1f
        fullscreenSwipe.pictureTranslationX = 0f
        fullscreenSwipe.pictureTranslationY = 0f
        pictureSwipe = false
        swipeSettling = false
        transitionRunning = false
        transitionToMini = false
        val miniSettledFrame = if (mini && miniSettlePending) {
            var drawn: DrawnWindowFrame
            do {
                // Insets may retarget the dock while Android is waiting to draw; confirm
                // the latest layout without starting a new clock.
                drawn = awaitDrawnFrame { latestTarget }
                currentCoroutineContext().ensureActive()
            } while (drawn.viewport != latestTarget)
            drawn
        } else null
        // Either callback can hand the Surface to another host; both must wait for
        // the final dock to draw in this window, in their original order.
        if (mini && miniSwipeCommitted) {
            miniSwipeCommitted = false
            onMiniSwipeFinished()
        }
        if (miniSettledFrame != null) {
            miniSettlePending = false
            currentOnMiniSettled(miniSettledFrame.boundsInWindow)
        }
        if (!mini) {
            swipeToMini = false
            miniSwipeCommitted = false
            miniSettlePending = false
            localMiniTransition = false
        }
    }

    LaunchedEffect(target, parentSize, windowOffset) {
        // Idle dragging/docking still tracks layout immediately; do not overwrite
        // a mode change before its transition effect claims it.
        if (!transitionRunning && !swiping && !entryPending && previousMini == mini &&
            previousFillsWindow == fillsWindow && targetReady) {
            viewport = target
            previousParentSize = parentSize
        }
    }

    val handle = remember { MiniPlayerHandle() }
    val revealingSource = mini && animateFromBounds != null ||
        (swipeToMini || localMiniTransition) && (swiping || mini || transitionRunning)
    // Read in this composition scope: the Box below can recompose its moving
    // viewport independently; SideEffect-only reads would leave the page at
    // progress zero while the video already expands.
    val reportedEntryProgress = if (mini && !previousMini) minOf(entryProgress, publishedEntryProgress[0]) else entryProgress
    val fullscreenProgress = if (mini || revealingSource || entryProgress < 1f) 0f else
        ((viewport.height - embeddedBounds.height) / (fullscreenBounds.height - embeddedBounds.height).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val reportedMiniDragProgress = if (localMiniTransition && mini) localMiniProgress else if (revealingSource) {
        ((embeddedBounds.width - viewport.width) / (embeddedBounds.width - widthPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
    } else 0f
    SideEffect {
        publishedEntryProgress[0] = reportedEntryProgress
        onEntryProgress(reportedEntryProgress)
        onFullscreenProgress(fullscreenProgress)
        onMiniDragChanged(revealingSource)
        onMiniDragProgress(reportedMiniDragProgress)
        // Recognition uses finger travel; resistance affects only the live picture.
        fun swipeTravelPx() = (parentSize.height * SWIPE_TRAVEL_FRACTION).coerceAtLeast(1f)
        fun playerEntry() = !swipeStartedFullscreen && !swipeIsContentPull && !swipeToMini
        fun commitDistance() = if (playerEntry()) FullscreenSwipeMotion.entryThreshold(embeddedBounds.height, density.density)
            else GestureResistance.fullscreenThreshold(parentSize.height.toFloat(), density.density)
        fun cue(distance: Float) {
            if (swipeThreshold.crossed(distance, commitDistance()))
                haptics.perform(HapticsController.Event.GESTURE_THRESHOLD)
        }
        fun dragSwipe(distance: Float, contentPull: Boolean) {
            if (swipeCancelled || mini || !fullscreenSwipeEnabled || dismissing || parentSize.width <= 0 || parentSize.height <= 0) return
            if (!swiping) {
                val downward = distance >= 0f
                swiping = true
                swipeThreshold.reset()
                swipeStartedFullscreen = fillsWindow
                swipeIsContentPull = contentPull
                swipeDirection = if (distance < 0f || contentPull) -1f else 1f
                swipeOrigin = displayedViewport[0].takeIf { it.width > 0f && it.height > 0f } ?: viewport
                swipeOriginScale = fullscreenSwipe.pictureScale
                swipeOriginTranslationX = fullscreenSwipe.pictureTranslationX
                swipeOriginTranslationY = fullscreenSwipe.pictureTranslationY
                pictureSwipe = !contentPull && (!fillsWindow && !downward || fillsWindow && downward)
                swipeSettling = false
                windowMotionClock[0] = null
                swipeToMini = !fillsWindow && downward && !contentPull
                swipeParentSize = parentSize
                awaitingFullscreen = null
            }
            val directed = distance * swipeDirection
            cue(directed)
            if (swipeToMini) {
                val progress = (distance / swipeTravelPx()).coerceIn(0f, 1f)
                val width = embeddedBounds.width + (widthPx - embeddedBounds.width) * progress
                val height = embeddedBounds.height + (heightPx - embeddedBounds.height) * progress
                val center = embeddedBounds.center + Offset(fullscreenSwipe.horizontal, distance.coerceAtLeast(0f))
                viewport = Rect(center.x - width / 2f, center.y - height / 2f,
                    center.x + width / 2f, center.y + height / 2f)
            } else if (pictureSwipe) {
                viewport = swipeOrigin
                if (swipeStartedFullscreen) {
                    fullscreenSwipe.pictureScale = swipeOriginScale
                    fullscreenSwipe.pictureTranslationY = swipeOriginTranslationY + FullscreenSwipeMotion.pictureTranslation(directed, density.density)
                } else {
                    fullscreenSwipe.pictureScale = swipeOriginScale + FullscreenSwipeMotion.pictureZoom(directed, embeddedBounds.height, density.density)
                }
            } else {
                // Container expands with a content pull and contracts on a fullscreen
                // swipe along the same path; player entry keeps its zoom.
                val progress = FullscreenSwipeMotion.progress(directed, swipeTravelPx(), density.density)
                viewport = lerp(swipeOrigin, if (swipeStartedFullscreen) embeddedBounds else fullscreenBounds, progress)
                fullscreenSwipe.pictureScale = swipeOriginScale + (1f - swipeOriginScale) * progress
                fullscreenSwipe.pictureTranslationX = swipeOriginTranslationX * (1f - progress)
                fullscreenSwipe.pictureTranslationY = swipeOriginTranslationY * (1f - progress)
            }
        }
        fullscreenSwipe.drag = { dragSwipe(it, contentPull = false) }
        fullscreenSwipe.dragContentPull = { dragSwipe(-it, contentPull = true) }
        fun releaseSwipe(distance: Float, velocityPxPerSec: Float, onCommit: (Boolean) -> Unit): Boolean {
            if (swipeCancelled) {
                swipeCancelled = false
                return true
            }
            if (mini || !fullscreenSwipeEnabled || dismissing) return false
            if (!swiping) return true
            if (swipeToMini) {
                val commit = GestureResistance.commits(distance, velocityPxPerSec, commitDistance(), density.density)
                if (commit) cue(commitDistance())
                awaitingFullscreen = null
                transitionToMini = commit
                transitionRunning = true
                swiping = false
                // A move and cancellation in one frame still need a settling animation.
                swipeRelease++
                if (commit) {
                    dragX = snapMiniX(viewport.center.x - widthPx / 2f - restX)
                    dragY = clampMiniY(viewport.center.y - heightPx / 2f - restY)
                    persist(dragX, dragY)
                    miniSwipeCommitted = true
                    currentOnMiniSwipe()
                }
                return true
            }
            val directed = distance * swipeDirection
            val velocity = velocityPxPerSec * swipeDirection
            val commit = if (playerEntry()) FullscreenSwipeMotion.commitsEntry(directed, velocity, embeddedBounds.height, density.density)
                else GestureResistance.commits(directed, velocity, commitDistance(), density.density)
            if (commit) cue(commitDistance())
            awaitingFullscreen = if (commit) !swipeStartedFullscreen else null
            transitionToMini = false
            transitionRunning = true
            swipeSettling = true
            val progressVelocity = FullscreenSwipeMotion.progressVelocity(directed, velocity, swipeTravelPx(), density.density)
            swipeHeightVelocity = if (!pictureSwipe)
                ((if (swipeStartedFullscreen) embeddedBounds else fullscreenBounds).height - swipeOrigin.height) * progressVelocity else 0f
            swipeScaleVelocity = if (pictureSwipe && !swipeStartedFullscreen)
                FullscreenSwipeMotion.pictureZoomVelocity(directed, velocity, embeddedBounds.height, density.density)
                else if (pictureSwipe) 0f else (1f - swipeOriginScale) * progressVelocity
            swipeTranslationVelocity = if (pictureSwipe && swipeStartedFullscreen)
                FullscreenSwipeMotion.pictureTranslationVelocity(directed, velocity, density.density) else -swipeOriginTranslationY * progressVelocity
            swiping = false
            // A move and cancellation in one frame still need a settling animation.
            swipeRelease++
            if (commit) onCommit(!swipeStartedFullscreen)
            return true
        }
        fullscreenSwipe.release = { distance, velocity ->
            releaseSwipe(distance, velocity) { target ->
                if (swipeStartedFullscreen && swipeDirection > 0f) currentOnFullscreenSwipeRestore()
                else currentOnFullscreenSwipe(target)
            }
        }
        fullscreenSwipe.releaseContentPull = { distance, velocity ->
            releaseSwipe(distance, velocity, currentOnContentFullscreenPull)
        }
        fullscreenSwipe.end = { committed ->
            swipeCancelled = false
            if (swiping) {
                if (committed) cue(commitDistance())
                awaitingFullscreen = if (swipeToMini || !committed) null else !swipeStartedFullscreen
                transitionToMini = swipeToMini && committed
                transitionRunning = true
                swipeSettling = !swipeToMini
                swipeHeightVelocity = 0f
                swipeScaleVelocity = 0f
                swipeTranslationVelocity = 0f
                swiping = false
                swipeRelease++
                if (swipeToMini) {
                    if (committed) {
                        dragX = snapMiniX(viewport.center.x - widthPx / 2f - restX)
                        dragY = clampMiniY(viewport.center.y - heightPx / 2f - restY)
                        persist(dragX, dragY)
                        miniSwipeCommitted = true
                        currentOnMiniSwipe()
                    }
                } else if (committed) {
                    if (swipeIsContentPull) currentOnContentFullscreenPull(!swipeStartedFullscreen)
                    else if (swipeStartedFullscreen && swipeDirection > 0f) currentOnFullscreenSwipeRestore()
                    else currentOnFullscreenSwipe(!swipeStartedFullscreen)
                }
            }
        }
        // A plain tap also fires begin(): cancelling the snap here would freeze a
        // mid-flight dock (end(false) does not resume it). Cancel on the first
        // real DRAG move instead — see dragFromStart.
        handle.begin = {
            if (mini && !dismissing && !transitionRunning) {
                collapseCandidate = null
                edgeThreshold.reset()
                closeThreshold.reset()
                dragAccepted = true
                originX = dragX
                originY = dragY
                pinchStartWidthDp = spec.widthDp
            }
        }
        handle.dragFromStart = fun(dx, dy) {
            if (!mini || !dragAccepted || parentSize.width <= 0 || dismissing || transitionRunning) return
            // First drag move: cancel the in-flight snap and re-anchor origin to the
            // CURRENT mid-animation position so the drag does not jump.
            if (snapJob != null) {
                snapJob?.cancel()
                snapJob = null
                originX = dragX
                originY = dragY
            }
            dragging = true
            val rawX = originX + dx
            // Restoring a folded window stays attached to the finger.
            val resistedX = if (collapsedSide != null) rawX else GestureResistance.beyondBounds(rawX,
                -restX, (parentSize.width - widthPx - restX).coerceAtLeast(-restX), 128f * density.density)
            dragX = resistedX.coerceIn(collapsedX(MiniPlayerLayout.CollapsedSide.LEFT),
                collapsedX(MiniPlayerLayout.CollapsedSide.RIGHT))
            collapseCandidate = MiniPlayerLayout.collapseSide(restX + dragX, widthPx,
                parentSize.width, dx, dy, MiniPlayerLayout.COLLAPSE_THRESHOLD_DP * density.density)
            if (edgeThreshold.crossed(if (collapseCandidate != null) 1f else 0f, 1f))
                haptics.perform(HapticsController.Event.GESTURE_THRESHOLD)
            // Partial off-screen travel lets every window size reach the bottom target.
            dragY = (originY + dy).coerceIn(topMarginPx - restY,
                maxOf(topMarginPx - restY, closeBounds.center.y - restY - heightPx / 2))
            val hit = MiniPlayerLayout.hitsCloseTarget(restX + dragX + widthPx / 2,
                restY + dragY + heightPx / 2, closeBounds.left, closeBounds.top, closeBounds.right, closeBounds.bottom)
            if (closeThreshold.crossed(if (hit) 1f else 0f, 1f)) haptics.perform(HapticsController.Event.DROP_TARGET)
            closeArmed = hit
            val distance = (androidx.compose.ui.geometry.Offset(restX + dragX + widthPx / 2,
                restY + dragY + heightPx / 2) - closeBounds.center).getDistance()
            attraction = (1f - distance / with(density) { 128.dp.toPx() }).coerceIn(0f, 1f)
        }
        handle.pinchScale = { scale ->
            dragging = false
            closeArmed = false
            collapseCandidate = null
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
            handle.interacting = false
            attraction = 0f
            dragging = false
            closeArmed = false
            if (!mini || dismissing || transitionRunning) return
            pinchStartWidthDp = 0
            if (!moved || parentSize.width <= 0) return
            snapJob?.cancel()
            val x = settledMiniX(dragX)
            val y = clampMiniY(dragY)
            // Released drag continues toward its dock rather than stopping and
            // accelerating from rest again.
            val snapSpec = tween<Float>(motionDuration(220, motionScale), easing = LinearOutSlowInEasing)
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
        handle.cancel = { collapseCandidate = null; dragging = false; closeArmed = false; handle.end(true) }
        handle.release = {
            if (dragging && closeArmed) handle.dismiss() else {
                collapsedSide = collapseCandidate
                handle.end(true)
            }
        }
        handle.collapsedSide = collapsedSide
        handle.tap = {
            if (collapsedSide != null) {
                haptics.perform(HapticsController.Event.SELECTION)
                snapJob?.cancel()
                collapsedSide = null
                handle.end(true)
            } else onBackgroundTap()
        }
        handle.dismiss = {
            if (mini && !dismissing && parentSize.width > 0 && parentSize.height > 0) {
                dragging = false
                handle.interacting = false
                closeArmed = false
                dismissing = true
                snapJob?.cancel()
                snapJob = null
                scope.launch {
                    coroutineScope {
                        launch { Animatable(1f).animateTo(0.5f, tween(motionDuration(MiniPlayerLayout.DISMISS_MS, motionScale), easing = FastOutLinearInEasing)) { dismissScale = value } }
                        launch { Animatable(1f).animateTo(0f, tween(motionDuration(180, motionScale), easing = FastOutLinearInEasing)) { dismissAlpha = value } }
                    }
                    onDismiss()
                }
            }
        }
    }

    CompositionLocalProvider(LocalMiniPlayerHandle provides if (mini) handle else null,
        LocalMiniTransitionActive provides (revealingSource && !mini),
        LocalMiniChromeAlpha provides { if (revealingSource) ((reportedMiniDragProgress - 0.8f) / 0.2f).coerceIn(0f, 1f) else 1f },
        LocalFullscreenSwipeHandle provides if (!mini && fullscreenSwipeEnabled) fullscreenSwipe else null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { windowOffset = it.positionInWindow(); windowPositioned = true }
                .onSizeChanged { if (it.width > 0 && it.height > 0) measuredParentSize = it },
        ) {
            if (!mini && fullscreenSwipeEnabled && fullscreenProgress > 0f) {
                if (!swipeToMini) Box(Modifier.fillMaxSize().graphicsLayer { alpha = fullscreenProgress }.background(Color.Black))
            }
            AnimatedVisibility(visible = mini && dragging && !dismissing,
                modifier = Modifier.zIndex(2f).absoluteOffset { IntOffset(closeBounds.left.roundToInt(), closeBounds.top.roundToInt()) }.size(96.dp, 72.dp),
                enter = fadeIn(tween(motionDuration(100, motionScale))) + scaleIn(tween(motionDuration(160, motionScale), easing = WindowEntryEasing), initialScale = 0.8f),
                exit = fadeOut(tween(motionDuration(100, motionScale))) + scaleOut(tween(motionDuration(100, motionScale)), targetScale = 0.9f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).graphicsLayer { scaleX = closeTargetScale; scaleY = closeTargetScale }
                        .background(Color(0xCC303030), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close_player),
                        tint = Color.White.copy(alpha = if (closeArmed) 1f else 0.95f),
                        modifier = Modifier.size(32.dp))
                    }
                }
            }
            // Wait until the parent reports its size; with restX/restY still 0 the
            // window would appear at the top-left before moving to its dock.
            if (parentSize.width > 0 && parentSize.height > 0 && viewport.width > 0 && viewport.height > 0) {
                // Card entry scales from the destination layout; other modes keep native
                // aspect. SurfaceHolder fixes the video buffer independently.
                val rounding = if (localMiniTransition && mini) {
                    1f - entryProgress * (1f - localMiniProgress)
                } else if (!mini && initialBounds != null && entryProgress < 1f && !revealingSource) {
                    1f - entryProgress
                } else if (mini || revealingSource || transitionToMini) {
                    ((embeddedBounds.width - viewport.width) / (embeddedBounds.width - widthPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                } else 0f
                val corners = RoundedCornerShape((MiniPlayerLayout.CORNER_DP * rounding).dp)
                // Only card entry scales from a known destination layout; fullscreen/mini
                // release must use current native bounds before the effect starts.
                val contentSize = IntSize(embeddedBounds.width.roundToInt(), embeddedBounds.height.roundToInt())
                val scaleCardEntry = !mini && initialBounds != null && entryProgress < 1f &&
                    !fillsWindow && !revealingSource && contentSize.width > 0 && contentSize.height > 0
                val frameViewport = viewport
                val frameDrawRequest = pendingDrawRequest
                Box(
                    modifier = Modifier.absoluteOffset {
                            IntOffset(frameViewport.left.roundToInt(), frameViewport.top.roundToInt())
                        }
                        .size(with(density) { frameViewport.width.coerceAtLeast(1f).toDp() }, with(density) { frameViewport.height.coerceAtLeast(1f).toDp() })
                        .testTag("player-window")
                        .onGloballyPositioned {
                            // Viewport can recompose this child without rerunning the outer SideEffect.
                            val bounds = it.boundsInWindow()
                            laidOutFrame[0] = DrawnWindowFrame(frameViewport, bounds)
                            onBoundsChanged(bounds)
                            if (mini) com.hhst.youtubelite.navigation.WatchTransition.miniBounds = bounds
                            val laidOut = bounds.width > 0 && bounds.height > 0
                            if (laidOut && (mini && animateFromBounds != null || !mini && initialBounds != null)) entryLaidOut = true
                            if (mini && laidOut && placementRestored && !entryPending &&
                                (animateFromBounds != null && transitionRunning || viewport == target && !transitionRunning)) onReadyForDisplay()
                        }
                        .graphicsLayer {
                            alpha = dismissAlpha
                            scaleX = dismissScale * (1f - displayedAttraction * 0.35f)
                            scaleY = scaleX
                        }
                        .clip(corners)
                        .background(Color.Black)
                        .drawWithContent {
                            drawContent()
                            displayedViewport[0] = frameViewport
                            if (entryLaidOut) entryDrawn = true
                            if (!mini && !firstContentDrawn) {
                                firstContentDrawn = true
                                onReadyForDisplay()
                            }
                            val laidOut = laidOutFrame[0]
                            if (frameDrawRequest != null && pendingDrawRequest === frameDrawRequest &&
                                frameViewport == frameDrawRequest.target() && laidOut != null && laidOut.viewport == frameViewport &&
                                size.width > 0f && size.height > 0f) {
                                frameDrawRequest.drawn.complete(laidOut)
                            }
                        }
                        .then(
                            if ((mini || revealingSource) && (showStroke || revealingSource)) {
                                Modifier.border(1.dp, Color(MiniPlayerLayout.STROKE_COLOR), corners)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Box(
                        modifier = (if (scaleCardEntry) Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(with(density) { contentSize.width.coerceAtLeast(1).toDp() }, with(density) { contentSize.height.coerceAtLeast(1).toDp() })
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                val scale = viewport.width / contentSize.width.coerceAtLeast(1)
                                scaleX = scale
                                scaleY = scale
                            }
                        else Modifier.fillMaxSize()),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
