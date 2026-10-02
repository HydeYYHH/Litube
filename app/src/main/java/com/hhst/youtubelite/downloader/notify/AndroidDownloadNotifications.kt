package com.hhst.youtubelite.downloader.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.ui.DownloadActionActivity
import com.hhst.youtubelite.downloader.ui.DownloadActionReceiver
import com.hhst.youtubelite.downloader.ui.DownloadActions
import com.hhst.youtubelite.downloader.work.DownloadTransferWorker
import com.hhst.youtubelite.downloader.work.DownloadWorkNames
import com.hhst.youtubelite.extractor.Promise
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class AndroidNotificationPort(
    private val context: Context,
) : DownloadNotificationPort {
    override fun notify(id: Int, payload: DownloadNotificationPayload) {
        if (!areNotificationsEnabled()) return
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(DownloadTransferWorker.CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        DownloadTransferWorker.CHANNEL_ID,
                        context.getString(R.string.download_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.download_channel_desc) },
                )
            }
        }
        val builder = NotificationCompat.Builder(context, DownloadTransferWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(payload.title)
            .setContentText(payloadText(payload))
            .setOnlyAlertOnce(true)
            .setOngoing(payload.ongoing)
            .setSilent(true)
        if (payload.progress in 0..100) {
            builder.setProgress(100, payload.progress, payload.progress < 0)
        }
        builder.setContentIntent(activityIntent(DownloadActions.VIEW, payload.batchId))
        if (payload.showPause) {
            builder.addAction(
                0,
                context.getString(R.string.action_pause),
                broadcastIntent(DownloadActions.PAUSE, payload.batchId),
            )
        }
        if (payload.showResume) {
            builder.addAction(
                0,
                context.getString(R.string.download_resume),
                activityIntent(DownloadActions.RESUME, payload.batchId),
            )
        }
        if (payload.showCancel) {
            builder.addAction(
                0,
                context.getString(R.string.cancel),
                broadcastIntent(DownloadActions.CANCEL, payload.batchId),
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.download_view),
            activityIntent(DownloadActions.VIEW, payload.batchId),
        )
        runCatching { nm.notify(id, builder.build()) }
    }

    override fun cancel(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    override fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun payloadText(payload: DownloadNotificationPayload): String = when (payload.text) {
        "complete" -> context.getString(R.string.download_complete)
        "paused" -> context.getString(R.string.download_paused)
        "failed" -> context.getString(R.string.download_failed)
        else -> payload.text
    }

    private fun broadcastIntent(action: String, batchId: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(DownloadActions.EXTRA_BATCH_ID, batchId)
        return PendingIntent.getBroadcast(
            context,
            DownloadWorkNames.notificationId(batchId) xor action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun activityIntent(action: String, batchId: String): PendingIntent {
        val intent = DownloadActionActivity.intent(context, action, batchId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            DownloadWorkNames.notificationId(batchId) xor action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class DownloadNotificationWatcher(
    private val coordinator: DownloadCoordinator,
    private val repository: DownloadRepository,
    private val controller: DownloadNotificationController,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = Promise.DEFAULT_SCOPE.launch {
            coordinator.observeDownloads(DownloadFilter()).distinctUntilChanged().collect { snaps ->
                val batchIds = snaps.mapNotNull { snap ->
                    coordinator.batchIdForTask(snap.task.id)
                }.distinct()
                batchIds.forEach { batchId ->
                    val view = repository.transact { batchView(batchId) } ?: return@forEach
                    controller.publish(view)
                }
            }
        }
    }
}
