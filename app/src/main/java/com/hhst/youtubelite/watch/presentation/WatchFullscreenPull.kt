package com.hhst.youtubelite.watch.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Velocity
import com.hhst.youtubelite.player.surface.FullscreenSwipeHandle
import com.hhst.youtubelite.player.surface.GestureMath

private class ContentPull {
    var layout: LayoutCoordinates? = null
    var pressed = false
    var eligible = false
    var active = false
    var consumeFling = false
    var distance = 0f
    val velocity = GestureMath.VelocityTracker()
}

/** Only a single-finger pull below the picture, after the list reaches its top. */
@Composable
internal fun watchFullscreenPull(
    handle: FullscreenSwipeHandle,
    enabled: Boolean,
    playerBottomPx: () -> Int,
): Modifier {
    val pull = remember(handle) { ContentPull() }
    val bottom = rememberUpdatedState(playerBottomPx)
    val connection = remember(handle, enabled) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || !pull.pressed || !pull.eligible || source != NestedScrollSource.UserInput || pull.distance <= 0f || available.y >= 0f) return Offset.Zero
            val consumed = available.y.coerceAtLeast(-pull.distance)
            pull.distance += consumed
            pull.consumeFling = pull.distance > 0f
            handle.dragContentPull(pull.distance)
            return Offset(0f, consumed)
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || !pull.pressed || !pull.eligible || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            pull.distance += available.y
            pull.active = true
            pull.consumeFling = true
            handle.horizontal = 0f
            handle.dragContentPull(pull.distance)
            return Offset(0f, available.y)
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            val consume = pull.consumeFling
            pull.consumeFling = false
            return if (consume) Velocity(0f, available.y) else Velocity.Zero
        }
    } }
    return Modifier.onGloballyPositioned { pull.layout = it }.nestedScroll(connection)
        .pointerInput(handle, enabled) {
            if (!enabled) return@pointerInput
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (!pull.pressed && pressed == 1) {
                            val change = event.changes.first { it.pressed }
                            val y = pull.layout?.localToWindow(change.position)?.y ?: 0f
                            pull.eligible = bottom.value() > 0 && y >= bottom.value()
                            pull.distance = 0f
                            pull.active = false
                            pull.consumeFling = false
                            pull.velocity.reset()
                        }
                        event.changes.firstOrNull()?.let { pull.velocity.add(it.uptimeMillis, it.position.y) }
                        if (pressed > 1) {
                            pull.eligible = false
                            if (pull.active) handle.end(false)
                            pull.active = false
                            pull.distance = 0f
                        }
                        if (pull.pressed && pressed == 0) {
                            if (pull.active) {
                                if (pull.eligible && event.changes.none { it.isConsumed })
                                    handle.releaseContentPull(-pull.distance, -pull.velocity.velocityY())
                                else handle.end(false)
                            }
                            pull.distance = 0f
                            pull.active = false
                            pull.eligible = false
                        }
                        pull.pressed = pressed > 0
                    }
                }
            } finally {
                if (pull.active) handle.end(false)
                pull.distance = 0f
                pull.active = false
                pull.pressed = false
                pull.eligible = false
            }
        }
}
