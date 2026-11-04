package com.hhst.youtubelite.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.home.presentation.LoadingSpinner

/** Shared edge gesture. Only unconsumed downward scroll can start a pull. */
@Stable
class PullRefreshState internal constructor(private val threshold: Float) {
    var offset by mutableFloatStateOf(0f)
        internal set
    internal var pressed by mutableStateOf(false)
    internal var cancelled = false
    internal var release by mutableIntStateOf(0)
    internal var pending by mutableStateOf(false)
    internal var enabled = true
    internal var refreshing by mutableStateOf(false)
    internal var canPull: () -> Boolean = { true }
    val progress: Float get() = (offset / threshold).coerceIn(0f, 1f)
    val busy: Boolean get() = refreshing || pending

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || busy || !enabled) return Offset.Zero
            if (available.y < 0f && offset > 0f) {
                val consumed = available.y.coerceAtLeast(-offset)
                offset += consumed
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || !pressed || cancelled || !enabled || busy || !canPull() || available.y <= 0f) return Offset.Zero
            // Increasing resistance keeps a long drag bounded without a hard stop.
            val resistance = 0.5f * (1f - offset / (threshold * 2.5f)).coerceAtLeast(0f)
            offset = (offset + available.y * resistance).coerceAtMost(threshold * 2.5f)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (offset > 0f) Velocity(0f, available.y) else Velocity.Zero
    }

    internal suspend fun settle(target: Float) {
        Animatable(offset).animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow)) { offset = value }
        offset = target
    }
}

@Composable
fun rememberPullRefreshState(
    refreshing: Boolean,
    enabled: Boolean = true,
    canPull: () -> Boolean = { true },
    onRefresh: () -> Unit,
): PullRefreshState {
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val motion = remember(threshold) { PullRefreshState(threshold) }
    val latestRefresh by rememberUpdatedState(onRefresh)
    SideEffect {
        motion.enabled = enabled
        motion.refreshing = refreshing
        motion.canPull = canPull
    }
    val haptic = rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.GESTURE_THRESHOLD)
    val thresholdCue = remember(motion) { GestureThreshold() }
    val armed = motion.progress >= 1f
    LaunchedEffect(armed, motion.pressed) {
        if (!motion.pressed) thresholdCue.reset()
        else if (!motion.busy && thresholdCue.crossed(motion.progress, 1f)) haptic()
    }
    var handledRelease by remember { mutableIntStateOf(0) }
    LaunchedEffect(motion.pressed, motion.release, refreshing, enabled) {
        if (motion.pressed && enabled && !refreshing) return@LaunchedEffect
        val released = handledRelease != motion.release
        handledRelease = motion.release
        if (released && !motion.cancelled && enabled && !refreshing && motion.progress >= 1f) {
            motion.pending = true
            try {
                latestRefresh()
                // Allow even synchronous repositories to publish their result before settling.
                withFrameNanos { }
                withFrameNanos { }
            } finally {
                motion.pending = false
            }
        }
        motion.settle(if (refreshing) threshold else 0f)
    }
    return motion
}

fun Modifier.pullRefreshGesture(state: PullRefreshState): Modifier =
    nestedScroll(state.connection).pointerInput(state) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.any { it.pressed }
                    if (pressed && !state.pressed) state.cancelled = !state.enabled || state.busy
                    if (!pressed && state.pressed) {
                        // Eligibility belongs to this gesture, not a later recomposition: a
                        // busy pull must stay rejected if the request finishes on release.
                        state.cancelled = state.cancelled || !state.enabled || state.busy ||
                            event.changes.any { it.previousPressed && it.isConsumed }
                        state.release++
                    }
                    state.pressed = pressed
                }
            }
        } finally {
            state.cancelled = true
            state.pressed = false
        }
    }

@Composable
fun BoxScope.PullRefreshIndicator(state: PullRefreshState, tag: String, anchor: Dp = 0.dp) {
    if (state.offset <= 0f && !state.busy) return
    Surface(Modifier.align(Alignment.TopCenter)
        .graphicsLayer {
            translationY = anchor.toPx() + ((state.offset - 40.dp.toPx()) / 2f).coerceAtLeast(4.dp.toPx())
            alpha = if (state.busy) 1f else state.progress
            val scale = 0.65f + 0.35f * state.progress
            scaleX = scale
            scaleY = scale
        }.size(40.dp).testTag(tag), shape = CircleShape,
        color = DesignTokens.Background, shadowElevation = 3.dp) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Crossfade(state.busy, animationSpec = tween(120), label = "refresh-indicator") { busy ->
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    if (busy) LoadingSpinner()
                    else AppIcon(AppIcon.Refresh, Modifier.size(24.dp).graphicsLayer {
                        rotationZ = state.progress * 180f
                    })
                }
            }
        }
    }
}

/** Keeps the last painted content during a refresh so lists and their scroll positions survive. */
@Composable
fun <T> RefreshableContent(
    value: T,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentKey: Any? = Unit,
    tag: String = "refresh",
    canPull: () -> Boolean = { true },
    content: @Composable BoxScope.(T) -> Unit,
) {
    var retained by remember(contentKey) { mutableStateOf(value) }
    // Retention applies only to a user pull; query/account/tab loads keep their normal skeletons.
    var retaining by remember(contentKey) { mutableStateOf(false) }
    val pullMotion = key(contentKey) {
        rememberPullRefreshState(refreshing && retaining, enabled && !refreshing, canPull) {
            retaining = true
            onRefresh()
        }
    }
    SideEffect { if (!refreshing) retained = value }
    LaunchedEffect(refreshing, retaining) {
        if (!refreshing && retaining) {
            withFrameNanos { }
            withFrameNanos { }
            retaining = false
        }
    }
    Box(modifier.clipToBounds().pullRefreshGesture(pullMotion)
        // A zero-consuming scroll host lets empty and error pages participate as well.
        .scrollable(rememberScrollableState { 0f }, Orientation.Vertical, enabled = enabled)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = pullMotion.offset }) {
            content(if (refreshing && retaining) retained else value)
        }
        PullRefreshIndicator(pullMotion, "$tag-indicator")
    }
}
