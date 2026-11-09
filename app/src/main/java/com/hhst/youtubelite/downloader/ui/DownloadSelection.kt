package com.hhst.youtubelite.downloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.ui.components.AppCheckbox
import com.hhst.youtubelite.ui.components.AppTextButton

internal fun DownloadItemUiState.target(): DownloadTarget =
    itemId?.let(DownloadTarget::Item) ?: DownloadTarget.Task(taskId)

private val DownloadItemUiState.selectionKey get() = itemId ?: taskId

@Composable
internal fun SelectableDownloads(
    downloads: List<DownloadItemUiState>,
    onOpen: (DownloadItemUiState) -> Unit,
    onAction: (DownloadItemUiState, DownloadRowAction) -> Unit,
    onRemove: (DownloadItemUiState, RemoveMode) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var keys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var deleting by remember { mutableStateOf<List<DownloadItemUiState>?>(null) }
    val selected = downloads.filter { it.selectionKey in keys }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(downloads.map { it.selectionKey }) {
        keys = keys.filter { key -> downloads.any { it.selectionKey == key } }
    }
    fun toggle(item: DownloadItemUiState) {
        keys = if (item.selectionKey in keys) keys - item.selectionKey else keys + item.selectionKey
    }
    BackHandler(selecting) { keys = emptyList() }
    if (selecting) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val selectAll = stringResource(R.string.download_select_all)
            AppCheckbox(selected.size == downloads.size, { all ->
                keys = if (all) downloads.map { it.selectionKey } else emptyList()
            }, Modifier.semantics { contentDescription = selectAll })
            Text(stringResource(R.string.download_selected_count, selected.size, downloads.size), Modifier.weight(1f))
            AppTextButton(onClick = { keys = emptyList() }) { Text(stringResource(R.string.cancel)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            val bulkActions = listOf(DownloadRowAction.PAUSE, DownloadRowAction.RESUME,
                DownloadRowAction.CANCEL, DownloadRowAction.RETRY, DownloadRowAction.REDOWNLOAD, DownloadRowAction.DELETE)
            bulkActions.forEach { action ->
                val eligible = selected.filter {
                    (action == DownloadRowAction.DELETE || !it.skipped) && action in DownloadPresentation.actionsFor(it)
                }
                if (eligible.isNotEmpty()) AppTextButton(onClick = {
                    if (action == DownloadRowAction.DELETE) deleting = eligible.toList()
                    else { eligible.forEach { onAction(it, action) }; keys = emptyList() }
                }) { Text(actionLabel(action)) }
            }
        }
        DownloadHairline()
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(downloads, key = { it.selectionKey }) { item ->
            DownloadItemRow(item,
                onOpen = { if (selecting) toggle(item) else onOpen(item) },
                onLongClick = { toggle(item) },
                selectionMode = selecting, selected = item.selectionKey in keys,
                onAction = { onAction(item, it) })
            DownloadHairline()
        }
    }
    deleting?.let { pending ->
        DeleteDialog(onDismiss = { deleting = null }, onConfirm = { mode ->
            pending.forEach { onRemove(it, mode) }
            deleting = null
            keys = emptyList()
        })
    }
}
