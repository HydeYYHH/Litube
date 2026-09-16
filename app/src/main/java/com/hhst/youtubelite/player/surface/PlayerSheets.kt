package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.PlayerUiState

/** Player sheets: More + Queue. */
sealed interface PlayerSheet {
    data object More : PlayerSheet
    data object Queue : PlayerSheet
}

/** Dialog menus. */
sealed interface PlayerDialog {
    data object Resize : PlayerDialog
    data object Audio : PlayerDialog
    data object Info : PlayerDialog
    data object Cast : PlayerDialog
    data object SubtitleStyle : PlayerDialog
}

/** Anchored popup menus. */
sealed interface PlayerAnchorMenu {
    data object Speed : PlayerAnchorMenu
    data object Quality : PlayerAnchorMenu
    data object Subtitles : PlayerAnchorMenu
    data object Segments : PlayerAnchorMenu
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
    callbacks: PlayerSurfaceCallbacks,
    onDismiss: () -> Unit,
    onOpenDialog: (PlayerDialog) -> Unit,
) {
    if (sheet == null) return
    PlayerModalSheet(onDismiss = onDismiss) {
        when (sheet) {
            PlayerSheet.More -> MoreSheet(
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
                onShare = { callbacks.onShare(); onDismiss() },
            )
            PlayerSheet.Queue -> QueueSheet(
                items = state.queueItems,
                currentId = state.videoId,
                enabled = state.queueEnabled,
                loopMode = state.loopMode,
                onItem = { callbacks.onQueueItem(it); onDismiss() },
                onEnabled = callbacks::onQueueEnabled,
                onClear = callbacks::onQueueClear,
                onRemove = callbacks::onQueueRemove,
                onLoop = callbacks::onLoop,
                onClose = onDismiss,
                onMove = callbacks::onQueueMove,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerModalSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = PlayerUi.sheetMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
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
                state.audioTracks.forEach { add(OptionRow(it.key, it.label)) }
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
        )
    }
}

@Composable
private fun MoreSheet(
    showPip: Boolean,
    onResize: () -> Unit,
    onCast: () -> Unit,
    onPip: () -> Unit,
    onAudio: () -> Unit,
    onSubtitleStyle: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SheetTitle(stringResource(R.string.more_options))
        MoreRow(R.drawable.ic_resize, stringResource(R.string.resize_mode), onResize)
        MoreRow(R.drawable.ic_cast, stringResource(R.string.cast), onCast)
        if (showPip) MoreRow(R.drawable.ic_pip, stringResource(R.string.pip), onPip)
        MoreRow(R.drawable.ic_track, stringResource(R.string.audio_track), onAudio)
        MoreRow(R.drawable.ic_subtitles_on, stringResource(R.string.subtitle_style), onSubtitleStyle)
        MoreRow(R.drawable.ic_info, stringResource(R.string.info), onInfo)
        MoreRow(R.drawable.ic_share, stringResource(R.string.share), onShare)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        textAlign = TextAlign.Center,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MoreRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 16.dp)
                .size(24.dp),
        )
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp),
        )
    }
}
