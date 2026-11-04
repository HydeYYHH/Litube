package com.hhst.youtubelite.ui

import android.app.Activity
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.share.VideoShare
import org.koin.java.KoinJavaComponent.getKoin

enum class MediaActionKind(val label: String) {
    PlayNext("Play next"), Queue("Add to queue"), Download("Download"), OpenWith("Open with"),
    Share("Share"), CopyLink("Copy link"),
}

data class MediaActionItem(val id: String, val title: String, val author: String = "", val artwork: String = "")

/**
 * Queue access, download pickers and the system share sheet stay with the
 * activity that owns them; the sheet only routes taps here.
 */
class ActivityMediaActionExecutor(private val activity: Activity) {
    fun execute(item: MediaActionItem, action: MediaActionKind) {
        val url = VideoShare.urlFor(item.id) ?: return
        val id = url.substringAfterLast('/')
        when (action) {
            MediaActionKind.PlayNext -> {
                val player = getKoin().get<PlayerViewModel>()
                player.onQueueAddNext(QueueItem(id, title = item.title, author = item.author))
                activity.showMessage(activity.getString(R.string.play_next_queued))
            }
            MediaActionKind.Queue -> {
                val player = getKoin().get<PlayerViewModel>()
                val already = player.queueContains(id)
                player.onQueueAdd(QueueItem(id, title = item.title, author = item.author))
                activity.showMessage(activity.getString(if (already) R.string.queue_item_already else R.string.queue_item_added))
            }
            MediaActionKind.Download -> DownloadUi.showSingleConfirm(activity, id, item.title, item.author, item.artwork)
            MediaActionKind.OpenWith -> ShareGateway(activity).openWith(item.id)
            MediaActionKind.Share -> ShareGateway(activity).share(item.id)
            MediaActionKind.CopyLink -> ShareGateway(activity).copyLink(item.id)
        }
    }
}
