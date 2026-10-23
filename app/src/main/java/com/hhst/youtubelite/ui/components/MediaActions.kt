package com.hhst.youtubelite.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.share.VideoShare
import com.hhst.youtubelite.ui.design.*
import org.koin.java.KoinJavaComponent.getKoin

data class MediaActionItem(val id: String, val title: String, val author: String = "", val artwork: String = "")

fun Modifier.mediaActions(item: MediaActionItem, onClick: () -> Unit): Modifier = composed {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    if (open) MediaActionsDialog(item) { open = false }
    combinedClickable(role = Role.Button, onLongClickLabel = "Video actions", onLongClick = { open = true }, onClick = onClick)
}

@Composable
fun MediaActionsDialog(item: MediaActionItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = VideoShare.urlFor(item.id)
    val icons = listOf(AppIcon.QueueAdd, AppIcon.Download, AppIcon.OpenWith, AppIcon.Share)
    val actions = listOf("Add to queue", "Download", "Open with", "Share", "Copy link", "Copy title")
    Dialog(onDismissRequest = onDismiss) {
        com.hhst.youtubelite.ui.theme.AppTheme {
            Surface(Modifier.widthIn(max = 400.dp).fillMaxWidth().testTag("media-actions"),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        if (item.artwork.isNotBlank()) Artwork(item.artwork,
                            Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)))
                        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).testTag("media-actions-close")) {
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                                Icon(painterResource(R.drawable.ic_close), "Close video actions", Modifier.padding(4.dp).size(24.dp))
                            }
                        }
                    }
                    SelectionContainer {
                        Text(item.title, Modifier.padding(top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.author.isNotBlank()) Text(item.author, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    actions.forEachIndexed { index, label ->
                        if (url != null || label == "Copy title") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button,
                                onClick = {
                                    executeMediaAction(context, item, index)
                                    onDismiss()
                                }).padding(horizontal = 8.dp, vertical = 12.dp).testTag("media-action-$index"), verticalAlignment = Alignment.CenterVertically) {
                                if (index < icons.size) AppIcon(icons[index], modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSurface)
                                else Icon(painterResource(R.drawable.ic_copy), null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun executeMediaAction(context: Context, item: MediaActionItem, action: Int) {
    val url = VideoShare.urlFor(item.id)
    val id = url?.substringAfterLast('/')
    when (action) {
        0 -> if (id != null) {
            getKoin().get<PlayerViewModel>().onQueueAdd(QueueItem(id, title = item.title, author = item.author))
            Toast.makeText(context, R.string.queue_item_added, Toast.LENGTH_SHORT).show()
        }
        1 -> if (id != null) DownloadUi.showSingleConfirm(context, id, item.title, item.author, item.artwork)
        2 -> context.activity()?.let { ShareGateway(it).openWith(item.id) }
        3 -> context.activity()?.let { ShareGateway(it).share(item.id) }
        4, 5 -> {
            val text = if (action == 5) item.title else url ?: return
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
