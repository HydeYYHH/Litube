package com.hhst.youtubelite.player.surface

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.PlayerUiState

@Composable
internal fun ResizeDialog(
    selected: ResizeMode,
    onPick: (ResizeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.resize_mode),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                ResizeMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onPick(mode) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(mode.iconRes()),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(24.dp),
                        )
                        Text(
                            text = stringResource(mode.labelRes()),
                            modifier = Modifier.weight(1f),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
internal fun OptionDialog(
    title: String,
    rows: List<OptionRow<String>>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    val isSelected = row.value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onPick(row.value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.label,
                            modifier = Modifier.weight(1f),
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
internal fun InfoDialog(state: PlayerUiState, onDismiss: () -> Unit, onCopyHint: (String) -> Unit) {
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.copied)
    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("info", text))
        onCopyHint(copiedLabel)
    }
    fun kbps(v: Int): String = if (v > 0) "${v / 1000} kbps" else "-"
    val autoLabel = stringResource(R.string.player_quality_auto)
    val details = buildList {
        add(stringResource(R.string.player_info_title) to state.title)
        state.author?.let { add(stringResource(R.string.player_info_author) to it) }
        add(
            stringResource(R.string.player_info_quality) to
                (state.qualityLabel
                    ?: PlayerUi.playingQuality(state.activeQuality, state.videoHeight)
                    ?: autoLabel),
        )
        add(stringResource(R.string.player_info_duration) to PlayerUi.formatTime(state.durationMs))
        if (state.isLive) add(stringResource(R.string.player_info_type) to stringResource(R.string.player_live))
        state.videoId?.let { add(stringResource(R.string.player_info_id) to it) }
        state.videoCodec?.let { add(stringResource(R.string.info_video_codec) to it) }
        if (state.videoWidth > 0 && state.videoHeight > 0) {
            val fps = if (state.videoFps > 0) "@${state.videoFps}fps" else ""
            add(
                stringResource(R.string.info_resolution) to
                    "${state.videoWidth}x${state.videoHeight}$fps",
            )
        }
        if (state.videoBitrate > 0) add(stringResource(R.string.info_bitrate) to kbps(state.videoBitrate))
        state.videoItag?.let { add(stringResource(R.string.info_itag) to it.toString()) }
        state.audioCodec?.let { add(stringResource(R.string.info_audio_codec) to it) }
        if (state.audioBitrate > 0) add(stringResource(R.string.info_audio_bitrate) to kbps(state.audioBitrate))
        if (state.audioSampleRate > 0) {
            add(stringResource(R.string.info_sample_rate) to "${state.audioSampleRate} Hz")
        }
        if (state.audioChannels > 0) {
            add(stringResource(R.string.info_channels) to state.audioChannels.toString())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.info),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                details.forEach { (label, value) ->
                    InfoLine(label = label, value = value, onCopy = { copy("$label: $value") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copy(details.joinToString("\n") { "${it.first}: ${it.second}" })
            }) {
                Text(stringResource(R.string.copy_all), color = PlayerUi.YtRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String, onCopy: () -> Unit = {}) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = stringResource(R.string.copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCopy)
                    .padding(6.dp),
            )
        }
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    Spacer(Modifier.height(8.dp))
}
