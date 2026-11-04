package com.hhst.youtubelite.player.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.downloader.ui.DownloadEntries
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.QueueRepository
import org.koin.compose.koinInject

@Composable
internal fun LibraryQueueSheet(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    val queue: QueueRepository = koinInject()
    val state by queue.state.collectAsStateWithLifecycle()
    val owner = state.owner
    val context = LocalContext.current
    PlayerModalSheet(onDismiss = onDismiss, expanded = true) {
        QueueSheet(state.items, currentId = null, loopMode = LoopMode.QUEUE_NEXT,
            onItem = { url -> VideoId.parse(url)?.let(onPlay) },
            onClear = { queue.clear(owner) }, onRemove = { queue.remove(it, owner) },
            onLoop = null, onClose = onDismiss, onMove = queue::move, owner = owner,
            onDownloadAll = { DownloadUi.showBatchConfirm(context, DownloadEntries.queueSnapshot(state.items)) },
            onDownloadItem = { DownloadEntries.single(it)?.let { spec -> DownloadEntries.show(context, spec) } },
        )
    }
}
