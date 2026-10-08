package com.hhst.youtubelite.player.surface

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.ui.YoutubeThumb

/** Coalescing window for style pushes while the user drags a control. */
private const val STYLE_PUSH_INTERVAL_MS = 500L

/**
 * Subtitle appearance editor: draggable preview for position, size slider,
 * background chips, bold, HSV palette + hex, Reset / Close.
 */
@Composable
internal fun SubtitleStyleDialog(
    style: SubtitleStyle,
    coverUrl: String?,
    onChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
    fullscreen: Boolean = false,
) {
    // onChange applies the style live AND persists it (MMKV), and a drag emits
    // one change per pixel. Keep a local draft for immediate preview, push to
    // the owner at most every STYLE_PUSH_INTERVAL_MS, and flush the final
    // draft when the dialog leaves composition so the last edit is not lost.
    var draft by remember { mutableStateOf(style) }
    var lastPushAt by remember { mutableLongStateOf(0L) }
    fun push(next: SubtitleStyle) {
        draft = next
        val now = SystemClock.uptimeMillis()
        if (now - lastPushAt >= STYLE_PUSH_INTERVAL_MS) {
            lastPushAt = now
            onChange(next)
        }
    }
    fun edit(transform: (SubtitleStyle) -> SubtitleStyle) = push(transform(draft))
    DisposableEffect(Unit) {
        onDispose { onChange(draft) }
    }
    val sliderColors = SliderDefaults.colors(
        thumbColor = PlayerUi.YtRed,
        activeTrackColor = PlayerUi.YtRed,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    PlayerModalSheet(onDismiss = onDismiss, fullscreen = fullscreen) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.subtitle_style),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
                fontSize = SettingsTokens.TitleSize.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SubtitlePositionPreview(
                style = draft,
                coverUrl = coverUrl,
                onChange = { push(it) },
                height = if (LocalConfiguration.current.screenHeightDp < 500) 100.dp else 168.dp,
            )
            Text(
                text = stringResource(R.string.subtitle_style_drag_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StyleSlider(
                label = stringResource(R.string.subtitle_style_text_size),
                valueLabel = "${(draft.textScale * 100).toInt()}%",
                value = draft.textScale,
                range = SubtitleStyle.MIN_SCALE..SubtitleStyle.MAX_SCALE,
                colors = sliderColors,
                onChange = { value -> edit { it.copy(textScale = value) } },
            )
            StyleOptionRow(
                label = stringResource(R.string.subtitle_style_background),
                options = listOf(
                    SubtitleStyle.Background.NONE to stringResource(R.string.subtitle_background_none),
                    SubtitleStyle.Background.SEMI to stringResource(R.string.subtitle_background_semi),
                    SubtitleStyle.Background.OPAQUE to stringResource(R.string.subtitle_background_opaque),
                ),
                selected = draft.background,
                onSelect = { pick -> edit { it.copy(background = pick) } },
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.subtitle_style_bold),
                    fontSize = SettingsTokens.DetailSize.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = draft.bold,
                    onCheckedChange = { checked -> edit { it.copy(bold = checked) } },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = PlayerUi.YtRed,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
            ColorPicker(
                label = stringResource(R.string.subtitle_style_color),
                selected = draft.colorArgb,
                onSelect = { argb -> edit { it.copy(colorArgb = argb) } },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { push(SubtitleStyle()) }) {
                    Text(stringResource(R.string.reset), color = PlayerUi.YtRed)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SubtitlePositionPreview(
    style: SubtitleStyle,
    coverUrl: String?,
    onChange: (SubtitleStyle) -> Unit,
    height: Dp = 168.dp,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val latest = rememberUpdatedState(style)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(boxSize) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val w = boxSize.width.toFloat().coerceAtLeast(1f)
                    val h = boxSize.height.toFloat().coerceAtLeast(1f)
                    val s = latest.value
                    onChange(
                        s.copy(
                            horizontal = (s.horizontal + dragAmount.x / w * 2f).coerceIn(-1f, 1f),
                            vertical = (s.vertical + dragAmount.y / h).coerceIn(0f, 1f),
                        ),
                    )
                }
            },
    ) {
        YoutubeThumb(
            url = coverUrl,
            modifier = Modifier.fillMaxSize(),
            contentDescription = stringResource(R.string.thumbnail),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            SubtitleOverlay(
                cues = listOf(stringResource(R.string.subtitle_style_preview)),
                style = style,
                modifier = Modifier.align(
                    BiasAlignment(
                        horizontalBias = style.horizontal.coerceIn(-1f, 1f),
                        verticalBias = style.verticalBias(),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun StyleSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: SliderColors,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = SettingsTokens.DetailSize.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = colors,
        )
    }
}

@Composable
private fun ColorPicker(
    label: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val hsv = remember(selected) {
        val out = FloatArray(3)
        AndroidColor.colorToHSV(selected, out)
        out
    }
    var hexText by remember { mutableStateOf(SubtitleStyle.formatColorHex(selected)) }
    LaunchedEffect(selected) {
        if (SubtitleStyle.parseColorHex(hexText) != selected) {
            hexText = SubtitleStyle.formatColorHex(selected)
        }
    }
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            text = label,
            fontSize = SettingsTokens.DetailSize.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SaturationValueBox(
            hue = hsv[0],
            saturation = hsv[1],
            value = hsv[2],
            onChange = { sat, value ->
                onSelect(AndroidColor.HSVToColor(floatArrayOf(hsv[0], sat, value)))
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        HueBar(
            hue = hsv[0],
            onChange = { hue ->
                onSelect(AndroidColor.HSVToColor(floatArrayOf(hue, hsv[1], hsv[2])))
            },
            modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                SubtitleStyle.parseColorHex(text)?.let(onSelect)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            label = { Text(stringResource(R.string.subtitle_style_hex)) },
            placeholder = { Text("#FFFFFF") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            trailingIcon = {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(selected))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            },
        )
    }
}

@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val thumbRadiusPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    fun applyAt(x: Float, y: Float) {
        val w = sizePx.width.toFloat().coerceAtLeast(1f)
        val h = sizePx.height.toFloat().coerceAtLeast(1f)
        onChange((x / w).coerceIn(0f, 1f), 1f - (y / h).coerceIn(0f, 1f))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged { sizePx = it }
            .pointerInput(hue) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    applyAt(down.position.x, down.position.y)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            applyAt(change.position.x, change.position.y)
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(Modifier.fillMaxSize().background(hueColor))
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
        )
        val thumbX = (saturation.coerceIn(0f, 1f) * (sizePx.width - 1).coerceAtLeast(0)).toInt()
        val thumbY = ((1f - value.coerceIn(0f, 1f)) * (sizePx.height - 1).coerceAtLeast(0)).toInt()
        Box(
            Modifier
                .offset { IntOffset(thumbX - thumbRadiusPx, thumbY - thumbRadiusPx) }
                .size(16.dp)
                .border(2.dp, Color.White, CircleShape)
                .background(Color.Transparent, CircleShape),
        )
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // pointerInput(Unit) never restarts, so applyAt must read the LATEST
    // onChange — the one captured at first composition closes over the hsv
    // array of the initial selection and would silently revert any
    // saturation/value pick made since.
    val latestOnChange = rememberUpdatedState(onChange)
    val rainbow = remember {
        (0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
    }
    val thumbHalfPx = with(LocalDensity.current) { 2.dp.roundToPx() }
    fun applyAt(x: Float) {
        val w = sizePx.width.toFloat().coerceAtLeast(1f)
        latestOnChange.value((x / w).coerceIn(0f, 1f) * 360f)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { sizePx = it }
            .background(Brush.horizontalGradient(rainbow))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    applyAt(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            applyAt(change.position.x)
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val thumbX = ((hue.coerceIn(0f, 360f) / 360f) * (sizePx.width - 1).coerceAtLeast(0)).toInt()
        Box(
            Modifier
                .offset { IntOffset(thumbX - thumbHalfPx, 0) }
                .width(4.dp)
                .fillMaxHeight()
                .background(Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

/** Label + equal-width chips; the selected chip is red-highlighted. */
@Composable
private fun <T> StyleOptionRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(
            text = label,
            fontSize = SettingsTokens.DetailSize.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = text,
                            maxLines = 1,
                            fontWeight = FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PlayerUi.YtRed.copy(alpha = 0.16f),
                        selectedLabelColor = PlayerUi.YtRed,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Color.Transparent,
                        selectedBorderColor = PlayerUi.YtRed.copy(alpha = 0.4f),
                    ),
                )
            }
        }
    }
}
