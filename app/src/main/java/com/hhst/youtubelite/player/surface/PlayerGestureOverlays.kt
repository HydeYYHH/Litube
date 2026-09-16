package com.hhst.youtubelite.player.surface

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.GestureUi
import kotlin.math.roundToInt

private const val HINT_VERTICAL_BIAS = -0.5f

/**
 * YouTube-style per-gesture overlays: double-tap arcs with triangle +
 * accumulated seconds, the 2x hold capsule, the scrub/timebar target bubble,
 * and vertical volume/brightness rails on the opposite edge from the hand.
 *
 * All overlays share the glass look — translucent surface + hairline border —
 * instead of a heavy black slab. Each overlay joins/leaves composition with
 * the gesture state itself, so there is no separate enter/exit transition.
 */
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
                    Modifier.align(Alignment.CenterEnd).padding(end = side)
                } else {
                    Modifier.align(Alignment.CenterStart).padding(start = side)
                },
            )
            is GestureUi.SpeedHold -> SpeedHoldOverlay(
                modifier = Modifier.align(BiasAlignment(0f, HINT_VERTICAL_BIAS)),
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(PlayerUi.GlassBg, shape)
            .border(0.5.dp, PlayerUi.GlassStroke, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) { content() }
}

/** Left/right third double-tap: triangle pair + "+30s"-style accumulation. */
@Composable
private fun DoubleTapSeekOverlay(state: GestureUi.DoubleTapSeek, modifier: Modifier = Modifier) {
    val label = PlayerUi.offsetHint(state.accumMs)
    GesturePill(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!state.forward) {
                Icon(
                    painter = painterResource(R.drawable.ic_rewind),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            if (state.forward) {
                Icon(
                    painter = painterResource(R.drawable.ic_fast_forward),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Long-press hold: "2x" capsule above center, with a soft play pulse. */
@Composable
private fun SpeedHoldOverlay(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "speed-hold")
    val iconScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "speed-hold-scale",
    )
    GesturePill(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp).graphicsLayerScale(iconScale),
            )
                Text(
                    text = stringResource(R.string.speed_hold_2x),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.then(Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    })

/** Scrub / timebar drag: target time + signed delta. */
@Composable
private fun ScrubBubbleOverlay(state: GestureUi.Scrub, modifier: Modifier = Modifier) {
    val deltaLabel = PlayerUi.offsetHint(state.deltaMs)
    GesturePill(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = PlayerUi.formatTime(state.targetMs),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
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
        val trackHeight = (maxHeight * 0.38f).coerceAtLeast(72.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(PlayerUi.GlassBg, RoundedCornerShape(16.dp))
                .border(0.5.dp, PlayerUi.GlassStroke, RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 10.dp),
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
                fontWeight = FontWeight.Bold,
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
    // Presence is gated by the caller (countdown != null), so only a fade-in
    // is required; the composable is not in the tree when the card hides.
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
            fontWeight = FontWeight.Bold,
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
            fontWeight = FontWeight.Bold,
        )
        Icon(
            painter = painterResource(R.drawable.ic_next),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}
