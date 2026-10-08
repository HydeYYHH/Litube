package com.hhst.youtubelite.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.resolve.DownloadUnavailableReason
import com.hhst.youtubelite.ui.YoutubeThumb
import org.koin.compose.koinInject

/** Real thumbnail with the placeholder box as the load-failure fallback. */
@Composable
fun DownloadThumbnail(url: String?, modifier: Modifier = Modifier) {
    YoutubeThumb(
        url = url,
        modifier = modifier
            .aspectRatio(DownloadTokens.ThumbAspect)
            .clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
fun DownloadCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    iconRes: Int = 0,
) {
    val desc = contentDescription ?: text
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = DownloadTokens.MinTouch)
            .semantics { this.contentDescription = desc },
        shape = RoundedCornerShape(DownloadTokens.Capsule),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        if (iconRes != 0) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Normal, fontSize = SettingsTokens.BodySize.sp)
    }
}

@Composable
fun DownloadFilterChip(
    selected: Boolean,
    label: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics: HapticsController = koinInject()
    Box(
        Modifier.heightIn(min = DownloadTokens.MinTouch).selectable(selected = selected, role = Role.Tab,
            onClick = { if (!selected) haptics.perform(HapticsController.Event.SELECTION); onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.heightIn(min = if (compact) 32.dp else 36.dp)
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
fun DownloadHairline() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = DownloadTokens.DIVIDER_ALPHA),
    )
}

@Composable
fun DownloadItemRow(
    item: DownloadItemUiState,
    onOpen: () -> Unit,
    onAction: (DownloadRowAction) -> Unit,
) {
    var moreExpanded by remember(item.itemId ?: item.taskId) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DownloadTokens.MinTouch)
            .clickable(onClick = onOpen)
            .padding(horizontal = DownloadTokens.PageInset, vertical = 10.dp),
    ) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadThumbnail(item.thumbnailUrl, Modifier.width(if (stacked) 80.dp else 120.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        minLines = DownloadTokens.TITLE_MAX_LINES,
                        maxLines = DownloadTokens.TITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Normal,
                        fontSize = SettingsTokens.BodySize.sp,
                        lineHeight = SettingsTokens.BodyLine.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.author.orEmpty(),
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!stacked) DownloadRowDetails(item, stacked = false)
                }
                val actions = DownloadPresentation.actionsFor(item)
                val primary = actions.firstOrNull {
                    it == DownloadRowAction.PAUSE ||
                        it == DownloadRowAction.RESUME ||
                        it == DownloadRowAction.RETRY ||
                        it == DownloadRowAction.OPEN
                }
                val primaryLabel = primary?.let { actionLabel(it) }
                val moreLabel = stringResource(R.string.download_more_actions)
                // Reserve the primary action slot even when a transition has no action.
                Box(Modifier.width(DownloadTokens.RowActionWidth).height(DownloadTokens.MinTouch)) {
                    if (primary != null && primaryLabel != null) {
                        IconButton(
                            onClick = { onAction(primary) },
                            modifier = Modifier
                                .width(DownloadTokens.RowActionWidth).height(DownloadTokens.MinTouch)
                                .semantics { contentDescription = primaryLabel },
                        ) {
                            Icon(
                                painter = painterResource(actionIcon(primary)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(DownloadTokens.Icon),
                            )
                        }
                    }
                }
                Box {
                    IconButton(
                        onClick = { moreExpanded = true },
                        modifier = Modifier
                            .width(DownloadTokens.RowActionWidth).height(DownloadTokens.MinTouch)
                            .semantics { contentDescription = moreLabel },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(DownloadTokens.Icon),
                        )
                    }
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                        modifier = Modifier.widthIn(min = 200.dp, max = 280.dp).heightIn(max = 360.dp),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        actions.forEach { action ->
                            val label = actionLabel(action)
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    Icon(painterResource(actionIcon(action)), contentDescription = null,
                                        modifier = Modifier.size(DownloadTokens.Icon))
                                },
                                onClick = {
                                    moreExpanded = false
                                    onAction(action)
                                },
                                modifier = Modifier.heightIn(min = DownloadTokens.MinTouch)
                                    .semantics { contentDescription = label },
                            )
                        }
                    }
                }
            }
            if (stacked) {
                Spacer(Modifier.height(8.dp))
                DownloadRowDetails(item, stacked = true)
            }
        }
    }
}

@Composable
private fun DownloadRowDetails(item: DownloadItemUiState, stacked: Boolean) {
    val phase = DownloadPresentation.phaseCopy(item)
    val phaseText = stringResource(phaseString(phase))
    val quality = item.qualityLabel.orEmpty()
    val size = item.expectedBytes?.let { DownloadPresentation.formatBytes(it) }.orEmpty()
    val kind = when {
        item.attachmentsOnly -> stringResource(R.string.download_kind_attachments)
        item.audioOnly -> stringResource(R.string.download_audio)
        else -> ""
    }
    val progressText = DownloadPresentation.progressText(item.status, item.progressBytes, item.expectedBytes)
    val failure = DownloadPresentation.failureReason(item)?.let { failureCopy(it) }
    Column(Modifier.fillMaxWidth()) {
        val meta = listOf(kind, quality, size).filter { it.isNotBlank() }.joinToString(" · ")
        // Preserve normal row height across status changes; stacked rows grow with their text.
        if (!stacked || meta.isNotBlank()) Text(
            text = meta,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            lineHeight = 16.sp,
        )
        Text(
            text = phaseText,
            minLines = 1,
            maxLines = if (stacked) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            lineHeight = 16.sp,
        )
        if (!stacked || failure != null || !progressText.isNullOrBlank()) Text(
            text = failure ?: progressText.orEmpty(),
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = if (failure != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            lineHeight = 16.sp,
        )
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)) {
            if (DownloadPresentation.isActivePhase(item.phase) &&
                item.status != DownloadStatus.FAILED && item.status != DownloadStatus.CANCELLED &&
                (item.status == DownloadStatus.RUNNING || item.progressBytes > 0L)) {
                val fraction = if (item.phase == DownloadPhase.TRANSFER) {
                    DownloadPresentation.progressFraction(item.progressBytes, item.expectedBytes)
                } else null
                val barModifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = barModifier,
                        trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
                } else if (item.status == DownloadStatus.RUNNING) {
                    LinearProgressIndicator(modifier = barModifier,
                        trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: DownloadRowAction): String = stringResource(
    when (action) {
        DownloadRowAction.PAUSE -> R.string.action_pause
        DownloadRowAction.RESUME -> R.string.download_resume
        DownloadRowAction.CANCEL -> R.string.cancel
        DownloadRowAction.RETRY -> R.string.retry
        DownloadRowAction.REDOWNLOAD -> R.string.download_redownload
        DownloadRowAction.OPEN -> R.string.download_open
        DownloadRowAction.SHARE -> R.string.share
        DownloadRowAction.COPY_ID -> R.string.download_copy_id
        DownloadRowAction.DELETE -> R.string.download_delete
    },
)

@Composable
private fun failureCopy(reason: String): String = when {
    reason == "ENOSPC" -> stringResource(R.string.download_reason_storage)
    reason == "ENOMEM" || reason.startsWith("Failed to allocate") -> stringResource(R.string.download_reason_memory)
    reason == "403" || reason == "http-403" -> stringResource(R.string.download_reason_forbidden)
    reason.startsWith("429:") || reason == "http-429" -> stringResource(R.string.download_reason_rate_limit)
    DownloadUnavailableReason.entries.any { it.name == reason } ->
        unavailableCopy(reason)
    else -> reason
}

private fun actionIcon(action: DownloadRowAction): Int = when (action) {
    DownloadRowAction.PAUSE -> R.drawable.ic_pause
    DownloadRowAction.RESUME -> R.drawable.ic_play
    DownloadRowAction.RETRY, DownloadRowAction.REDOWNLOAD -> R.drawable.ic_replay
    DownloadRowAction.OPEN -> R.drawable.ic_play
    DownloadRowAction.SHARE -> R.drawable.ic_share
    DownloadRowAction.COPY_ID -> R.drawable.ic_copy
    DownloadRowAction.CANCEL -> R.drawable.ic_close
    DownloadRowAction.DELETE -> R.drawable.ic_delete
}

internal fun phaseString(phase: PhaseCopy): Int = when (phase) {
    PhaseCopy.RESOLVING -> R.string.download_phase_resolve
    PhaseCopy.TRANSFERRING -> R.string.download_phase_transfer
    PhaseCopy.WAITING_PROCESS -> R.string.download_phase_waiting_process
    PhaseCopy.WAITING_NETWORK -> R.string.download_waiting_network
    PhaseCopy.MERGING -> R.string.download_phase_merging
    PhaseCopy.SAVING -> R.string.download_phase_saving
    PhaseCopy.COMPLETE -> R.string.download_complete
    PhaseCopy.FILE_NOT_FOUND -> R.string.download_file_not_found
    PhaseCopy.PAUSED -> R.string.download_paused
    PhaseCopy.FAILED -> R.string.download_failed
    PhaseCopy.CANCELLED -> R.string.download_cancelled
    PhaseCopy.QUEUED -> R.string.download_queued
}
