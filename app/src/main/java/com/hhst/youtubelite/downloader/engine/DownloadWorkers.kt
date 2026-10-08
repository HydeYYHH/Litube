package com.hhst.youtubelite.downloader.engine

import android.app.Notification
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

    private fun foregroundInfo(batchId: String): ForegroundInfo =
        workerForegroundInfo(applicationContext, sdk, batchId, DownloadWorkKind.TRANSFER)

    companion object {
        fun placeholderNotification(context: Context, batchId: String): Notification {
            ensureDownloadChannel(context)
            return NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
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

    private fun foregroundInfo(batchId: String): ForegroundInfo =
        workerForegroundInfo(applicationContext, sdk, batchId, DownloadWorkKind.FINALIZE)
}

/** Shared worker foreground scaffold; only the notification id seed and FGS type differ. */
private fun workerForegroundInfo(
    context: Context,
    sdk: Int,
    batchId: String,
    kind: DownloadWorkKind,
): ForegroundInfo {
    val id = DownloadWorkNames.notificationId(batchId.ifBlank { kind.name.lowercase() })
    val type = DownloadForegroundTypes.forWork(sdk, kind)
    val notification = DownloadTransferWorker.placeholderNotification(context, batchId)
    return if (sdk >= 29) ForegroundInfo(id, notification, type) else ForegroundInfo(id, notification)
}
