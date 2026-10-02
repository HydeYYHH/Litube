package com.hhst.youtubelite.ui.browser

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hhst.youtubelite.R
import com.hhst.youtubelite.browser.Bridge
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.ui.YoutubeThumb

/** Long-press media menu: thumbnail, title, author, queue and share actions. */
@Composable
fun MediaItemMenuDialog(
    item: Bridge.QueueItemJson,
    onQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // parseQueueItem guarantees item.url parses to an id; the canonical
    // hqdefault thumbnail keeps this menu consistent with the queue sheet.
    val videoId = VideoId.parse(item.url)
    val thumbUrl = videoId?.let(VideoId::thumbnailUrl)
    val shareUrl = videoId?.let(VideoId::watchUrl) ?: item.url
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(16.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    YoutubeThumb(
                        url = thumbUrl,
                        contentDescription = stringResource(R.string.thumbnail),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        text = item.title.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                val author = item.author?.takeIf { it.isNotBlank() }
                if (author != null) {
                    Text(
                        text = author,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onQueue) {
                        Icon(
                            painter = painterResource(R.drawable.ic_queue_add),
                            contentDescription = stringResource(R.string.add_to_queue),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            VideoId.parse(item.url)?.let { id ->
                                DownloadUi.showSingleConfirm(
                                    context,
                                    id,
                                    item.title.orEmpty(),
                                    item.author,
                                    VideoId.thumbnailUrl(id),
                                )
                            }
                            onDismiss()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.download),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareUrl)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        send,
                                        context.getString(R.string.share),
                                    ),
                                )
                            }
                            onDismiss()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.share),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
