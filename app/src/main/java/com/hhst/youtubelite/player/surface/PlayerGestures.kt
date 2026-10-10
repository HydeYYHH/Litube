package com.hhst.youtubelite.player.surface

import android.os.SystemClock
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Gesture callbacks emitted by [playerGestures]. Unspecified methods are no-ops. */
interface GestureCallbacks {
    fun onTap() {}
    fun onDoubleTapSeek(offsetMs: Long) {}
    fun onDoubleTapCenter() {}
    fun onLongPressStart() {}
    fun onLongPressEnd() {}
    fun onSeekPreview(offsetMs: Long) {}
    fun onSeekCommit(offsetMs: Long) {}
    fun onBrightness(delta: Float) {}
    fun onVolume(delta: Float) {}
    fun onFullscreenSwipe(up: Boolean) {}
    fun onFullscreenSwipeProgress(distancePx: Float) {}
    fun onFullscreenSwipeCancel() {}
    /** Finger up: overlays (edge sliders) clear. */
    fun onGestureEnd() {}
    /** Fullscreen pinch: multiplicative scale factor for this event. */
    fun onZoomScale(factor: Float) {}
    /** Fullscreen two-finger pan, in px. */
    fun onZoomPan(dx: Float, dy: Float) {}
}

private const val LONG_PRESS_MS = 400L

/** Shared by the tap and drag handlers of one [playerGestures] modifier. */
private class TapGuard {
    var suppressUntil = 0L
}

/**
 * Player gesture layer:
 * tap → toggle controls; double-tap thirds → ±10 s / play-pause; long-press → 2x;
 * horizontal drag → live seek scrub; vertical drag in zones → brightness/volume;
 * center vertical swipe → fullscreen toggle.
 *
 * Every gesture is gated by [zoneEnabled] (extension prefs, embedded/fullscreen).
 */
fun Modifier.playerGestures(
    callbacks: GestureCallbacks,
    zoneEnabled: (GestureMath.GestureZone) -> Boolean = { true },
    enabled: Boolean = true,
    zoomEnabled: Boolean = false,
    /** Extra key so captured lambdas (e.g. casting) restart both handlers together. */
    restartKey: Any? = null,
    /** Shorts (vertical feed): vertical drag switches video, not brightness/volume. */
    verticalFeed: Boolean = false,
): Modifier = composed {
    val tapGuard = remember { TapGuard() }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { originInWindow = it.positionInWindow() }
        .pointerInput(enabled, callbacks, zoomEnabled, restartKey, verticalFeed) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onTap = {
                    if (SystemClock.uptimeMillis() < tapGuard.suppressUntil) return@detectTapGestures
                    if (zoneEnabled(GestureMath.GestureZone.TAP)) callbacks.onTap()
                },
                onDoubleTap = { offset ->
                    if (SystemClock.uptimeMillis() < tapGuard.suppressUntil) return@detectTapGestures
                    if (!zoneEnabled(GestureMath.GestureZone.DOUBLE_TAP)) return@detectTapGestures
                    when (GestureMath.doubleTapZone(offset.x, size.width.toFloat())) {
                        GestureMath.DoubleTapZone.LEFT ->
                            callbacks.onDoubleTapSeek(-GestureMath.DOUBLE_TAP_SEEK_MS)
                        GestureMath.DoubleTapZone.RIGHT ->
                            callbacks.onDoubleTapSeek(GestureMath.DOUBLE_TAP_SEEK_MS)
                        GestureMath.DoubleTapZone.CENTER -> callbacks.onDoubleTapCenter()
                    }
                },
            )
        }
        .pointerInput(enabled, callbacks, zoomEnabled, restartKey, verticalFeed) {
            if (!enabled) return@pointerInput
            awaitEachGesture { trackDrag(callbacks, zoneEnabled, tapGuard, zoomEnabled, verticalFeed) { originInWindow } }
        }
}

private suspend fun AwaitPointerEventScope.trackDrag(
    callbacks: GestureCallbacks,
    zoneEnabled: (GestureMath.GestureZone) -> Boolean,
    tapGuard: TapGuard,
    zoomEnabled: Boolean,
    verticalFeed: Boolean,
    windowOffset: () -> Offset,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    // A consumed down belongs to a child (time bar, chrome button): tracking
    // it here would double-drive the same finger.
    if (down.isConsumed) return
    val start = down.position
    val startInWindow = start + windowOffset()
    var lastYInWindow = startInWindow.y
    val startTime = SystemClock.uptimeMillis()
    val width = size.width.toFloat()
    val height = size.height.toFloat()

    var longPressActive = false
    /** True once the long-press deadline has been applied (hit or skipped). */
    var longPressResolved = false
    var dragMode = GestureMath.DragMode.NONE
    /** Zone the slop-crossing drag actually started in, before pref gating. */
    var rawMode = GestureMath.DragMode.NONE
    /** True once slop was crossed, even if the zone resolved to NONE. */
    var dragResolved = false
    var totalDx = 0f
    var totalDy = 0f
    var zooming = false
    var lastSpan = 0f
    var lastCx = 0f
    var lastCy = 0f

    try {
    while (true) {
        // While undecided, race the next event against the long-press timeout.
        // A non-positive timeout must not be passed to withTimeoutOrNull.
        // It returns immediately without suspending and spins the main thread
        // when long-press is disabled or after the touch slop.
        val event = if (!longPressResolved && !dragResolved && !zooming) {
            val remaining = LONG_PRESS_MS - (SystemClock.uptimeMillis() - startTime)
            if (remaining <= 0L) {
                longPressResolved = true
                if (zoneEnabled(GestureMath.GestureZone.LONG_PRESS)) {
                    longPressActive = true
                    callbacks.onLongPressStart()
                }
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            }
        } else {
            awaitPointerEvent()
        }

        if (event == null) {
            longPressResolved = true
            if (zoneEnabled(GestureMath.GestureZone.LONG_PRESS)) {
                longPressActive = true
                callbacks.onLongPressStart()
            }
            continue
        }

        val pressed = event.changes.filter { it.pressed }
        if (pressed.size >= 2) {
            callbacks.onFullscreenSwipeCancel()
            if (!zoomEnabled) return
        }
        if (pressed.size >= 2) {
            if (longPressActive) {
                longPressActive = false
                callbacks.onLongPressEnd()
            }
            dragMode = GestureMath.DragMode.NONE
            rawMode = GestureMath.DragMode.NONE
            val a = pressed[0].position
            val b = pressed[1].position
            val span = hypot(a.x - b.x, a.y - b.y)
            val cx = (a.x + b.x) / 2f
            val cy = (a.y + b.y) / 2f
            if (!zooming) {
                zooming = true
                lastSpan = span
                lastCx = cx
                lastCy = cy
            } else {
                if (lastSpan > 0f && span > 0f) callbacks.onZoomScale(span / lastSpan)
                callbacks.onZoomPan(cx - lastCx, cy - lastCy)
                lastSpan = span
                lastCx = cx
                lastCy = cy
            }
            pressed.forEach { it.consume() }
            continue
        }

        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (change.changedToUpIgnoreConsumed()) break
        if (!change.positionChanged()) continue
        if (zooming) continue

        // The surface moves during fullscreen interpolation; track the finger in window space.
        val inWindow = change.position + windowOffset()
        totalDx = inWindow.x - startInWindow.x
        totalDy = inWindow.y - startInWindow.y
        val stepDy = inWindow.y - lastYInWindow
        lastYInWindow = inWindow.y
        val slop = viewConfiguration.touchSlop

        if (longPressActive && max(abs(totalDx), abs(totalDy)) > slop) {
            // Movement cancels the 2x hold.
            longPressActive = false
            callbacks.onLongPressEnd()
        }

        if (!dragResolved && dragMode == GestureMath.DragMode.NONE) {
            if (max(abs(totalDx), abs(totalDy)) > slop) {
                if (change.isConsumed) return // a child owns this drag
                dragResolved = true
                rawMode = GestureMath.resolveDragMode(totalDx, totalDy, start.x, width, verticalFeed)
                dragMode = when (rawMode) {
                    GestureMath.DragMode.SEEK ->
                        if (zoneEnabled(GestureMath.GestureZone.SEEK)) rawMode else GestureMath.DragMode.NONE
                    GestureMath.DragMode.BRIGHTNESS ->
                        if (zoneEnabled(GestureMath.GestureZone.BRIGHTNESS)) rawMode else GestureMath.DragMode.NONE
                    GestureMath.DragMode.VOLUME ->
                        if (zoneEnabled(GestureMath.GestureZone.VOLUME)) rawMode else GestureMath.DragMode.NONE
                    GestureMath.DragMode.NONE -> GestureMath.DragMode.NONE
                }
                if (dragMode != GestureMath.DragMode.NONE) change.consume()
                if (!verticalFeed && rawMode == GestureMath.DragMode.NONE &&
                    zoneEnabled(GestureMath.GestureZone.FULLSCREEN_SWIPE)) callbacks.onFullscreenSwipeProgress(totalDy)
            }
            continue
        }

        when (dragMode) {
            GestureMath.DragMode.SEEK ->
                callbacks.onSeekPreview(GestureMath.seekOffsetMs(totalDx, width))
            GestureMath.DragMode.BRIGHTNESS ->
                callbacks.onBrightness(GestureMath.brightnessDelta(stepDy, height))
            GestureMath.DragMode.VOLUME ->
                callbacks.onVolume(GestureMath.volumeDelta(stepDy, height))
            GestureMath.DragMode.NONE -> if (!verticalFeed && rawMode == GestureMath.DragMode.NONE &&
                zoneEnabled(GestureMath.GestureZone.FULLSCREEN_SWIPE)) callbacks.onFullscreenSwipeProgress(totalDy)
        }
        change.consume()
    }

    when {
        zooming -> tapGuard.suppressUntil = SystemClock.uptimeMillis() + GestureMath.TAP_SUPPRESS_MS
        longPressActive -> {
            tapGuard.suppressUntil = SystemClock.uptimeMillis() + GestureMath.TAP_SUPPRESS_MS
            callbacks.onLongPressEnd()
            longPressActive = false
        }
        dragMode == GestureMath.DragMode.SEEK ->
            callbacks.onSeekCommit(GestureMath.seekOffsetMs(totalDx, width))
        // Fullscreen swipe (or Shorts feed switch) for center-zone / feed
        // vertical drags. A disabled brightness/volume zone must not re-route
        // into a fullscreen toggle, but a vertical feed still switches video.
        dragMode == GestureMath.DragMode.NONE && rawMode == GestureMath.DragMode.NONE &&
            (verticalFeed || zoneEnabled(GestureMath.GestureZone.FULLSCREEN_SWIPE)) &&
            abs(totalDy) > height * GestureMath.FULLSCREEN_SWIPE_MIN_FRACTION &&
            abs(totalDy) > abs(totalDx) ->
            callbacks.onFullscreenSwipe(up = totalDy < 0)
    }
    } finally {
        if (longPressActive) callbacks.onLongPressEnd()
        callbacks.onGestureEnd()
    }
}
