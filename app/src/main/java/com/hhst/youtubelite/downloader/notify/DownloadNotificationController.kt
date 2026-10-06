package com.hhst.youtubelite.downloader.notify

import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.engine.DownloadWorkNames

/**
 * Per-batch notification. Progress posts at most once per second; pause,
 * cancel, fail, and batch completion flush immediately. Attachments never
 * fire their own complete notification.
 */
class DownloadNotificationController(
    private val port: DownloadNotificationPort,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lastProgressAt = HashMap<String, Long>()
    private val lastSignature = HashMap<String, String>()
    private val lastPayload = HashMap<String, DownloadNotificationPayload>()

    fun publish(view: BatchView) {
        val batchId = view.batch.id
        val payload = payload(view)
        val signature = signature(view)
        val critical = signature != lastSignature[batchId]
        // Room emits for every chunk and every batch. Unchanged notifications,
        // especially completed batches, need no reinflation in the system shade.
        if (lastPayload[batchId] == payload) {
            lastSignature[batchId] = signature
            return
        }
        val shouldNotify = payload.complete || view.stats.total > 0
        if (shouldNotify && !port.areNotificationsEnabled()) return
        val now = clock()
        val last = lastProgressAt[batchId] ?: 0L
        if (!critical && now - last < 1_000L) return
        lastProgressAt[batchId] = now
        lastSignature[batchId] = signature
        if (shouldNotify) {
            port.notify(DownloadWorkNames.notificationId(batchId), payload)
        } else {
            port.cancel(DownloadWorkNames.notificationId(batchId))
        }
        lastPayload[batchId] = payload
    }

    fun payload(view: BatchView): DownloadNotificationPayload {
        val owned = view.items.filter { it.owned && !it.skipped }
        val complete = owned.isNotEmpty() && owned.all {
            it.task.task.phase == DownloadPhase.COMPLETE &&
                it.task.task.completion == CompletionKind.FULL &&
                it.task.task.fileAvailability == FileAvailability.EXISTS
        }
        val paused = view.stats.paused > 0 && view.stats.running == 0 && view.stats.queued == 0
        val running = view.stats.running > 0 || view.stats.queued > 0
        val total = owned.size.coerceAtLeast(1)
        val done = owned.count {
            it.task.task.phase == DownloadPhase.COMPLETE &&
                it.task.task.completion == CompletionKind.FULL &&
                it.task.task.fileAvailability == FileAvailability.EXISTS
        }
        val progress = if (complete) 100 else (done * 100 / total)
        val title = view.batch.name.ifBlank { view.batch.id }
        val text = when {
            complete -> "complete"
            paused -> "paused"
            view.stats.failed > 0 && !running -> "failed"
            else -> "${view.stats.running + view.stats.queued} running"
        }
        return DownloadNotificationPayload(
            batchId = view.batch.id,
            title = title,
            text = text,
            progress = progress,
            ongoing = running && !complete,
            complete = complete,
            showPause = running && !complete,
            showCancel = !complete && view.stats.cancelled < owned.size,
            showResume = paused,
        )
    }

    private fun signature(view: BatchView): String {
        val statuses = view.items.filter { it.owned }.joinToString(",") {
            "${it.task.task.status}:${it.task.task.phase}:${it.task.task.userPaused}:${it.task.task.userCancelled}"
        }
        return "${view.stats.completed}:${view.stats.failed}:${view.stats.cancelled}:" +
            "${view.stats.paused}:${view.stats.running}:$statuses"
    }

    companion object {
        fun statusCritical(previous: DownloadStatus?, next: DownloadStatus): Boolean {
            if (previous == next) return false
            return next == DownloadStatus.PAUSED ||
                next == DownloadStatus.CANCELLED ||
                next == DownloadStatus.FAILED ||
                next == DownloadStatus.WAITING_SYSTEM ||
                next == DownloadStatus.WAITING_NETWORK ||
                previous == DownloadStatus.RUNNING
        }
    }
}
