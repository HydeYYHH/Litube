package com.hhst.youtubelite.comments.presentation

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
    private val height: Float,
    private val density: Float,
    private val enabled: () -> Boolean,
    private val animateMotion: () -> Boolean,
    private val onDismiss: () -> Unit,
    private val onDismissStarted: () -> Unit = {},
) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set
    private var settling: Job? = null
    private var dismissed = false

    fun drag(delta: Float): Float {
        if (!enabled() || dismissed) return 0f
        settling?.cancel()
        val before = offset
        offset = (offset + delta).coerceIn(0f, height)
        return offset - before
    }

    fun settle(velocity: Float) {
        if (!enabled() || dismissed) return
        val close = offset >= minOf(height * .28f, 144f * density) ||
            (offset > 16f * density && velocity > 1200f * density)
        settleTo(close, velocity)
    }

    fun dismiss() = settleTo(close = true, velocity = 0f)

    private fun settleTo(close: Boolean, velocity: Float) {
        if (dismissed) return
        if (close) onDismissStarted()
        settling?.cancel()
        settling = scope.launch {
            val target = if (close) height else 0f
            if (animateMotion()) {
                animate(offset, target, initialVelocity = velocity,
                    animationSpec = if (close) tween(180) else spring(dampingRatio = 1f, stiffness = 500f)) {
                    value, _ -> offset = value.coerceIn(0f, height)
                }
            } else offset = target
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
