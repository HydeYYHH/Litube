package com.hhst.youtubelite.downloader.engine

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.extractor.Promise
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentHashMap

class AndroidWorkEnqueuePort(
    private val context: Context,
) : WorkEnqueuePort {
    private val wm get() = WorkManager.getInstance(context)

    override fun enqueueUnique(request: UniqueWorkRequest): EnqueueOutcome {
        val existing = runCatching {
            wm.getWorkInfosForUniqueWork(request.uniqueName).get()
        }.getOrNull().orEmpty()
        val active = existing.any { !it.state.isFinished }
        if (active && !request.replace) return EnqueueOutcome.ALREADY_PRESENT
        val constraints = Constraints.Builder().apply {
            if (request.requiresNetwork) {
                setRequiredNetworkType(
                    if (request.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
            }
        }.build()
        val data = workDataOf(DownloadWorkNames.KEY_BATCH_ID to request.batchId)
        val work = if (request.kind == DownloadWorkKind.FINALIZE ||
            request.kind == DownloadWorkKind.MUX ||
            request.kind == DownloadWorkKind.SAVE
        ) {
            OneTimeWorkRequestBuilder<DownloadFinalizeWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()
        } else {
            OneTimeWorkRequestBuilder<DownloadTransferWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()
        }
        val policy = if (request.replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork(request.uniqueName, policy, work)
        return if (active) EnqueueOutcome.REPLACED else EnqueueOutcome.CREATED
    }

    override fun cancelUnique(name: String) {
        runCatching { wm.cancelUniqueWork(name) }
    }

    override fun state(name: String): WorkSnapshot? {
        val infos = runCatching { wm.getWorkInfosForUniqueWork(name).get() }.getOrNull() ?: return null
        val info = infos.firstOrNull() ?: return null
        val kind = if (name.startsWith(DownloadWorkNames.FINALIZE_PREFIX)) {
            DownloadWorkKind.FINALIZE
        } else {
            DownloadWorkKind.TRANSFER
        }
        return WorkSnapshot(active = !info.state.isFinished, kind = kind)
    }
}

class AndroidUidtJobPort(
    private val context: Context,
) : UidtJobPort {
    @SuppressLint("NewApi")
    override fun register(request: UidtJobRequest, legalUserInteraction: Boolean): UidtRegisterResult {
        if (Build.VERSION.SDK_INT < 34) return UidtRegisterResult.RejectedNoUserInteraction
        if (!legalUserInteraction) return UidtRegisterResult.RejectedNoUserInteraction
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: return UidtRegisterResult.RejectedNoUserInteraction
        val pending = scheduler.allPendingJobs.firstOrNull { it.id == request.jobId }
        if (pending != null) return UidtRegisterResult.Restored(request.jobId)
        val extras = PersistableBundle().apply {
            putString(DownloadWorkNames.KEY_BATCH_ID, request.batchId)
        }
        val estimated = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        val job = JobInfo.Builder(
            request.jobId,
            ComponentName(context, DownloadUidtJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetworkType(
                if (request.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY,
            )
            .setEstimatedNetworkBytes(estimated, 0L)
            .setExtras(extras)
            .build()
        val code = scheduler.schedule(job)
        return if (code == JobScheduler.RESULT_SUCCESS) {
            UidtRegisterResult.Created(request.jobId)
        } else {
            UidtRegisterResult.RejectedNoUserInteraction
        }
    }

    override fun cancel(jobId: Int) {
        if (Build.VERSION.SDK_INT < 34) return
        context.getSystemService(JobScheduler::class.java)?.cancel(jobId)
    }

    override fun isActive(jobId: Int): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return context.getSystemService(JobScheduler::class.java)
            ?.allPendingJobs
            ?.any { it.id == jobId } == true
    }
}

class DownloadUidtJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    @SuppressLint("NewApi")
    override fun onStartJob(params: JobParameters): Boolean {
        val batchId = params.extras.getString(DownloadWorkNames.KEY_BATCH_ID) ?: return false
        val koin = GlobalContext.getOrNull() ?: return false
        val executor = koin.get<DownloadBatchExecutor>()
        val notification = DownloadTransferWorker.placeholderNotification(this, batchId)
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(
                params,
                DownloadWorkNames.notificationId(batchId),
                notification,
                JOB_END_NOTIFICATION_POLICY_DETACH,
            )
        }
        val job = Promise.DEFAULT_SCOPE.launch {
            try {
                executor.executeTransfer(batchId)
            } finally {
                running.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        running[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        val batchId = params.extras.getString(DownloadWorkNames.KEY_BATCH_ID)
        val koin = GlobalContext.getOrNull()
        if (batchId != null && koin != null) {
            val executor = koin.get<DownloadBatchExecutor>()
            val coordinator = koin.get<DownloadCoordinator>()
            Promise.DEFAULT_SCOPE.launch {
                coordinator.ownedTaskIds(batchId).forEach { executor.onSystemStop(it) }
            }
        }
        return false
    }
}

/** Koin-backed [WorkerFactory]; WorkManager is initialized after the Koin graph. */
class KoinDownloadWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val koin = GlobalContext.getOrNull() ?: return null
        return when (workerClassName) {
            DownloadTransferWorker::class.java.name ->
                DownloadTransferWorker(appContext, workerParameters, koin.get())
            DownloadFinalizeWorker::class.java.name ->
                DownloadFinalizeWorker(appContext, workerParameters, koin.get())
            else -> null
        }
    }
}
