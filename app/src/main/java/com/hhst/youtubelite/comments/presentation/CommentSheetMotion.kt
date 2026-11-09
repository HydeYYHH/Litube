package com.hhst.youtubelite.comments.presentation

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import com.hhst.youtubelite.ui.design.AppMotion
import com.hhst.youtubelite.ui.design.GestureResistance
import com.hhst.youtubelite.ui.design.GestureThreshold
import kotlin.math.roundToInt
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class CommentSheetMotion(
    private val scope: CoroutineScope,
    height: Float,
    private val density: Float,
    private val enabled: () -> Boolean,
    private val animateMotion: () -> Boolean,
    private val onDismiss: () -> Unit,
    private val onDismissStarted: () -> Unit = {},
    startHidden: Boolean = false,
    private val onThreshold: () -> Unit = {},
) : NestedScrollConnection {
    private var height = height
    var offset by mutableFloatStateOf(if (startHidden && animateMotion()) height else 0f)
        private set
    private var settling: Job? = null
    private var dismissed = false
    private var closing = false
    private var finger = 0f
    private val thresholdCue = GestureThreshold()
    private fun threshold() = minOf(height * .28f, 144f * density).coerceAtLeast(80f * density)

    fun resize(newHeight: Float) {
        if (height == newHeight) return
        val fraction = if (height > 0f) offset / height else 0f
        height = newHeight.coerceAtLeast(0f)
        offset = fraction * height
        finger = GestureResistance.fingerDistance(offset, density)
    }

    fun enter() { if (offset > 0f) settleTo(false, 0f, entering = true) }
    fun cancel() { settling?.cancel() }

    fun drag(delta: Float): Float {
        if (!enabled() || dismissed || closing) return 0f
        if (settling != null) {
            settling?.cancel()
            settling = null
            finger = GestureResistance.fingerDistance(offset, density)
            thresholdCue.reset()
        }
        val consumed = delta.coerceAtLeast(-finger)
        finger += consumed
        offset = GestureResistance.distance(finger, density).coerceAtMost(height)
        if (thresholdCue.crossed(finger, threshold())) onThreshold()
        return consumed
    }

    fun settle(velocity: Float) {
        if (!enabled() || dismissed) return
        val close = GestureResistance.commits(finger, velocity, threshold(), density)
        if (close && thresholdCue.crossed(threshold(), threshold())) onThreshold()
        settleTo(close, velocity)
    }

    fun dismiss() = settleTo(close = true, velocity = 0f)

    private fun settleTo(close: Boolean, velocity: Float, entering: Boolean = false) {
        if (dismissed || closing) return
        if (close) { closing = true; onDismissStarted() }
        settling?.cancel()
        settling = scope.launch {
            val target = if (close) 1f else 0f
            val from = if (height > 0f) offset / height else target
            if (animateMotion()) {
                animate(from, target, initialVelocity = velocity / height.coerceAtLeast(1f),
                    animationSpec = when {
                        close -> tween((120 * (1f - from)).roundToInt(), easing = FastOutLinearInEasing)
                        entering -> tween(180, easing = AppMotion.Spatial)
                        else -> spring(dampingRatio = 1f, stiffness = 500f)
                    }) {
                    value, _ -> offset = value.coerceIn(0f, 1f) * height
                }
            } else offset = target * height
            finger = 0f
            thresholdCue.reset()
            if (close) {
                dismissed = true
                onDismiss()
            }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f && offset > 0f)
            Offset(0f, drag(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y > 0f)
            Offset(0f, drag(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f || !enabled()) return Velocity.Zero
        settle(available.y)
        return Velocity(0f, available.y)
    }
}
