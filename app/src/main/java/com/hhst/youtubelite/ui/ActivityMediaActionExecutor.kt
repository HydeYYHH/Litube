package com.hhst.youtubelite.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.share.ShareGateway
import com.hhst.youtubelite.share.VideoShare
import com.hhst.youtubelite.ui.components.MediaActionExecutor
import com.hhst.youtubelite.ui.components.MediaActionItem
import com.hhst.youtubelite.ui.components.MediaActionKind
import org.koin.java.KoinJavaComponent.getKoin

/**
 * The activity-owned executor behind the long-press action panel. This is the
 * only place the panel's actions touch the player, the downloader or the system
 * share sheet, so the dialog stays a pure surface.
 */
class ActivityMediaActionExecutor(private val activity: Activity) : MediaActionExecutor {
    override fun execute(item: MediaActionItem, action: MediaActionKind) {
        if (action == MediaActionKind.CopyTitle) {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("", item.title))
            Toast.makeText(activity, R.string.title_copied, Toast.LENGTH_SHORT).show()
            return
        }
        val url = VideoShare.urlFor(item.id) ?: return
        val id = url.substringAfterLast('/')
        when (action) {
            MediaActionKind.PlayNext -> {
                val player = getKoin().get<PlayerViewModel>()
                player.onQueueAddNext(QueueItem(id, title = item.title, author = item.author))
                Toast.makeText(activity, R.string.play_next_queued, Toast.LENGTH_SHORT).show()
            }
            MediaActionKind.Queue -> {
                val player = getKoin().get<PlayerViewModel>()
                val already = player.queueContains(id)
                player.onQueueAdd(QueueItem(id, title = item.title, author = item.author))
                Toast.makeText(activity,
                    activity.getString(if (already) R.string.queue_item_already else R.string.queue_item_added),
                    Toast.LENGTH_SHORT).show()
            }
            MediaActionKind.Download -> DownloadUi.showSingleConfirm(activity, id, item.title, item.author, item.artwork)
            MediaActionKind.OpenWith -> ShareGateway(activity).openWith(item.id)
            MediaActionKind.Share -> ShareGateway(activity).share(item.id)
            MediaActionKind.CopyLink -> ShareGateway(activity).copyLink(item.id)
            MediaActionKind.CopyTitle -> Unit
        }
    }
}
