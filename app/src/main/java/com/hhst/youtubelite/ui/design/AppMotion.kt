package com.hhst.youtubelite.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import kotlin.math.roundToInt
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Spatial changes share one curve; short control feedback never waits for a page transition. */
object AppMotion {
    const val WindowMs = 240
    const val ContentMs = 140
    const val ExpandMs = 160
    const val CollapseMs = 100
    const val ChromeInMs = 100
    const val ChromeOutMs = 80
    private val emphasized = android.view.animation.PathInterpolator(android.graphics.Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    })
    val Spatial = Easing { emphasized.getInterpolation(it) }
    // Retain velocity when scroll direction changes, with no spring overshoot.
    fun scrollResponse(scale: Float): FiniteAnimationSpec<Float> = if (scale <= 0f) tween(0)
        else spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 0.01f)
}

/** A panel owns its exit, including Back during entry. The host removes it only after it lands. */
@Composable
fun PanelTransition(
    fromBottom: Boolean,
    onDismiss: () -> Unit,
    backEnabled: Boolean = true,
    scrim: @Composable (Modifier, () -> Unit) -> Unit = { _, _ -> },
    content: @Composable (Modifier, () -> Unit) -> Unit,
) {
    val scale = LocalMotionScale.current
    val progress = remember { Animatable(if (scale <= 0f) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val close = { closing = true }
    LaunchedEffect(closing, scale) {
        val duration = if (closing) (120 * progress.value).roundToInt() else 180
        progress.animateTo(if (closing) 0f else 1f,
            tween(motionDuration(duration, scale), easing = if (closing) FastOutLinearInEasing else AppMotion.Spatial))
        if (closing) dismiss()
    }
    BackHandler(enabled = backEnabled || closing, onBack = close)
    scrim(Modifier.graphicsLayer { alpha = progress.value }, close)
    content(Modifier.graphicsLayer {
        if (fromBottom) translationY = (1f - progress.value) * size.height
        else translationX = (1f - progress.value) * size.width * if (rtl) -1f else 1f
    }, close)
}
