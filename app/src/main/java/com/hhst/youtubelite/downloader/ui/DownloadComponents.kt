package com.hhst.youtubelite.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R

@Composable
fun DownloadThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(DownloadTokens.ThumbAspect)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun DownloadCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
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
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DownloadFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = DownloadTokens.MinTouch),
        shape = RoundedCornerShape(DownloadTokens.Capsule),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = DownloadTokens.DIVIDER_ALPHA),
        ),
    )
}

@Composable
fun DownloadHairline() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = DownloadTokens.DIVIDER_ALPHA),
    )
}

@Composable
fun DownloadedBadge(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val downloaded = stringResource(R.string.download_downloaded)
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(DownloadTokens.Capsule))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = downloaded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_download),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(DownloadTokens.Icon),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.download_downloaded),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

@Composable
fun DownloadWatchEntry(
    downloaded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloaded) {
        DownloadedBadge(modifier = modifier, onClick = onClick)
    } else {
        val download = stringResource(R.string.download)
        Row(
            modifier = modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(DownloadTokens.Capsule))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp)
                .semantics { contentDescription = download },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(DownloadTokens.Icon),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.download),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
fun DownloadItemRow(
    item: DownloadItemUiState,
    onOpen: () -> Unit,
    onAction: (DownloadRowAction) -> Unit,
) {
    val phase = DownloadPresentation.phaseCopy(item)
    val phaseText = stringResource(phaseString(phase))
    val quality = item.qualityLabel.orEmpty()
    val size = item.expectedBytes?.let { DownloadPresentation.formatBytes(it) }.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DownloadTokens.MinTouch)
            .clickable(onClick = onOpen)
            .padding(horizontal = DownloadTokens.PageInset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadThumbnail(Modifier.width(120.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                maxLines = DownloadTokens.TITLE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.author?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = listOf(quality, size).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    maxLines = 1,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                )
            }
            Text(
                text = phaseText,
                maxLines = 1,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        val primary = DownloadPresentation.actionsFor(item).firstOrNull {
            it == DownloadRowAction.PAUSE ||
                it == DownloadRowAction.RESUME ||
                it == DownloadRowAction.RETRY ||
                it == DownloadRowAction.OPEN
        }
        val primaryLabel = primary?.let { actionLabel(it) }
        val moreLabel = stringResource(R.string.download_more_actions)
        if (primary != null && primaryLabel != null) {
            IconButton(
                onClick = { onAction(primary) },
                modifier = Modifier
                    .size(DownloadTokens.MinTouch)
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
        IconButton(
            onClick = { onAction(DownloadRowAction.DELETE) },
            modifier = Modifier
                .size(DownloadTokens.MinTouch)
                .semantics { contentDescription = moreLabel },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(DownloadTokens.Icon),
            )
        }
    }
}

@Composable
internal fun actionLabel(action: DownloadRowAction): String = stringResource(
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

internal fun actionIcon(action: DownloadRowAction): Int = when (action) {
    DownloadRowAction.PAUSE -> R.drawable.ic_pause
    DownloadRowAction.RESUME -> R.drawable.ic_play
    DownloadRowAction.RETRY, DownloadRowAction.REDOWNLOAD -> R.drawable.ic_replay
    DownloadRowAction.OPEN -> R.drawable.ic_play
    DownloadRowAction.SHARE -> R.drawable.ic_share
    DownloadRowAction.COPY_ID -> R.drawable.ic_copy
    DownloadRowAction.CANCEL, DownloadRowAction.DELETE -> R.drawable.ic_close
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
