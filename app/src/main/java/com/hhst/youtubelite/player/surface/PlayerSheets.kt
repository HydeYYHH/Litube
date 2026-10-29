package com.hhst.youtubelite.player.surface

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.downloader.ui.DownloadEntries
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.PlayerUiState
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.ui.components.audioTrackLabel
import com.hhst.youtubelite.ui.components.AppSheetHandle
import com.hhst.youtubelite.ui.theme.AppStyle

/**
 * Player sheets: More is the root settings page; Quality and Speed are its
 * subpages. They replace the sheet content in place (with an in-sheet back
 * action) instead of closing the sheet and stacking a popup over the player.
 */
sealed interface PlayerSheet {
    data object More : PlayerSheet
    data object Quality : PlayerSheet
    data object Speed : PlayerSheet
    data object Queue : PlayerSheet
    data object Chapters : PlayerSheet
}

/** Dialog menus. */
sealed interface PlayerDialog {
    data object Resize : PlayerDialog
    data object Audio : PlayerDialog
    data object Info : PlayerDialog
    data object Cast : PlayerDialog
    data object SubtitleStyle : PlayerDialog
}

/** Anchored popup menus opened directly from the chrome. */
sealed interface PlayerAnchorMenu {
    data object Subtitles : PlayerAnchorMenu
}

/**
 * Typed dropdown row: [value] is handed back by the menu on pick (null for
 * "Auto"/default rows), [label] is displayed, and [enabled] = false renders a
 * non-clickable row.
 */
internal class OptionRow<T>(
    val value: T?,
    val label: String,
    val enabled: Boolean = true,
)

@Composable
fun PlayerSheetHost(
    sheet: PlayerSheet?,
    state: PlayerUiState,
    positionState: State<Long>,
    callbacks: PlayerSurfaceCallbacks,
    onDismiss: () -> Unit,
    onOpenDialog: (PlayerDialog) -> Unit,
    onNavigate: (PlayerSheet) -> Unit,
    onOpenQueue: (() -> Unit)? = null,
) {
    if (sheet == null) return
    val context = LocalContext.current
    val queueAdded = stringResource(R.string.queue_item_added)
    val jumpedTemplate = stringResource(R.string.jumped_to_segment)
    PlayerModalSheet(onDismiss = onDismiss, fullscreen = state.fullscreen && !state.mini) {
        when (sheet) {
            PlayerSheet.More -> MoreSheet(
                onQueue = onOpenQueue,
                onLoop = onOpenQueue?.let { { callbacks.onLoop(); onDismiss() } },
                loopMode = state.loopMode,
                onSpeed = { onNavigate(PlayerSheet.Speed) },
                onQuality = { onNavigate(PlayerSheet.Quality) },
                showPip = state.pipAvailable,
                onResize = {
                    onDismiss()
                    onOpenDialog(PlayerDialog.Resize)
                },
                onCast = {
                    onDismiss()
                    onOpenDialog(PlayerDialog.Cast)
                },
                onPip = { callbacks.onPip(); onDismiss() },
                onAudio = {
                    onDismiss()
                    onOpenDialog(PlayerDialog.Audio)
                },
                onSubtitleStyle = {
                    onDismiss()
                    onOpenDialog(PlayerDialog.SubtitleStyle)
                },
                onInfo = {
                    onDismiss()
                    onOpenDialog(PlayerDialog.Info)
                },
                onDownload = {
                    DownloadEntries.single(
                        state.videoId,
                        state.title,
                        state.author,
                        state.videoId?.let(VideoId::thumbnailUrl),
                    )?.let { DownloadEntries.show(context, it) }
                    onDismiss()
                },
                onShare = { callbacks.onShare(); onDismiss() },
                onQueueAdd = {
                    state.videoId?.let { id ->
                        callbacks.onQueueAdd(QueueItem(id, VideoId.watchUrl(id), state.title, state.author, VideoId.thumbnailUrl(id)))
                        callbacks.onHint(queueAdded)
                    }
                    onDismiss()
                },
            )
            PlayerSheet.Quality -> OptionSheet(
                title = stringResource(R.string.player_info_quality),
                tag = "player-sheet-quality",
                onBack = { onNavigate(PlayerSheet.More) },
                onClose = onDismiss,
                rows = buildList {
                    add(OptionRow<String>(null, stringResource(R.string.player_quality_auto)))
                    state.qualities.forEach { add(OptionRow(it, it)) }
                },
                selected = state.qualityLabel,
                onPick = { callbacks.onQuality(it); onDismiss() },
            )
            PlayerSheet.Speed -> OptionSheet(
                title = stringResource(R.string.info_playback_speed),
                tag = "player-sheet-speed",
                onBack = { onNavigate(PlayerSheet.More) },
                onClose = onDismiss,
                rows = PlayerUi.SpeedChoices.map { OptionRow(it, PlayerUi.speedLabel(it)) },
                selected = state.speed,
                onPick = { speed -> speed?.let(callbacks::onSpeed); onDismiss() },
            )
            PlayerSheet.Queue -> QueueSheet(
                items = state.queueItems,
                currentId = state.videoId,
                loopMode = state.loopMode,
                onItem = { callbacks.onQueueItem(it); onDismiss() },
                onClear = callbacks::onQueueClear,
                onRemove = callbacks::onQueueRemove,
                onLoop = callbacks::onLoop,
                onClose = onDismiss,
                onMove = callbacks::onQueueMove,
                onDownloadAll = {
                    DownloadUi.showBatchConfirm(context, DownloadEntries.queueSnapshot(state.queueItems))
                },
                onDownloadItem = { item ->
                    DownloadEntries.single(item)?.let { DownloadEntries.show(context, it) }
                },
            )
            PlayerSheet.Chapters -> ChaptersSheet(
                chapters = state.chapters,
                segments = state.sponsorSegments,
                positionState = positionState,
                fallbackThumb = state.videoId?.let { VideoId.thumbnailUrl(it) },
                onSeek = { startMs, label ->
                    callbacks.onSeek(startMs)
                    callbacks.onHint(jumpedTemplate.format(label))
                    onDismiss()
                },
                onPreviewSeek = { startMs, label ->
                    callbacks.onSeek(startMs)
                    callbacks.onHint(jumpedTemplate.format(label))
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerModalSheet(
    onDismiss: () -> Unit,
    fullscreen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (fullscreen) {
        FullscreenPlayerSheet(onDismiss, content)
        return
    }
    val maxHeight = PlayerUi.sheetMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = AppStyle.SheetShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = {
            AppSheetHandle()
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), content = content)
    }
}

@Composable
fun PlayerDialogHost(
    dialog: PlayerDialog?,
    state: PlayerUiState,
    callbacks: PlayerSurfaceCallbacks,
    onDismiss: () -> Unit,
    onCopyHint: (String) -> Unit = {},
    onRefreshCastState: () -> Unit = {},
) {
    if (dialog == null) return
    when (dialog) {
        PlayerDialog.Resize -> ResizeDialog(
            selected = state.resizeMode,
            onPick = { callbacks.onResize(it); onDismiss() },
            onDismiss = onDismiss,
        )
        PlayerDialog.Audio -> OptionDialog(
            title = stringResource(R.string.audio_track),
            rows = buildList<OptionRow<String>> {
                add(OptionRow(null, stringResource(R.string.player_audio_default)))
                state.audioTracks.forEach { add(OptionRow(it.key, audioTrackLabel(it))) }
            },
            selected = state.audioTrackKey,
            onPick = { callbacks.onAudioTrack(it); onDismiss() },
            onDismiss = onDismiss,
        )
        PlayerDialog.Info -> InfoDialog(
            state = state,
            onDismiss = onDismiss,
            onCopyHint = onCopyHint,
        )
        PlayerDialog.Cast -> CastDialog(
            state = state,
            onDevice = { callbacks.onCastDevice(it) },
            onStop = { callbacks.onCastStop(); onDismiss() },
            onShareLink = callbacks::onShareCastLink,
            onCloseLink = callbacks::onCloseCastLink,
            onRefresh = onRefreshCastState,
            onDiscovery = callbacks::onCastDiscovery,
            onDismiss = onDismiss,
        )
        PlayerDialog.SubtitleStyle -> SubtitleStyleDialog(
            style = state.subtitleStyle,
            coverUrl = state.videoId?.let { VideoId.thumbnailUrl(it) },
            onChange = callbacks::onSubtitleStyle,
            onDismiss = onDismiss,
            fullscreen = state.fullscreen && !state.mini,
        )
    }
}

@Composable
private fun MoreSheet(
    onQueue: (() -> Unit)?,
    onLoop: (() -> Unit)?,
    loopMode: LoopMode,
    onSpeed: (() -> Unit)?,
    onQuality: (() -> Unit)?,
    showPip: Boolean,
    onResize: () -> Unit,
    onCast: () -> Unit,
    onPip: () -> Unit,
    onAudio: () -> Unit,
    onSubtitleStyle: () -> Unit,
    onInfo: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onQueueAdd: () -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SheetTitle(stringResource(R.string.more_options))
        onQuality?.let { MoreRow(R.drawable.ic_quality, stringResource(R.string.player_info_quality), it) }
        onSpeed?.let { MoreRow(R.drawable.ic_playback_speed, stringResource(R.string.info_playback_speed), it) }
        MoreRow(R.drawable.ic_track, stringResource(R.string.audio_track), onAudio)
        MoreRow(R.drawable.ic_subtitles_on, stringResource(R.string.subtitle_style), onSubtitleStyle)
        MoreRow(R.drawable.ic_resize, stringResource(R.string.resize_mode), onResize)
        MoreDivider()
        onQueue?.let { MoreRow(R.drawable.ic_queue, stringResource(R.string.queue), it) }
        MoreRow(R.drawable.ic_queue_add, stringResource(R.string.add_to_queue), onQueueAdd)
        onLoop?.let { MoreRow(PlayerUi.loopIcon(loopMode), stringResource(PlayerUi.loopLabelRes(loopMode)), it) }
        MoreDivider()
        MoreRow(R.drawable.ic_cast, stringResource(R.string.cast), onCast)
        if (showPip) MoreRow(R.drawable.ic_pip, stringResource(R.string.pip), onPip)
        MoreRow(R.drawable.ic_download, stringResource(R.string.download), onDownload)
        MoreRow(R.drawable.ic_share, stringResource(R.string.share), onShare)
        MoreDivider()
        MoreRow(R.drawable.ic_info, stringResource(R.string.info), onInfo)
    }
}

@Composable
private fun MoreDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

/**
 * Subpage of [PlayerSheet.More]: one header (back + title + close) and a
 * radio list. It replaces the More rows in the same sheet instance, so there
 * is never a popup stacked over an open sheet. System back returns to More.
 */
@Composable
private fun <T> OptionSheet(
    title: String,
    tag: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    rows: List<OptionRow<T>>,
    selected: T?,
    onPick: (T?) -> Unit,
) {
    BackHandler { onBack() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = R.drawable.ic_arrow_back,
            contentDescription = stringResource(R.string.a11y_back),
            onClick = onBack,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("player-sheet-back"),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            fontSize = SettingsTokens.TitleSize.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PlayerIconButton(
            icon = R.drawable.ic_close,
            contentDescription = stringResource(R.string.close),
            onClick = onClose,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("player-sheet-close"),
        )
    }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        for (row in rows) {
            val isSelected = row.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsTokens.RowHeight)
                    .clickable(enabled = row.enabled, role = Role.Button) { onPick(row.value) }
                    .padding(start = 24.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.label,
                    modifier = Modifier.weight(1f),
                    fontSize = SettingsTokens.BodySize.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        !row.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (isSelected) Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        textAlign = TextAlign.Start,
        fontSize = SettingsTokens.TitleSize.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MoreRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsTokens.RowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(end = 16.dp)
                .size(24.dp),
        )
        Text(
            text = label,
            fontSize = SettingsTokens.BodySize.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
