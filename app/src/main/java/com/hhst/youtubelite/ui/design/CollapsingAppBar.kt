package com.hhst.youtubelite.ui.design

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/** Follow real vertical scrolling; short pages never hide their navigation. */
@Stable
class AppBarCollapseState {
    internal var height = 0f
    internal var hidden by mutableFloatStateOf(0f)
    internal var enabled = true
    internal var animateMotion = true
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (enabled && source == NestedScrollSource.UserInput && available.y > 0f)
                hidden = (hidden - available.y).coerceAtLeast(0f)
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (enabled && source == NestedScrollSource.UserInput && consumed.y < 0f)
                hidden = (hidden - consumed.y).coerceIn(0f, height)
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val target = if (hidden > height / 2f) height else 0f
            if (enabled && hidden != target) {
                if (animateMotion) animate(hidden, target, animationSpec = spring(stiffness = 650f, dampingRatio = 1f)) {
                    value, _ -> hidden = value.coerceIn(0f, height)
                } else hidden = target
            }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberAppBarCollapseState(key: Any? = Unit, enabled: Boolean = true): AppBarCollapseState {
    val state = remember(key) { AppBarCollapseState() }
    val motion = LocalMotionScale.current
    SideEffect {
        state.enabled = enabled
        state.animateMotion = motion > 0f
        if (!enabled) state.hidden = 0f
    }
    return state
}

@Composable
fun CollapsingAppBar(state: AppBarCollapseState, content: @Composable () -> Unit) {
    Box(Modifier.clipToBounds().layout { measurable, constraints ->
        val bar = measurable.measure(constraints.copy(minHeight = 0))
        state.height = bar.height.toFloat()
        val visible = (bar.height - state.hidden).roundToInt().coerceIn(0, bar.height)
        layout(bar.width, visible) { bar.placeRelative(0, visible - bar.height) }
    }) { content() }
}
