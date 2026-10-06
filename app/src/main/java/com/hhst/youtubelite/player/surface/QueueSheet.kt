package com.hhst.youtubelite.player.surface

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.ui.YoutubeThumb

@Composable
internal fun QueueSheet(
    items: List<QueueItem>,
    currentId: String?,
    enabled: Boolean,
    loopMode: LoopMode,
    onItem: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    onLoop: () -> Unit,
    onClose: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onDownloadAll: () -> Unit = {},
    onDownloadItem: (QueueItem) -> Unit = {},
) {
    // Working copy: drag reorders locally and commits once per drop, so the
    // persisted queue is not rewritten on every animation frame. pointerInput
    // coroutines capture these once and never see a replacement, so the state
    // object is created once and re-synced (not re-created) when items change,
    // and the persisted list is read through rememberUpdatedState.
    var localItems by remember { mutableStateOf(items) }
    val persistedItems = rememberUpdatedState(items)
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // While a row is under the finger, external queue updates (e.g. the
    // playing item's metadata upsert) must not clobber the working copy.
    // The drag start index is resolved live so the commit below can map the
    // local reorder back onto the persisted list by id.
    var dragInProgress by remember { mutableStateOf(false) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    // Auto-scroll inputs fed by the dragged row: which item is under the
    // drag, and the pointer's viewport-relative Y (NaN = not yet known).
    var dragKeyId by remember { mutableStateOf<String?>(null) }
    var dragPointerY by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    LaunchedEffect(items, dragInProgress) {
        if (!dragInProgress) localItems = items
    }
    // Scroll the playing item near the top ONCE per sheet opening — re-running
    // on every items emission would yank the list back after each drag commit.
    LaunchedEffect(localItems.isNotEmpty()) {
        if (localItems.isNotEmpty()) {
            val idx = localItems.indexOfFirst { it.videoId == currentId }
            if (idx >= 0) listState.scrollToItem(idx, 200)
        }
    }
    // Edge auto-scroll: while the finger is pinned inside the top/bottom edge
    // band, scroll the list every frame (speed ramps with edge proximity) and
    // swap the dragged row with whichever row now sits under the pointer —
    // the per-row delta accumulator only sees finger movement, so a queue
    // taller than the viewport could otherwise never be reordered past it.
    LaunchedEffect(dragInProgress) {
        if (!dragInProgress) return@LaunchedEffect
        val edgePx = with(density) { AUTO_SCROLL_EDGE_DP.dp.toPx() }
        val maxSpeedPx = with(density) { AUTO_SCROLL_SPEED_DP.dp.toPx() }
        var lastFrame = 0L
        while (dragInProgress) {
            // Frame callback only computes (it cannot suspend); the actual
            // scroll + swap run after it, against refreshed layout info.
            var delta = 0f
            withFrameNanos { now ->
                if (lastFrame != 0L) {
                    val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    val y = dragPointerY
                    if (!y.isNaN()) {
                        val info = listState.layoutInfo
                        val viewportStart = -info.beforeContentPadding.toFloat()
                        val viewportEnd = info.viewportSize.height + info.afterContentPadding.toFloat()
                        delta = when {
                            y < viewportStart + edgePx ->
                                -maxSpeedPx * ((viewportStart + edgePx - y) / edgePx).coerceIn(0f, 1f) * dt
                            y > viewportEnd - edgePx ->
                                maxSpeedPx * ((y - (viewportEnd - edgePx)) / edgePx).coerceIn(0f, 1f) * dt
                            else -> 0f
                        }
                    }
                }
                lastFrame = now
            }
            if (delta != 0f) {
                // dispatchRawDelta: non-suspend raw scroll on the list's own
                // scrollable scope — per-frame nudges need no gesture framing.
                listState.dispatchRawDelta(delta)
                // While pinned at an edge the finger is stationary, so the
                // per-row delta accumulator never fires: swap the dragged row
                // with whichever row the scroll brought under the pointer.
                val info = listState.layoutInfo
                val y = dragPointerY
                val underKey = if (y.isNaN()) {
                    null
                } else {
                    info.visibleItemsInfo
                        .firstOrNull { y >= it.offset && y < it.offset + it.size }
                        ?.key as? String
                }
                val dragKey = dragKeyId
                if (underKey != null && dragKey != null && underKey != dragKey) {
                    val from = localItems.indexOfFirst { it.videoId == dragKey }
                    val to = localItems.indexOfFirst { it.videoId == underKey }
                    if (from >= 0 && to >= 0 && from != to) {
                        localItems = localItems.toMutableList().apply {
                            add(to, removeAt(from))
                        }
                    }
                }
            }
        }
    }

    Column(Modifier.padding(bottom = 24.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.queue),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp),
                textAlign = TextAlign.Center,
                fontSize = SettingsTokens.TitleSize.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(7.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
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
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.queue_autoplay),
                fontSize = SettingsTokens.BodySize.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = PlayerUi.YtRed,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Text(
                        stringResource(R.string.clear_queue),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Normal,
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
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        if (localItems.isEmpty()) {
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
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(localItems, key = { _, it -> it.videoId }) { index, item ->
                    val current = item.videoId == currentId
                    DraggableQueueRow(
                        item = item,
                        current = current,
                        listState = listState,
                        onItem = { if (current) onClose() else onItem(item.url) },
                        onRemove = { onRemove(item.videoId) },
                        onDragStarted = {
                            dragInProgress = true
                            dragStartIndex = localItems.indexOfFirst { it.videoId == item.videoId }
                            dragKeyId = item.videoId
                            dragPointerY = Float.NaN
                        },
                        onDragPointer = { dragPointerY = it },
                        onDrag = { delta ->
                            // pointerInput does not restart on lambda captures,
                            // so the closure may hold the pre-reorder index —
                            // resolve the row's live position instead.
                            val from = localItems.indexOfFirst { it.videoId == item.videoId }
                            if (from < 0) return@DraggableQueueRow
                            val target = (from + delta).coerceIn(0, localItems.lastIndex)
                            if (target != from) {
                                localItems = localItems.toMutableList().apply {
                                    add(target, removeAt(from))
                                }
                            }
                        },
                        onDragEnd = {
                            dragInProgress = false
                            dragKeyId = null
                            dragPointerY = Float.NaN
                            val from = dragStartIndex
                            val to = localItems.indexOfFirst { it.videoId == item.videoId }
                            if (from < 0 || to < 0 || from == to) {
                                // Nothing moved (or the row vanished): adopt the
                                // persisted list to absorb any external change.
                                localItems = persistedItems.value
                                return@DraggableQueueRow
                            }
                            // Commit the local reorder, mapped by id onto the
                            // persisted list: rows added/removed elsewhere shift
                            // the target, but the dragged row's id is stable.
                            val persisted = persistedItems.value
                            val persistedFrom = persisted.indexOfFirst { it.videoId == item.videoId }
                            if (persistedFrom >= 0) {
                                val persistedTo = (persistedFrom + (to - from))
                                    .coerceIn(0, persisted.lastIndex)
                                if (persistedTo != persistedFrom) onMove(persistedFrom, persistedTo)
                            }
                            // Keep the local order on screen; the repository
                            // re-emission (or the resync above) reconciles drift.
                        },
                        onDownload = { onDownloadItem(item) },
                    )
                }
            }
        }
    }
}

/**
 * Queue row with long-press drag reorder: the whole row
 * is the drag handle; a plain tap still plays.
 */
@Composable
private fun DraggableQueueRow(
    item: QueueItem,
    current: Boolean,
    listState: LazyListState,
    onItem: () -> Unit,
    onRemove: () -> Unit,
    onDragStarted: () -> Unit,
    onDragPointer: (Float) -> Unit,
    onDrag: (Int) -> Unit,
    onDragEnd: () -> Unit,
    onDownload: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    // Per-event deltas are sub-row at normal speed; accumulate and consume
    // only whole rows so slow drags still move.
    var dragRemainderPx by remember { mutableFloatStateOf(0f) }
    // A long press that ends without dragging must not play: the drag detector
    // leaves the up unconsumed and the inner clickable sees a tap. Guard on
    // drag-end recency instead of relying on consumption semantics.
    var lastDragEndAt by remember { mutableLongStateOf(0L) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .background(if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .pointerInput(item.videoId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        dragRemainderPx = 0f
                        onDragStarted()
                    },
                    onDragEnd = {
                        dragging = false
                        lastDragEndAt = SystemClock.uptimeMillis()
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        lastDragEndAt = SystemClock.uptimeMillis()
                        onDragEnd()
                    },
                ) { change, amount ->
                    change.consume()
                    // Pointer Y in the list's item-offset space: the row's
                    // viewport offset plus the local hit position. Feeds the
                    // sheet's edge auto-scroll loop.
                    val rowInfo = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == item.videoId }
                    onDragPointer(
                        if (rowInfo != null) rowInfo.offset + change.position.y else Float.NaN,
                    )
                    dragRemainderPx += amount.y
                    val rows = (dragRemainderPx / rowHeightPx).toInt()
                    if (rows != 0) {
                        dragRemainderPx -= rows * rowHeightPx
                        onDrag(rows)
                    }
                }
            }
            .clickable(onClick = {
                if (SystemClock.uptimeMillis() - lastDragEndAt >= GestureMath.TAP_SUPPRESS_MS) onItem()
            })
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QueueThumbnail(item.thumbnailUrl)
        Column(Modifier.weight(1f)) {
            if (current) {
                Text(
                    text = stringResource(R.string.queue_now_playing),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = PlayerUi.YtRed,
                    letterSpacing = 0.8.sp,
                )
            }
            Text(
                text = item.title.ifBlank { item.videoId },
                fontWeight = FontWeight.Normal,
                color = if (current) PlayerUi.YtRed else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onDownload)
                .padding(6.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.queue_remove),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(6.dp),
        )
    }
}

/** Small async thumbnail; falls back to a play icon. */
@Composable
private fun QueueThumbnail(url: String?) {
    YoutubeThumb(
        url = url,
        modifier = Modifier
            .size(width = 64.dp, height = 36.dp)
            .clip(RoundedCornerShape(4.dp)),
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
