package com.hhst.youtubelite.ui.design

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Attach [panelModifier] to the surface and [handleModifier] to its handle/header only.
 * Scrolling the body remains independent of dismissal; docked panels disable this gesture. */
class BottomSheetDrag internal constructor(
    private val scope: CoroutineScope,
    private val density: Float,
    private val motionScale: Float,
    private val enabled: Boolean,
    private val onDismiss: () -> Unit,
    private val onThreshold: () -> Unit,
) {
    private var offset by mutableFloatStateOf(0f)
    private var closing = false
    private var settle: Job? = null
    private var finger = 0f
    private val threshold = GestureThreshold()

    val panelModifier: Modifier get() = Modifier.graphicsLayer { translationY = offset }
    val handleModifier: Modifier get() = if (!enabled) Modifier else Modifier.pointerInput(this) {
        val velocity = VelocityTracker()
        fun restore() {
            settle?.cancel()
            settle = scope.launch {
                if (motionScale <= 0f) offset = 0f
                else animate(offset, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { value, _ -> offset = value }
            }
        }
        detectVerticalDragGestures(
            onDragStart = {
                settle?.cancel()
                finger = GestureResistance.fingerDistance(offset, density)
                threshold.reset()
                velocity.resetTracking()
            },
            onVerticalDrag = { change, amount ->
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!closing) {
                    finger = (finger + amount).coerceAtLeast(0f)
                    offset = GestureResistance.distance(finger, density)
                    if (threshold.crossed(finger, 80f * density)) onThreshold()
                }
            },
            onDragCancel = { if (!closing) restore() },
            onDragEnd = {
                if (!closing && GestureResistance.commits(finger, velocity.calculateVelocity().y, 80f * density, density)) {
                    if (threshold.crossed(80f * density, 80f * density)) onThreshold()
                    closing = true
                    onDismiss()
                } else if (!closing) restore()
            },
        )
    }
}

@Composable
fun rememberBottomSheetDrag(enabled: Boolean = true, onDismiss: () -> Unit): BottomSheetDrag {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val motion = LocalMotionScale.current
    val dismiss by rememberUpdatedState(onDismiss)
    val haptic = rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.GESTURE_THRESHOLD)
    return remember(scope, density, motion, enabled) {
        BottomSheetDrag(scope, density, motion, enabled, { dismiss() }, haptic)
    }
}
