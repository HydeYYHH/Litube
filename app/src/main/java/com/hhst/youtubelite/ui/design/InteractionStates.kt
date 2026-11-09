package com.hhst.youtubelite.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Shared press/hover/focus state layer. Pressed responds immediately; released
 * states fade back over ~120ms without scaling/moving content. Keyboard focus
 * gets its own visible layer so focus never lands invisibly.
 */
fun Modifier.appStateLayer(
    interactions: MutableInteractionSource,
    cornerRadius: Dp,
    pressedColor: Color? = null,
    hoveredColor: Color? = null,
    focusedColor: Color? = null,
): Modifier = composed {
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val palette = LocalAppColors.current
    val color by animateColorAsState(
        when {
            pressed -> pressedColor ?: palette.stateLayer
            hovered -> hoveredColor ?: palette.border
            focused -> focusedColor ?: palette.stateLayer
            else -> Color.Transparent
        },
        animationSpec = tween(if (pressed) 0 else motionDuration(120, LocalMotionScale.current)), label = "state-layer",
    )
    drawBehind {
        if (color != Color.Transparent) {
            drawRoundRect(color, cornerRadius = CornerRadius(cornerRadius.toPx()))
        }
    }
}

/** Remembers the interaction source shared by the state layer and the click handler. */
@Composable
fun rememberAppInteractions(): MutableInteractionSource = remember { MutableInteractionSource() }
