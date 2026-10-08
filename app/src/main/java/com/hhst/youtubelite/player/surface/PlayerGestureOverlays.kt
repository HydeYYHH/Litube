package com.hhst.youtubelite.player.surface

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.GestureUi
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HINT_VERTICAL_BIAS = -0.5f

/** Pointer-rate feedback is composed independently of the playback controls. */
@Composable
fun GestureOverlays(
    gestureState: State<GestureUi?>,
    modifier: Modifier = Modifier,
    insetDp: Int = 0,
) {
    // Read the pointer-rate state here, not in the caller: gesture updates
    // then recompose only this overlay tree.
    val g = gestureState.value ?: return
    BoxWithConstraints(modifier) {
        val side = PlayerUi.gestureSideInsetDp(maxWidth.value.roundToInt(), insetDp).dp
        when (g) {
            is GestureUi.DoubleTapSeek -> DoubleTapSeekOverlay(
                state = g,
                modifier = if (g.forward) {
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(maxWidth * .42f)
                } else {
                    Modifier.align(Alignment.CenterStart).fillMaxHeight().width(maxWidth * .42f)
                },
            )
            is GestureUi.SpeedHold -> SpeedHoldOverlay(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = maxOf(16.dp, insetDp.dp)),
            )
            is GestureUi.Scrub -> ScrubBubbleOverlay(
                state = g,
                modifier = Modifier.align(BiasAlignment(0f, HINT_VERTICAL_BIAS)),
            )
            is GestureUi.EdgeSlider -> EdgeSliderOverlay(
                state = g,
                modifier = if (g.volume) {
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .padding(start = side)
                } else {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = side)
                },
            )
        }
    }
}

/** Glass pill wrapper shared by every gesture overlay. */
@Composable
private fun GesturePill(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(PlayerUi.GlassBg, shape)
            .border(0.5.dp, PlayerUi.GlassStroke, shape)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) { content() }
}

@Composable
private fun animationsEnabled(): Boolean = Settings.Global.getFloat(
    LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

@Composable
private fun arrowPhase(forward: Boolean, animate: Boolean): Animatable<Float, AnimationVector1D> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(forward, animate) {
        phase.snapTo(0f)
        if (animate) while (true) {
            phase.animateTo(1f, tween(480, easing = LinearEasing))
            phase.snapTo(0f)
        }
    }
    return phase
}

/** Two triangles illuminate in the seek direction, with no per-frame path allocation. */
@Composable
private fun DirectionalArrows(forward: Boolean, phase: State<Float>, animate: Boolean) {
    val density = LocalDensity.current
    val paths = remember(forward, density) {
        List(2) { i -> with(density) {
            val x = (4 + i * 14).dp.toPx()
            val left = if (forward) x else x + 9.dp.toPx()
            val tip = if (forward) x + 9.dp.toPx() else x
            Path().apply {
                moveTo(left, 5.dp.toPx()); lineTo(tip, 12.dp.toPx())
                lineTo(left, 19.dp.toPx()); close()
            }
        } }
    }
    Canvas(Modifier.size(32.dp, 24.dp).semantics { contentDescription = if (forward) ">>" else "<<" }) {
        paths.forEachIndexed { index, path ->
            val order = if (forward) index else paths.lastIndex - index
            val count = paths.size.toFloat()
            val distance = (phase.value * count - order + count) % count
            val alpha = if (animate) .25f + .75f * (1f - abs(distance - .5f)).coerceIn(0f, 1f) else 1f
            drawPath(path, Color.White.copy(alpha = alpha))
        }
    }
}

/** Side oval and a fresh ripple for each tap; arrows keep running across accumulated seeks. */
@Composable
private fun DoubleTapSeekOverlay(state: GestureUi.DoubleTapSeek, modifier: Modifier = Modifier) {
    val label = PlayerUi.offsetHint(state.accumMs)
    val animate = animationsEnabled()
    val phase = arrowPhase(state.forward, animate)
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(state.forward, state.accumMs, animate) {
        if (animate) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(400, easing = LinearEasing))
        } else ripple.snapTo(1f)
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawOval(Color(0x66303030),
                topLeft = Offset(if (state.forward) size.width * .08f else -size.width * .68f, -size.height * .2f),
                size = Size(size.width * 1.6f, size.height * 1.4f))
            if (animate && ripple.value < 1f) {
                drawCircle(Color.White.copy(alpha = .14f * (1f - ripple.value)),
                    radius = minOf(size.width, size.height) * (.2f + ripple.value * .8f),
                    center = Offset(size.width * (if (state.forward) .65f else .35f), size.height / 2))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DirectionalArrows(state.forward, phase.asState(), animate)
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Long-press speed banner: steady label and flowing arrows. */
@Composable
private fun SpeedHoldOverlay(modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val phase = arrowPhase(true, animate)
    Row(modifier.background(Color.Black.copy(alpha = .72f), CircleShape)
        .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.speed_hold_2x), color = Color.White,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        DirectionalArrows(true, phase.asState(), animate)
    }
}

/** Scrub / timebar drag: target time + signed delta. */
@Composable
private fun ScrubBubbleOverlay(state: GestureUi.Scrub, modifier: Modifier = Modifier) {
    val deltaLabel = PlayerUi.offsetHint(state.deltaMs)
    GesturePill(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = PlayerUi.formatTime(state.targetMs),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = deltaLabel,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Volume/brightness: a vertical rail on the opposite edge from the
 * gesture (left for volume, right for brightness) so the hand does not
 * cover the feedback. Fill uses scaleY so the track does not relayout
 * on every percent tick.
 */
@Composable
private fun EdgeSliderOverlay(state: GestureUi.EdgeSlider, modifier: Modifier = Modifier) {
    val icon = if (state.volume) R.drawable.ic_volume else R.drawable.ic_brightness
    val percent = state.percent.coerceIn(0, 100)
    val fill = percent / 100f
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val trackHeight = (maxHeight * 0.26f).coerceIn(48.dp, 112.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(PlayerUi.GlassBg, RoundedCornerShape(16.dp))
                .border(0.5.dp, PlayerUi.GlassStroke, RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (percent > 0) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp),
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleY = fill
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .background(Color.White),
                )
            }
            Text(
                text = "$percent%",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 36.dp),
            )
        }
    }
}

/** Sponsor pre-skip countdown card: "N s → skip" with a cancel button. */
@Composable
fun SponsorCountdownCard(
    seconds: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Presence is gated by the caller (countdown != null): the composable
    // simply leaves the tree when the card hides.
    Row(
        modifier = modifier
            .background(PlayerUi.HintBg, RoundedCornerShape(12.dp))
            .clickable(onClick = onCancel)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.sponsor_skip_in, seconds),
            color = Color.White,
            fontSize = 13.sp,
        )
        Text(
            text = stringResource(R.string.cancel),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Manual chip: "Skip ▸" inside a suppressed segment, "Jump ▸" for a highlight point. */
@Composable
fun SponsorSkipChip(
    highlight: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(PlayerUi.HintBg, RoundedCornerShape(12.dp))
            .clickable(onClick = onSkip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(if (highlight) R.string.jump else R.string.sponsor_skip_chip),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            painter = painterResource(R.drawable.ic_next),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}
