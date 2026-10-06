package com.hhst.youtubelite.player.surface

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.player.PlayerUiState
import kotlinx.coroutines.delay

/** Live Google Cast intro; the old chromecast/answer/3006766 article 404s. */
private const val CAST_LEARN_URL = "https://support.google.com/googlecast/answer/6102923"

@Composable
internal fun CastDialog(
    state: PlayerUiState,
    onDevice: (String) -> Unit,
    onStop: () -> Unit,
    onShareLink: () -> Unit,
    onCloseLink: () -> Unit,
    onRefresh: () -> Unit,
    onDiscovery: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Discovery scanning is battery-hungry: run it only while this dialog is
    // open, and keep the 1.5 s route re-poll for OEM ROMs that drop
    // discovery callbacks (the first scan can take several seconds).
    DisposableEffect(Unit) {
        onDiscovery(true)
        onDispose { onDiscovery(false) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(1_500)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.cast),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.casting) {
                    Text(
                        text = stringResource(
                            R.string.casting_to,
                            state.castingDeviceName ?: stringResource(R.string.cast),
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = SettingsTokens.BodySize.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.cast_stop), color = PlayerUi.YtRed)
                    }
                }
                Text(
                    text = stringResource(R.string.cast_section_devices),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                if (state.castDevices.isEmpty()) {
                    Text(
                        text = if (state.castAvailable) {
                            stringResource(R.string.cast_no_devices_found)
                        } else {
                            stringResource(R.string.cast_unavailable)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = SettingsTokens.DetailSize.sp,
                    )
                    Text(
                        text = if (state.castAvailable) {
                            stringResource(R.string.cast_no_devices_hint)
                        } else {
                            stringResource(R.string.cast_unavailable_hint)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LearnAboutCastingLink(
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                } else {
                    state.castDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable { onDevice(device.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (device.selected) R.drawable.ic_cast_connected else R.drawable.ic_cast,
                                ),
                                contentDescription = null,
                                tint = if (device.selected) {
                                    PlayerUi.YtRed
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(22.dp),
                            )
                            Text(
                                text = device.name,
                                color = if (device.selected) {
                                    PlayerUi.YtRed
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                // Link-cast is a separate mode from a Chromecast session: the
                // proxy serves the receiver's manifest during one, so the link
                // section (and with it the reconfigure path) is hidden here.
                if (!state.casting) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.cast_section_link),
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    Text(
                        text = stringResource(R.string.cast_section_link_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    state.castLinkUrl?.let { url ->
                        Text(
                            text = url,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.castLinkConnected) {
                            Text(
                                text = stringResource(R.string.cast_link_connected),
                                color = PlayerUi.YtRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = onShareLink) {
                            Text(stringResource(R.string.share), color = MaterialTheme.colorScheme.primary)
                        }
                        if (state.castLinkUrl != null) {
                            TextButton(onClick = onCloseLink) {
                                Text(
                                    stringResource(R.string.cast_link_close),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun LearnAboutCastingLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, CAST_LEARN_URL.toUri()),
                )
            }
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.cast_learn_casting),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
