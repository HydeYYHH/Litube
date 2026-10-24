package com.hhst.youtubelite.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hhst.youtubelite.R
import com.hhst.youtubelite.share.VideoShare
import com.hhst.youtubelite.ui.design.*

/** The long-press actions, typed so no array index decides what runs. */
enum class MediaActionKind(val label: String) {
    Queue("Add to queue"), Download("Download"), OpenWith("Open with"),
    Share("Share"), CopyLink("Copy link"), CopyTitle("Copy title"),
}

data class MediaActionItem(val id: String, val title: String, val author: String = "", val artwork: String = "")

/**
 * Host-provided execution boundary: the dialog renders rows and routes taps,
 * while queue access, download pickers and the system share sheet stay with the
 * activity that owns them.
 */
interface MediaActionExecutor {
    fun execute(item: MediaActionItem, action: MediaActionKind)
}

interface MediaActionHost {
    val mediaActionExecutor: MediaActionExecutor
}

fun Modifier.mediaActions(item: MediaActionItem, onClick: () -> Unit): Modifier = composed {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    if (open) MediaActionsDialog(item) { open = false }
    val haptics = org.koin.compose.koinInject<com.hhst.youtubelite.core.HapticsController>()
    combinedClickable(role = Role.Button, onLongClickLabel = "Video actions",
        onLongClick = {
            haptics.perform(com.hhst.youtubelite.core.HapticsController.Event.CONFIRM)
            open = true
        }, onClick = onClick)
}

@Composable
fun MediaActionsDialog(item: MediaActionItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = VideoShare.urlFor(item.id)
    val executor = (context as? MediaActionHost)?.mediaActionExecutor
    val visibleActions = remember(url) {
        MediaActionKind.entries.filter { url != null || it == MediaActionKind.CopyTitle }
    }
    Dialog(onDismissRequest = onDismiss) {
        AppTheme {
            Surface(Modifier.widthIn(max = 400.dp).fillMaxWidth().testTag("media-actions"),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        if (item.artwork.isNotBlank()) Artwork(item.artwork,
                            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)))
                        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).testTag("media-actions-close")) {
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = DesignTokens.Background.copy(alpha = 0.9f)) {
                                Icon(painterResource(R.drawable.ic_close), "Close video actions", Modifier.padding(4.dp).size(24.dp))
                            }
                        }
                    }
                    SelectionContainer {
                        Text(item.title, Modifier.padding(top = 12.dp, bottom = 4.dp),
                            style = DesignTokens.text(16, 24, FontWeight.Medium), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.author.isNotBlank()) Text(item.author,
                        style = DesignTokens.text(14, 20, color = DesignTokens.Secondary),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    visibleActions.forEach { kind ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = {
                                executor?.execute(item, kind)
                                onDismiss()
                            }).padding(horizontal = 8.dp, vertical = 12.dp)
                            .testTag("media-action-${kind.name}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            when (kind) {
                                MediaActionKind.Queue -> AppIcon(AppIcon.QueueAdd, Modifier.size(24.dp), color = DesignTokens.Ink)
                                MediaActionKind.Download -> AppIcon(AppIcon.Download, Modifier.size(24.dp), color = DesignTokens.Ink)
                                MediaActionKind.OpenWith -> AppIcon(AppIcon.OpenWith, Modifier.size(24.dp), color = DesignTokens.Ink)
                                MediaActionKind.Share -> AppIcon(AppIcon.Share, Modifier.size(24.dp), color = DesignTokens.Ink)
                                MediaActionKind.CopyLink, MediaActionKind.CopyTitle ->
                                    Icon(painterResource(R.drawable.ic_copy), null, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(kind.label, style = DesignTokens.text(16, 24))
                        }
                    }
                }
            }
        }
    }
}
