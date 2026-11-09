package com.hhst.youtubelite.saves.presentation

import com.hhst.youtubelite.ui.design.appPaneTitle
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.comments.domain.CommentsLayout
import com.hhst.youtubelite.saves.domain.NewPlaylistDraft
import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.domain.SaveDestination
import com.hhst.youtubelite.saves.domain.SavePlaylist
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.MenuSheetLayout
import com.hhst.youtubelite.ui.design.appDescription
import com.hhst.youtubelite.R

/** Server-driven state for the live save sheet; null [choices] means the list is still loading. */
class SaveSheetLive(
    val choices: List<SaveChoice>?,
    val failed: Boolean,
    val pending: Boolean,
    val onToggle: (SaveChoice) -> Unit,
    val onRetry: () -> Unit,
)

@Composable
fun SaveToPlaylistSheet(
    playlists: List<SavePlaylist>,
    onDismiss: () -> Unit,
    onSelect: (SaveDestination) -> Unit,
    onNewPlaylist: () -> Unit,
    live: SaveSheetLive? = null,
) {
    if (live != null) {
        val items = ArrayList<MenuSheetItem>()
        val choices = live.choices
        when {
            live.failed -> items.add(MenuSheetItem(AppIcon.Refresh, stringResource(R.string.ui_couldn_t_load_playlists_tap_to_retry), "save-menu-retry"))
            choices == null -> items.add(MenuSheetItem(AppIcon.Refresh, stringResource(R.string.ui_loading), "save-menu-loading"))
            else -> {
                choices.forEach {
                    items.add(MenuSheetItem(if (it.contains) AppIcon.Check else AppIcon.Playlist, it.title, "save-menu-playlist-${it.id}"))
                }
                items.add(MenuSheetItem(AppIcon.Add, stringResource(R.string.ui_new_playlist), "save-menu-new-playlist"))
            }
        }
        MenuBottomSheet(
            items = items,
            onDismiss = { if (!live.pending) onDismiss() },
            onSelect = { index ->
                if (live.pending) return@MenuBottomSheet
                when {
                    live.failed -> live.onRetry()
                    choices == null -> Unit
                    index < choices.size -> if (!live.pending) live.onToggle(choices[index])
                    else -> onNewPlaylist()
                }
            },
            tag = "save-to-playlist",
            paneTitle = stringResource(R.string.save_to_playlist),
            dismissEnabled = !live.pending,
            actionsEnabled = !live.pending,
            header = {
                if (live.pending) Box(Modifier.fillMaxWidth().padding(horizontal = MenuSheetLayout.RowStart.dp),
                    contentAlignment = Alignment.CenterEnd) {
                    com.hhst.youtubelite.ui.design.ActionProgress()
                }
            },
        )
        return
    }
    val destinations = buildList {
        add(SaveDestination.WatchLater)
        playlists.forEach { add(SaveDestination.Playlist(it.id, it.title)) }
    }
    MenuBottomSheet(
        items = buildList {
            add(MenuSheetItem(AppIcon.Clock, stringResource(R.string.ui_watch_later), "save-menu-watch-later"))
            playlists.forEach {
                add(MenuSheetItem(AppIcon.Playlist, it.title, "save-menu-playlist-${it.id}"))
            }
            add(MenuSheetItem(AppIcon.Add, stringResource(R.string.ui_new_playlist), "save-menu-new-playlist"))
        },
        onDismiss = onDismiss,
        onSelect = { index ->
            if (index < destinations.size) onSelect(destinations[index]) else onNewPlaylist()
        },
        tag = "save-to-playlist",
        paneTitle = stringResource(R.string.save_to_playlist),

    )
}

/** The sheet's own "New playlist" row, not a simulated created list. */
@Composable
fun NewPlaylistSheet(
    draft: NewPlaylistDraft,
    onDismiss: () -> Unit,
    onDraftChange: (NewPlaylistDraft) -> Unit,
    onSubmit: () -> Unit,
    pending: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    DisposableEffect(Unit) {
        onDispose {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    Box(
        Modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable(enabled = !pending) { onDismiss() }
            .appDescription(R.string.a11y_close_new_playlist),
    )
    BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = MenuSheetLayout.BottomInset.dp)
                .width(MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .heightIn(max = (maxHeight - MenuSheetLayout.BottomInset.dp).coerceAtLeast(0.dp))
                .clip(RoundedCornerShape(MenuSheetLayout.Corner.dp)).background(DesignTokens.Panel)
                .verticalScroll(rememberScrollState())
                .testTag("new-playlist-sheet").appPaneTitle(R.string.ui_new_playlist),
        ) {
            AppText(
                stringResource(R.string.ui_private),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                size = 12, line = 18, color = DesignTokens.Secondary,
            )
            SheetField(
                value = draft.title,
                placeholder = stringResource(R.string.player_info_title),
                tag = "new-playlist-title",
                description = stringResource(R.string.ui_playlist_title),
                focus = focus,
                onValue = { if (!pending) onDraftChange(draft.copy(title = it)) },
                readOnly = pending,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            SheetField(
                value = draft.description,
                placeholder = stringResource(R.string.ui_description_optional),
                tag = "new-playlist-description",
                description = stringResource(R.string.ui_playlist_description),
                onValue = { if (!pending) onDraftChange(draft.copy(description = it)) },
                readOnly = pending,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetAction(stringResource(R.string.cancel), "new-playlist-cancel", enabled = !pending, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                SheetAction(
                    stringResource(R.string.ui_create), "new-playlist-create",
                    enabled = draft.title.isNotBlank() && !pending,
                    pending = pending,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    placeholder: String,
    tag: String,
    description: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    readOnly: Boolean = false,
) {
    BasicTextField(
        value, onValue,
        readOnly = readOnly,
        modifier = modifier.heightIn(min = CommentsLayout.InputHeight.dp).fillMaxWidth()
            .clip(RoundedCornerShape(CommentsLayout.InputRadius.dp))
            .border(1.dp, DesignTokens.Border.copy(alpha = CommentsLayout.DividerAlpha),
                RoundedCornerShape(CommentsLayout.InputRadius.dp))
            .padding(horizontal = CommentsLayout.InputTextInset.dp, vertical = 8.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .testTag(tag)
            .semantics { contentDescription = description },
        textStyle = DesignTokens.text(14, 20),
        cursorBrush = SolidColor(DesignTokens.Ink),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) AppText(placeholder, size = 14, line = 20, color = DesignTokens.Secondary)
                inner()
            }
        },
    )
}

@Composable
private fun SheetAction(
    label: String,
    tag: String,
    enabled: Boolean = true,
    pending: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, enabled = enabled) { if (enabled) onClick() }
            .testTag(tag).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) com.hhst.youtubelite.ui.design.ActionProgress()
        else AppText(
            label,
            size = 14, line = 20, weight = FontWeight.Medium,
            color = if (enabled) DesignTokens.Ink else DesignTokens.Secondary.copy(alpha = 0.5f),
        )
    }
}
