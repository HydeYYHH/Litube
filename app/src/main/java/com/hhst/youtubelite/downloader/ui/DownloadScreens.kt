package com.hhst.youtubelite.downloader.ui
import com.hhst.youtubelite.ui.design.CollapsingAppBar
import com.hhst.youtubelite.ui.design.rememberAppBarCollapseState
import androidx.compose.ui.input.nestedscroll.nestedScroll

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.hhst.youtubelite.ui.components.AppAlertDialog as AlertDialog
import com.hhst.youtubelite.ui.components.AppCheckbox as Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.hhst.youtubelite.ui.components.AppLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.hhst.youtubelite.ui.components.AppSlider as Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.hhst.youtubelite.ui.components.AppSwitch as Switch
import androidx.compose.material3.Text
import com.hhst.youtubelite.ui.components.AppTextButton as TextButton
import com.hhst.youtubelite.ui.components.AppTopAppBar as TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.AppStyle
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.ui.components.ConstrainedPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

sealed class DownloadDest {
    data object List : DownloadDest()
    data class Batch(val batchId: String) : DownloadDest()
    data object Settings : DownloadDest()
    data object History : DownloadDest()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    viewModel: DownloadViewModel,
    initialBatchId: String?,
    newBatchRequests: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
    onClose: () -> Unit,
) {
    var dest by rememberSaveable(stateSaver = Saver(
        save = { d ->
            when (d) {
                DownloadDest.List -> "list"
                is DownloadDest.Batch -> "batch:${d.batchId}"
                DownloadDest.Settings -> "settings"
                DownloadDest.History -> "history"
            }
        },
        restore = { raw ->
            when {
                raw == "settings" -> DownloadDest.Settings
                raw == "history" -> DownloadDest.History
                raw.startsWith("batch:") -> DownloadDest.Batch(raw.removePrefix("batch:"))
                else -> DownloadDest.List
            }
        },
    )) {
        mutableStateOf(
            if (!initialBatchId.isNullOrBlank()) DownloadDest.Batch(initialBatchId) else DownloadDest.List,
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics: HapticsController = koinInject()
    var pendingDelete by remember { mutableStateOf<DownloadItemUiState?>(null) }
    // Hoisted so returning from a batch detail restores the list scroll position.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Keep one live snapshot while opening details/settings. A new cold Room
    // subscription in each pane briefly replaced existing downloads with an empty state.
    val downloads = remember(viewModel) { viewModel.observeDownloads() }
    val downloadItems = downloads.collectAsStateWithLifecycle<List<DownloadItemUiState>?>(null)
    var filter by rememberSaveable { mutableStateOf(ManagerFilter.ALL) }
    LaunchedEffect(newBatchRequests) {
        newBatchRequests.collect { dest = DownloadDest.Batch(it) }
    }

    fun handle(item: DownloadItemUiState, action: DownloadRowAction) {
        // Rows reference tasks through items: address them by itemId so the
        // coordinator's owned/skipped boundary holds across shared batches.
        val target = when (val id = item.itemId) {
            null -> DownloadTarget.Task(item.taskId)
            else -> DownloadTarget.Item(id)
        }
        if (item.skipped && action != DownloadRowAction.DELETE && action != DownloadRowAction.COPY_ID) {
            return
        }
        if (action in listOf(DownloadRowAction.PAUSE, DownloadRowAction.RESUME, DownloadRowAction.CANCEL, DownloadRowAction.RETRY, DownloadRowAction.REDOWNLOAD))
            haptics.perform(HapticsController.Event.CONFIRM)
        when (action) {
            DownloadRowAction.PAUSE -> viewModel.pause(target)
            DownloadRowAction.RESUME -> viewModel.resume(target)
            DownloadRowAction.CANCEL -> viewModel.cancel(target)
            DownloadRowAction.RETRY -> viewModel.retryFailed(target)
            DownloadRowAction.REDOWNLOAD -> viewModel.redownload(target)
            DownloadRowAction.OPEN -> openUri(context, item.publishedUris.firstOrNull())
            DownloadRowAction.SHARE -> shareUri(context, item.publishedUris.firstOrNull(), item.title)
            DownloadRowAction.COPY_ID -> copyId(context, item.videoId)
            DownloadRowAction.DELETE -> pendingDelete = item
        }
    }

    BackHandler {
        if (dest != DownloadDest.List) dest = DownloadDest.List else onClose()
    }

    val appBar = rememberAppBarCollapseState(dest to filter,
        enabled = dest != DownloadDest.Settings && !downloadItems.value.isNullOrEmpty())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(appBar.connection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CollapsingAppBar(appBar) { TopAppBar(
                expandedHeight = settingsAppBarHeight(),
                title = {
                    Text(
                        when (dest) {
                            DownloadDest.Settings -> stringResource(R.string.download_settings)
                            DownloadDest.History -> stringResource(R.string.download_history)
                            is DownloadDest.Batch -> stringResource(R.string.download_batch_details)
                            DownloadDest.List -> stringResource(R.string.downloads)
                        },
                    )
                },
                navigationIcon = {
                    val back = stringResource(R.string.navigate_back)
                    IconButton(
                        onClick = { if (dest != DownloadDest.List) dest = DownloadDest.List else onClose() },
                        modifier = Modifier.semantics { contentDescription = back },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.padding(0.dp),
                        )
                    }
                },
                actions = {
                    if (dest is DownloadDest.List) {
                        val settings = stringResource(R.string.download_settings)
                        IconButton(
                            onClick = { dest = DownloadDest.Settings },
                            modifier = Modifier.semantics { contentDescription = settings },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = null,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            ) }
        },
    ) { padding ->
        ConstrainedPage {
            when (val current = dest) {
                DownloadDest.List -> DownloadListPane(
                    listState = listState,
                    downloadItems = downloadItems,
                    filter = filter,
                    onFilter = { filter = it },
                    padding = padding,
                    onOpenBatch = { dest = DownloadDest.Batch(it) },
                    onRemove = { item, mode -> viewModel.remove(item.target(), mode) },
                    onAction = ::handle,
                )
                is DownloadDest.Batch -> DownloadBatchPane(
                    batchId = current.batchId,
                    viewModel = viewModel,
                    padding = padding,
                    onAction = ::handle,
                )
                DownloadDest.Settings -> DownloadSettingsPane(
                    viewModel = viewModel,
                    padding = padding,
                )
                DownloadDest.History -> DownloadHistoryPane(
                    viewModel = viewModel,
                    downloadItems = downloadItems,
                    padding = padding,
                    onAction = ::handle,
                    snackbar = snackbar,
                )
            }
        }
    }

    pendingDelete?.let { item ->
        DeleteDialog(
            onDismiss = { pendingDelete = null },
            onConfirm = { mode ->
                val target = when (val id = item.itemId) {
                    null -> DownloadTarget.Task(item.taskId)
                    else -> DownloadTarget.Item(id)
                }
                haptics.perform(HapticsController.Event.CONFIRM)
                viewModel.remove(target, mode)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun DownloadListPane(
    listState: androidx.compose.foundation.lazy.LazyListState,
    downloadItems: State<List<DownloadItemUiState>?>,
    filter: ManagerFilter,
    onFilter: (ManagerFilter) -> Unit,
    padding: PaddingValues,
    onOpenBatch: (String) -> Unit,
    onRemove: (DownloadItemUiState, RemoveMode) -> Unit,
    onAction: (DownloadItemUiState, DownloadRowAction) -> Unit,
) {
    val loaded = downloadItems.value
    val items = remember(loaded, filter) {
        val phases = DownloadPresentation.managerFilter(filter).phases
        loaded.orEmpty().filter { phases == null || it.phase in phases }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = DownloadTokens.PageInset, end = DownloadTokens.PageInset, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadFilterChip(filter == ManagerFilter.ALL, stringResource(R.string.download_filter_all), compact = true) {
                onFilter(ManagerFilter.ALL)
            }
            DownloadFilterChip(filter == ManagerFilter.IN_PROGRESS, stringResource(R.string.download_filter_in_progress), compact = true) {
                onFilter(ManagerFilter.IN_PROGRESS)
            }
            DownloadFilterChip(filter == ManagerFilter.COMPLETED, stringResource(R.string.download_filter_completed), compact = true) {
                onFilter(ManagerFilter.COMPLETED)
            }
        }
        DownloadHairline()
        if (loaded == null) {
            DownloadsLoading()
        } else if (items.isEmpty()) {
            EmptyDownloads()
        } else {
            SelectableDownloads(items, listState = listState,
                onOpen = { it.batchId?.let(onOpenBatch) }, onAction = onAction, onRemove = onRemove)
        }
    }
}

@Composable
private fun DownloadsLoading() {
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        showProgress = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showProgress) com.hhst.youtubelite.home.presentation.LoadingSpinner()
    }
}

@Composable
private fun EmptyDownloads() {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = stringResource(R.string.download_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = SettingsTokens.BodySize.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** An emitted null is a missing batch; no emission yet is still loading. */
private data class LoadedBatch(val value: BatchUiState?)

@Composable
private fun DownloadBatchPane(
    batchId: String,
    viewModel: DownloadViewModel,
    padding: PaddingValues,
    onAction: (DownloadItemUiState, DownloadRowAction) -> Unit,
) {
    val batchFlow = remember(viewModel, batchId) { viewModel.observeBatch(batchId).map(::LoadedBatch) }
    val loaded = key(batchId) { batchFlow.collectAsStateWithLifecycle<LoadedBatch?>(null).value }
    val batch = loaded?.value
    val items = batch?.items.orEmpty()
    val stats = batch?.stats
    Column(Modifier.fillMaxSize().padding(padding)) {
        Text(
            text = batch?.name.orEmpty(),
            modifier = Modifier.padding(horizontal = DownloadTokens.PageInset, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = SettingsTokens.BodySize.sp,
        )
        if (stats != null && stats.total > 0) {
            val completedText = stringResource(R.string.download_batch_progress, stats.completed, stats.total)
            val failedText = if (stats.failed > 0) {
                stringResource(R.string.download_batch_failed_count, stats.failed)
            } else null
            Text(
                text = listOfNotNull(completedText, failedText).joinToString(" · "),
                modifier = Modifier.padding(horizontal = DownloadTokens.PageInset),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { stats.completed.toFloat() / stats.total.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DownloadTokens.PageInset, vertical = 8.dp)
                    .clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
            )
        }
        // Batch controls mirror the per-row semantics offered by actionsFor.
        val target = { item: DownloadItemUiState ->
            item.itemId?.let(DownloadTarget::Item) ?: DownloadTarget.Task(item.taskId)
        }
        val pausable = remember(items) { items.filter {
            !it.skipped && it.status in setOf(
                DownloadStatus.RUNNING,
                DownloadStatus.PAUSING,
                DownloadStatus.WAITING_SYSTEM,
            )
        } }
        val resumable = remember(items) { items.filter {
            !it.skipped && it.status in setOf(
                DownloadStatus.PAUSED,
                DownloadStatus.WAITING_NETWORK,
                DownloadStatus.QUEUED,
            )
        } }
        if (items.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DownloadTokens.PageInset, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pausable.isNotEmpty()) {
                    TextButton(onClick = { pausable.forEach { viewModel.pause(target(it)) } }) {
                        Text(stringResource(R.string.download_pause_all))
                    }
                }
                if (resumable.isNotEmpty()) {
                    TextButton(onClick = { resumable.forEach { viewModel.resume(target(it)) } }) {
                        Text(stringResource(R.string.download_resume_all))
                    }
                }
            }
        }
        DownloadHairline()
        if (loaded == null) {
            DownloadsLoading()
        } else if (items.isEmpty()) {
            EmptyDownloads()
        } else {
            SelectableDownloads(items,
                onOpen = { onAction(it, DownloadRowAction.OPEN) }, onAction = onAction,
                onRemove = { item, mode -> viewModel.remove(item.target(), mode) })
        }
    }

}

@Composable
private fun DownloadSettingsPane(
    viewModel: DownloadViewModel,
    padding: PaddingValues,
) {
    var wifi by remember { mutableStateOf(viewModel.prefs.wifiOnly()) }
    var quality by remember { mutableStateOf(viewModel.prefs.defaultQuality()) }
    var connections by remember { mutableIntStateOf(viewModel.prefs.maxConnections()) }
    val qualities = listOf("144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p")
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = DownloadTokens.PageInset),
    ) {
        SettingsRow(
            title = stringResource(R.string.download_wifi_only),
            subtitle = null,
            onClick = { wifi = !wifi; viewModel.setWifiOnly(wifi) },
            trailing = {
                Switch(checked = wifi, onCheckedChange = {
                    wifi = it
                    viewModel.setWifiOnly(it)
                })
            },
        )
        DownloadHairline()
        Text(
            text = stringResource(R.string.download_quality),
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.download_quality_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        qualities.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DownloadTokens.MinTouch)
                    .clickable {
                        quality = option
                        viewModel.setDefaultQuality(option)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                if (quality == option) Icon(painterResource(R.drawable.ic_check), null,
                    Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                else Spacer(Modifier.size(24.dp))
            }
        }
        DownloadHairline()
        Text(
            text = stringResource(R.string.download_connections, connections),
            modifier = Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = connections.toFloat(),
            onValueChange = {
                connections = it.toInt().coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)
                viewModel.setMaxConnections(connections)
            },
            valueRange = DownloadSettings.MIN_CONNECTIONS.toFloat()..DownloadSettings.MAX_CONNECTIONS.toFloat(),
            steps = DownloadSettings.MAX_CONNECTIONS - DownloadSettings.MIN_CONNECTIONS - 1,
        )

    }
}

@Composable
private fun DownloadHistoryPane(
    viewModel: DownloadViewModel,
    downloadItems: State<List<DownloadItemUiState>?>,
    padding: PaddingValues,
    onAction: (DownloadItemUiState, DownloadRowAction) -> Unit,
    snackbar: SnackbarHostState,
) {
    val items = downloadItems.value
    val terminal = remember(items) { items.orEmpty().filter(DownloadPresentation::isTerminal) }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var deleteFiles by remember { mutableStateOf(false) }
    val clearedMessage = stringResource(R.string.download_history_cleared)
    val failedMessage = stringResource(R.string.download_failed)
    Column(Modifier.fillMaxSize().padding(padding)) {
        TextButton(
            onClick = { confirmClear = true },
            modifier = Modifier
                .padding(horizontal = DownloadTokens.PageInset)
                .heightIn(min = DownloadTokens.MinTouch),
        ) {
            Text(stringResource(R.string.download_clear_history))
        }
        DownloadHairline()
        if (items == null) {
            DownloadsLoading()
        } else if (terminal.isEmpty()) {
            EmptyDownloads()
        } else {
            LazyColumn {
                items(terminal, key = { it.taskId }) { item ->
                    DownloadItemRow(
                        item = item,
                        onOpen = { onAction(item, DownloadRowAction.OPEN) },
                        onAction = { onAction(item, it) },
                    )
                    DownloadHairline()
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = AppStyle.Panel,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.download_clear_history)) },
            text = {
                Column {
                    Text(stringResource(R.string.download_clear_history_message))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Text(stringResource(R.string.download_delete_files), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        val outcome = runCatching { viewModel.clearHistory(deleteFiles) }
                        snackbar.showSnackbar(if (outcome.isSuccess) clearedMessage else failedMessage)
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}


@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DownloadTokens.MinTouch)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun DeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: (RemoveMode) -> Unit,
) {
    var files by remember { mutableStateOf(false) }
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppStyle.Panel,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.download_delete)) },
        text = {
            Row(
                Modifier.fillMaxWidth().heightIn(min = DownloadTokens.MinTouch)
                    .toggleable(value = files, role = Role.Checkbox,
                        onValueChange = { haptic(); files = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = files, onCheckedChange = null)
                Text(stringResource(R.string.download_delete_local_file), Modifier.padding(start = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(if (files) RemoveMode.RECORD_AND_FILES else RemoveMode.RECORD_ONLY)
            }) { Text(stringResource(R.string.download_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun openUri(context: Context, uri: String?) {
    val parsed = DownloadFileShare.parseOpenable(uri) ?: return
    runCatching { context.startActivity(DownloadFileShare.viewIntent(parsed)) }
}

private fun shareUri(context: Context, uri: String?, title: String) {
    val parsed = DownloadFileShare.parseOpenable(uri) ?: return
    runCatching {
        context.startActivity(
            Intent.createChooser(
                DownloadFileShare.sendIntent(parsed, title),
                title,
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

private fun copyId(context: Context, videoId: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("videoId", videoId))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}
