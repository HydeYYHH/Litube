package com.hhst.youtubelite.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hhst.youtubelite.ui.theme.YoutubeStyle
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = settingsAppBarHeight(),
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.background,
    ),
) = TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon,
    actions = actions, expandedHeight = expandedHeight, colors = colors)

@Composable
fun YoutubeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = YoutubeStyle.Action),
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick = onClick, modifier = modifier, enabled = enabled,
    colors = colors, shape = CircleShape, contentPadding = contentPadding, content = content)

@Composable
fun YoutubeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = YoutubeStyle.MenuShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation: Dp = 0.dp,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    properties: DialogProperties = DialogProperties(),
) = AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
    dismissButton = dismissButton, modifier = modifier, icon = icon,
    title = title?.let { { ProvideTextStyle(MaterialTheme.typography.titleLarge, it) } },
    text = text?.let { { ProvideTextStyle(MaterialTheme.typography.bodyMedium, it) } },
    shape = shape, containerColor = containerColor, tonalElevation = tonalElevation,
    titleContentColor = titleContentColor, textContentColor = textContentColor, properties = properties)

@Composable
fun YoutubeRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) =
    RadioButton(selected, onClick, modifier,
        colors = RadioButtonDefaults.colors(selectedColor = YoutubeStyle.Action,
            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))

@Composable
fun YoutubeCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) =
    Checkbox(checked, onCheckedChange, modifier, enabled,
        colors = CheckboxDefaults.colors(checkedColor = YoutubeStyle.Action,
            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant, checkmarkColor = Color.White))

@Composable
fun YoutubeLinearProgressIndicator(modifier: Modifier = Modifier, color: Color = YoutubeStyle.Action,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant) =
    LinearProgressIndicator(modifier = modifier.height(2.dp), color = color, trackColor = trackColor,
        strokeCap = StrokeCap.Butt, gapSize = 0.dp)

@Composable
fun YoutubeLinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier,
    color: Color = YoutubeStyle.Action, trackColor: Color = MaterialTheme.colorScheme.outlineVariant) =
    LinearProgressIndicator(progress = progress, modifier = modifier.height(2.dp), color = color,
        trackColor = trackColor, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})

/** Web-sized pill and thumb, with an independent 48 dp touch area. */
@Composable
fun YoutubeSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fraction by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = (if (checked) YoutubeStyle.Action else MaterialTheme.colorScheme.onSurfaceVariant)
        .copy(alpha = if (enabled) 1f else .38f)
    Box(modifier.size(48.dp).then(if (onCheckedChange == null) Modifier else
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch,
            onValueChange = onCheckedChange)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp, 20.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            val radius = 8.dp.toPx()
            drawCircle(Color.White.copy(alpha = if (enabled) 1f else .38f), radius,
                Offset(10.dp.toPx() + (if (rtl) 1f - fraction else fraction) * 16.dp.toPx(), size.height / 2))
        }
    }
}

/** Thin track and round thumb. Gestures and accessibility remain on the platform control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(thumbColor = YoutubeStyle.Action,
        activeTrackColor = YoutubeStyle.Action, inactiveTrackColor = MaterialTheme.colorScheme.outline)) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val opacity = if (enabled) 1f else .38f
    Slider(value, onValueChange, modifier, enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = onValueChangeFinished, colors = colors,
        thumb = { Box(Modifier.size(12.dp).background(colors.thumbColor.copy(alpha = opacity), CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val fraction = ((state.value - valueRange.start) /
                    (valueRange.endInclusive - valueRange.start).coerceAtLeast(.001f)).coerceIn(0f, 1f)
                drawRect(colors.inactiveTrackColor.copy(alpha = opacity))
                drawRect(colors.activeTrackColor.copy(alpha = opacity),
                    topLeft = Offset(if (rtl) size.width * (1 - fraction) else 0f, 0f),
                    size = Size(size.width * fraction, size.height))
            }
        })
}

@Composable
fun YoutubeSheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f), RoundedCornerShape(2.dp)))
    }
}
