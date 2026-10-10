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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.hhst.youtubelite.ui.components.YoutubeAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.hhst.youtubelite.ui.components.YoutubeTextButton as TextButton
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
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.player.PlayerUiState

@Composable
internal fun ResizeDialog(
    selected: ResizeMode,
    onPick: (ResizeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.resize_mode),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                ResizeMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
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
                            fontSize = SettingsTokens.BodySize.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    val isSelected = row.value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(row.value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.label,
                            modifier = Modifier.weight(1f),
                            fontSize = SettingsTokens.BodySize.sp,
                            fontWeight = FontWeight.Normal,
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
                Text(stringResource(R.string.cancel))
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
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.info), text))
        onCopyHint(copiedLabel)
    }
    val autoLabel = stringResource(R.string.player_quality_auto)
    val diagnostic = state.diagnostics
    val unavailable = stringResource(R.string.info_unavailable)
    val playbackState = stringResource(when (diagnostic.playbackState) {
        "idle" -> R.string.info_state_idle
        "playing" -> R.string.info_state_playing
        "paused" -> R.string.info_state_paused
        "buffering" -> R.string.info_state_buffering
        "ended" -> R.string.info_state_ended
        "cast" -> R.string.info_state_cast
        else -> R.string.info_unavailable
    })
    val bufferCount = stringResource(R.string.info_buffer_count, diagnostic.bufferCount)
    fun kbps(value: Int?) = value?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: unavailable
    fun fps(value: Float?) = value?.let { "%.1f fps".format(it) } ?: unavailable
    val details = buildList {
        add(stringResource(R.string.info_nominal_fps) to fps(diagnostic.nominalFps))
        add(stringResource(R.string.info_rendered_fps) to fps(diagnostic.renderedFps))
        add(stringResource(R.string.info_expected_fps) to fps(diagnostic.expectedFps))
        add(stringResource(R.string.info_video_decoder) to (diagnostic.videoDecoder ?: unavailable))
        add(stringResource(R.string.info_audio_decoder) to (diagnostic.audioDecoder ?: unavailable))
        add(stringResource(R.string.info_video_codec) to (diagnostic.videoCodec ?: unavailable))
        add(stringResource(R.string.info_audio_codec) to (diagnostic.audioCodec ?: unavailable))
        add(stringResource(R.string.info_dropped_frames) to "${diagnostic.droppedFrames} / ${diagnostic.renderedFrames + diagnostic.droppedFrames} (${diagnostic.droppedRatio?.let { "%.1f%%".format(it * 100) } ?: unavailable})")
        add(stringResource(R.string.info_playback_speed) to "${state.speed}×")
        add(stringResource(R.string.info_buffer_state) to "$playbackState · ${diagnostic.bufferingMs} ms · $bufferCount")
        add(stringResource(R.string.info_first_frame) to (diagnostic.firstFrameMs?.let { "$it ms" } ?: unavailable))

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

        add(stringResource(R.string.info_resolution) to
            if ((diagnostic.width ?: 0) > 0 && (diagnostic.height ?: 0) > 0) "${diagnostic.width}×${diagnostic.height}" else unavailable)
        add(stringResource(R.string.info_bitrate) to kbps(diagnostic.videoBitrate))
        state.videoItag?.let { add(stringResource(R.string.info_itag) to it.toString()) }

        add(stringResource(R.string.info_audio_bitrate) to kbps(diagnostic.audioBitrate))
        if (state.audioSampleRate > 0) {
            add(stringResource(R.string.info_sample_rate) to "${state.audioSampleRate} Hz")
        }
        if (state.audioChannels > 0) {
            add(stringResource(R.string.info_channels) to state.audioChannels.toString())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.info),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
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
                Text(stringResource(R.string.copy_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
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
                contentDescription = "${stringResource(R.string.copy)} $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCopy)
                    .padding(14.dp),
            )
        }
        Text(
            value,
            fontSize = SettingsTokens.BodySize.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    Spacer(Modifier.height(8.dp))
}
