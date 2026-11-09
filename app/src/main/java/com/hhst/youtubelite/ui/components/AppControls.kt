package com.hhst.youtubelite.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.window.Dialog
import com.hhst.youtubelite.ui.theme.AppStyle
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopAppBar(
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
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = AppStyle.Action),
    content: @Composable RowScope.() -> Unit,
) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    TextButton(onClick = { haptic(); onClick() }, modifier = modifier, enabled = enabled,
        colors = colors, shape = CircleShape, contentPadding = contentPadding, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AppStyle.MenuShape,
    containerColor: Color = AppStyle.Panel,
    tonalElevation: Dp = 0.dp,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest, properties = properties) {
        BoxWithConstraints {
            Surface(modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = shape, color = containerColor, tonalElevation = tonalElevation) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                    CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                        icon?.let { Box(Modifier.padding(bottom = 8.dp).align(Alignment.CenterHorizontally)) { it() } }
                        title?.let {
                            Box(Modifier.padding(bottom = if (text != null) 8.dp else 0.dp)) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, it)
                            }
                        }
                    }
                    text?.let {
                        Box(Modifier.weight(1f, fill = false)) {
                            CompositionLocalProvider(LocalContentColor provides textContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, it)
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Composable
fun AppRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    RadioButton(selected, onClick?.let { { if (!selected) haptic(); it() } }, modifier,
        colors = RadioButtonDefaults.colors(selectedColor = AppStyle.Action,
            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
fun AppCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    Checkbox(checked, onCheckedChange?.let { callback -> { next -> haptic(); callback(next) } }, modifier, enabled,
        colors = CheckboxDefaults.colors(checkedColor = AppStyle.Action,
            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant, checkmarkColor = AppStyle.OnAction))
}

@Composable
fun AppLinearProgressIndicator(modifier: Modifier = Modifier, color: Color = AppStyle.Action,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant) =
    LinearProgressIndicator(modifier = modifier.height(2.dp), color = color, trackColor = trackColor,
        strokeCap = StrokeCap.Butt, gapSize = 0.dp)

@Composable
fun AppLinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier,
    color: Color = AppStyle.Action, trackColor: Color = MaterialTheme.colorScheme.outlineVariant) =
    LinearProgressIndicator(progress = progress, modifier = modifier.height(2.dp), color = color,
        trackColor = trackColor, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})

/** Web-sized pill and thumb, with an independent 48 dp touch area. */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    // With system animations off the thumb snaps instead of gliding.
    val fraction by animateFloatAsState(if (checked) 1f else 0f,
        animationSpec = if (com.hhst.youtubelite.ui.design.LocalMotionScale.current > 0f) spring() else snap(), label = "switch")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = (if (checked) AppStyle.Action else MaterialTheme.colorScheme.onSurfaceVariant)
        .copy(alpha = if (enabled) 1f else .38f)
    val thumb = if (checked) AppStyle.OnAction else Color.White
    Box(modifier.size(48.dp).then(if (onCheckedChange == null) Modifier else
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch,
            onValueChange = { haptic(); onCheckedChange(it) })), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp, 20.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            val radius = 8.dp.toPx()
            drawCircle(thumb.copy(alpha = if (enabled) 1f else .38f), radius,
                Offset(10.dp.toPx() + (if (rtl) 1f - fraction else fraction) * 16.dp.toPx(), size.height / 2))
        }
    }
}

/** Gestures and accessibility remain on the platform control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(thumbColor = AppStyle.Action,
        activeTrackColor = AppStyle.Action, inactiveTrackColor = MaterialTheme.colorScheme.outline)) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val opacity = if (enabled) 1f else .38f
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    var changed by remember { mutableStateOf(false) }
    Slider(value, { changed = true; onValueChange(it) }, modifier, enabled, valueRange = valueRange, steps = steps,
        onValueChangeFinished = {
            if (changed) haptic()
            changed = false
            onValueChangeFinished?.invoke()
        }, colors = colors,
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
fun AppSheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .15f), RoundedCornerShape(2.dp)))
    }
}
