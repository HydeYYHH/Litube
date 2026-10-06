package com.hhst.youtubelite.downloader.ui

import android.content.Context
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.DownloadLimits
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.QueueItem

/** Confirm-sheet payload for a single video. Prefs/tasks are created only on confirm. */
data class DownloadSingleSpec(
    val videoId: String,
    val title: String = "",
    val author: String? = null,
    val thumbnailUrl: String? = null,
)

/**
 * Maps queue / player surfaces onto [DownloadUi] arguments.
 * Native queue snapshots are capped at [DownloadLimits.NATIVE_QUEUE_CAP].
 */
object DownloadEntries {

    fun queueSnapshot(items: List<QueueItem>, name: String = "Queue"): BatchSnapshot {
        val requests = items.asSequence()
            .mapNotNull(::request)
            .take(DownloadLimits.NATIVE_QUEUE_CAP)
            .toList()
        return BatchSnapshot(
            source = BatchSource.QUEUE,
            name = name,
            items = requests,
        )
    }

    fun single(item: QueueItem): DownloadSingleSpec? = request(item)?.let {
        DownloadSingleSpec(it.videoId, it.title, it.author, it.thumbnailUrl)
    }

    fun single(
        videoId: String?,
        title: String = "",
        author: String? = null,
        thumbnailUrl: String? = null,
    ): DownloadSingleSpec? {
        val id = VideoId.parse(videoId) ?: return null
        return DownloadSingleSpec(
            videoId = id,
            title = title,
            author = author,
            thumbnailUrl = thumbnailUrl ?: VideoId.thumbnailUrl(id),
        )
    }

    fun show(context: Context, spec: DownloadSingleSpec) {
        DownloadUi.showSingleConfirm(
            context,
            spec.videoId,
            spec.title,
            spec.author,
            spec.thumbnailUrl,
        )
    }

    private fun request(item: QueueItem): DownloadRequest? {
        val id = VideoId.parse(item.videoId) ?: VideoId.parse(item.url) ?: return null
        return DownloadRequest(
            videoId = id,
            title = item.title,
            author = item.author,
            thumbnailUrl = item.thumbnailUrl ?: VideoId.thumbnailUrl(id),
        )
    }
}
