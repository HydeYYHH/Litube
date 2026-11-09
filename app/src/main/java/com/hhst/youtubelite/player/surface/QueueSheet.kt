package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.hhst.youtubelite.ui.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.hhst.youtubelite.ui.components.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.AppStyle
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.QueueOwner
import com.hhst.youtubelite.ui.YoutubeThumb

@Composable
internal fun QueueSheet(
    items: List<QueueItem>,
    currentId: String?,
    loopMode: LoopMode,
    onItem: (String) -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    onLoop: (() -> Unit)?,
    onClose: () -> Unit,
    onMove: (Int, Int, QueueOwner?) -> Unit,
    /** Queue owner at composition time; drag commits carry the owner captured at start. */
    owner: QueueOwner = QueueOwner.DEVICE,
    onDownloadAll: () -> Unit = {},
    onDownloadItem: (QueueItem) -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.HOLD)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drag = remember(listState) { QueueDragState(listState, scope, items) }
    val source = rememberUpdatedState(items)
    val move = rememberUpdatedState(onMove)
    val ownerState = rememberUpdatedState(owner)
    val density = LocalDensity.current
    LaunchedEffect(items) { drag.synchronize(items) }
    LaunchedEffect(items.isNotEmpty()) {
        val index = items.indexOfFirst { it.videoId == currentId }
        if (index >= 0) listState.scrollToItem(index)
    }
    LaunchedEffect(drag.draggingKey) {
        if (drag.draggingKey == null) return@LaunchedEffect
        val edge = with(density) { AUTO_SCROLL_EDGE_DP.dp.toPx() }
        val speed = with(density) { AUTO_SCROLL_SPEED_DP.dp.toPx() }
        var lastFrame = 0L
        while (drag.draggingKey != null) {
            val now = withFrameNanos { it }
            if (lastFrame != 0L) {
                val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                val info = listState.layoutInfo
                val y = drag.pointerY
                val fraction = when {
                    y < info.viewportStartOffset + edge ->
                        -((info.viewportStartOffset + edge - y) / edge).coerceIn(0f, 1f)
                    y > info.viewportEndOffset - edge ->
                        ((y - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (fraction != 0f) listState.dispatchRawDelta(speed * fraction * dt)
            }
            lastFrame = now
            drag.reorderVisible()
        }
    }

    Column(Modifier.padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onLoop != null) PlayerIconButton(
                icon = PlayerUi.loopIcon(loopMode),
                contentDescription = stringResource(PlayerUi.loopLabelRes(loopMode)),
                onClick = onLoop,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp),
            )
            PlayerIconButton(
                icon = R.drawable.ic_clear,
                contentDescription = stringResource(R.string.clear_queue),
                onClick = { confirmClear = true },
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp),
                enabled = items.isNotEmpty(),
            )
            PlayerIconButton(
                icon = R.drawable.ic_download,
                contentDescription = stringResource(R.string.download_queue),
                onClick = onDownloadAll,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp),
                enabled = items.isNotEmpty(),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            PlayerIconButton(R.drawable.ic_close, stringResource(R.string.close), onClose,
                tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp))
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                containerColor = AppStyle.Panel,
                title = {
                    Text(
                        stringResource(R.string.clear_queue),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.clear_queue_confirm),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        onClear()
                    }) {
                        Text(stringResource(R.string.ok), color = PlayerUi.YtRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        if (drag.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.queue_empty),
                    fontSize = SettingsTokens.BodySize.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                userScrollEnabled = drag.draggingKey == null,
                modifier = Modifier.weight(1f).testTag("queue-list")
                    .pointerInput(drag) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (drag.start(it.y, ownerState.value)) haptic()
                            },
                            onDragEnd = { drag.finish(source.value, ownerState.value, false, move.value) },
                            onDragCancel = { drag.finish(source.value, ownerState.value, true, move.value) },
                        ) { change, amount ->
                            if (drag.draggingKey != null) {
                                change.consume()
                                drag.dragBy(amount.y)
                            }
                        }
                    },
            ) {
                items(drag.items, key = { it.videoId }) { item ->
                    val raised = drag.isRaised(item.videoId)
                    val dragging = drag.draggingKey == item.videoId
                    QueueRow(
                        item = item,
                        current = item.videoId == currentId,
                        dragging = dragging,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = if (raised) null else spring(stiffness = 650f, dampingRatio = 1f),
                            )
                            .zIndex(if (raised) 1f else 0f)
                            .graphicsLayer { translationY = drag.translation(item.videoId) }
                            .testTag("queue-row-${item.videoId}"),
                        onItem = { if (drag.canTap()) { if (item.videoId == currentId) onClose() else onItem(item.url) } },
                        onRemove = { if (drag.canTap()) onRemove(item.videoId) },
                        onDownload = { if (drag.canTap()) onDownloadItem(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    current: Boolean,
    dragging: Boolean,
    modifier: Modifier,
    onItem: () -> Unit,
    onRemove: () -> Unit,
    onDownload: () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val thumbnailWidth = (maxWidth * .24f).coerceIn(72.dp, 112.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(when {
                    dragging -> MaterialTheme.colorScheme.surfaceContainer
                    current -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                })
                .clickable(onClick = onItem)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QueueThumbnail(item.thumbnailUrl, thumbnailWidth)
            Column(Modifier.weight(1f)) {
                if (current) {
                    Text(
                        text = stringResource(R.string.queue_now_playing),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = PlayerUi.YtRed,
                    )
                }
                Text(
                    text = item.title.ifBlank { item.videoId },
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.author?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = stringResource(R.string.download),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDownload)
                    .padding(8.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.queue_remove),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun QueueThumbnail(url: String?, width: Dp) {
    YoutubeThumb(
        url = url,
        modifier = Modifier
            .size(width = width, height = width * 9f / 16f)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_play),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Auto-scroll engages when the pointer is this close to the viewport edge. */
private const val AUTO_SCROLL_EDGE_DP = 56

/** Max auto-scroll speed at the very edge; ramps linearly to 0 at the band. */
private const val AUTO_SCROLL_SPEED_DP = 900
