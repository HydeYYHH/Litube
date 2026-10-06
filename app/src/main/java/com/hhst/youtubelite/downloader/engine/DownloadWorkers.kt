package com.hhst.youtubelite.downloader.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.hhst.youtubelite.R

class DownloadTransferWorker(
    context: Context,
    params: WorkerParameters,
    private val executor: DownloadBatchExecutor,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(DownloadWorkNames.KEY_BATCH_ID) ?: return Result.success()
        runCatching { setForeground(foregroundInfo(batchId)) }
        executor.executeTransfer(batchId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val batchId = inputData.getString(DownloadWorkNames.KEY_BATCH_ID).orEmpty()
        return foregroundInfo(batchId)
    }

    private fun foregroundInfo(batchId: String): ForegroundInfo {
        val id = DownloadWorkNames.notificationId(batchId.ifBlank { "transfer" })
        val type = DownloadForegroundTypes.forWork(sdk, DownloadWorkKind.TRANSFER)
        val notification = placeholderNotification(applicationContext, batchId)
        return if (sdk >= 29) {
            ForegroundInfo(id, notification, type)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "download_channel"

        fun placeholderNotification(context: Context, batchId: String): Notification {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.download_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.download_channel_desc) },
                )
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_play)
                .setContentTitle(context.getString(R.string.download_channel_name))
                .setContentText(batchId)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}

class DownloadFinalizeWorker(
    context: Context,
    params: WorkerParameters,
    private val executor: DownloadBatchExecutor,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(DownloadWorkNames.KEY_BATCH_ID) ?: return Result.success()
        runCatching { setForeground(foregroundInfo(batchId)) }
        executor.executeFinalize(batchId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val batchId = inputData.getString(DownloadWorkNames.KEY_BATCH_ID).orEmpty()
        return foregroundInfo(batchId)
    }

    private fun foregroundInfo(batchId: String): ForegroundInfo {
        val id = DownloadWorkNames.notificationId(batchId.ifBlank { "finalize" })
        val type = DownloadForegroundTypes.forWork(sdk, DownloadWorkKind.FINALIZE)
        val notification = DownloadTransferWorker.placeholderNotification(applicationContext, batchId)
        return if (sdk >= 29) {
            ForegroundInfo(id, notification, type)
        } else {
            ForegroundInfo(id, notification)
        }
    }
}
