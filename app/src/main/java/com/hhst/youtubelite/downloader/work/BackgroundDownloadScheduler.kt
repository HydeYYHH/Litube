package com.hhst.youtubelite.downloader.work

import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DefaultDownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadInteraction
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadScheduler
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.ScheduleRecord
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.notify.DownloadNotificationPayload
import com.hhst.youtubelite.downloader.notify.DownloadNotificationPort

/**
 * Maps coordinator schedule/cancel onto WorkManager (API 26–33 transfer +
 * all finalize) or one UIDT Job per batch (API 34+ transfer on user confirm
 * / explicit resume only). Duplicate enqueue is [EnqueueOutcome.ALREADY_PRESENT].
 */
class BackgroundDownloadScheduler(
    private val repository: DownloadRepository,
    private val coordinator: () -> DownloadCoordinator,
    private val work: WorkEnqueuePort,
    private val uidt: UidtJobPort,
    private val notifications: DownloadNotificationPort,
    private val sdk: () -> Int,
    private val prefs: DownloadPrefs = DefaultDownloadPrefs,
    var legalUserInteraction: Boolean = true,
) : DownloadScheduler, DownloadInteraction {

    override fun noteUserInitiated() {
        legalUserInteraction = true
    }

    data class Plan(
        val batchId: String,
        val kind: DownloadWorkKind,
        val backend: String,
        val outcome: String,
        val uniqueName: String,
        val jobId: Int? = null,
        val requiresNetwork: Boolean,
    )

    val plans = mutableListOf<Plan>()
    val restored = mutableListOf<String>()

    override suspend fun schedule(taskId: String) {
        scheduleInternal(taskId, repair = false)
    }

    override suspend fun cancel(taskId: String) {
        val ctx = lookup(taskId) ?: return
        val remaining = liveTaskIds(ctx.batchId).filter { it != taskId }
        if (remaining.isNotEmpty()) {
            coordinator().bindSystemWork(taskId, clearJobId = true, backend = "", workKind = "")
            return
        }
        work.cancelUnique(DownloadWorkNames.transfer(ctx.batchId))
        work.cancelUnique(DownloadWorkNames.finalize(ctx.batchId))
        ctx.schedule?.systemJobId?.let { uidt.cancel(it) }
        notifications.cancel(DownloadWorkNames.notificationId(ctx.batchId))
        coordinator().bindSystemWork(taskId, clearJobId = true, backend = "", workKind = "")
    }

    suspend fun repair(taskId: String) = scheduleInternal(taskId, repair = true)

    fun systemHasWork(batchId: String, kind: DownloadWorkKind): Boolean {
        val name = if (kind == DownloadWorkKind.FINALIZE || kind == DownloadWorkKind.MUX ||
            kind == DownloadWorkKind.SAVE
        ) {
            DownloadWorkNames.finalize(batchId)
        } else {
            DownloadWorkNames.transfer(batchId)
        }
        if (work.state(name)?.active == true) return true
        val jobId = DownloadWorkNames.uidtJobId(batchId)
        return kind == DownloadWorkKind.TRANSFER && uidt.isActive(jobId)
    }

    fun noteRestored(batchId: String, kind: DownloadWorkKind) {
        restored += "$batchId:${kind.name}"
        notifications.notifyPrompt(batchId)
    }

    private suspend fun scheduleInternal(taskId: String, repair: Boolean) {
        val ctx = lookup(taskId) ?: return
        val task = ctx.task
        if (task.userPaused || task.userCancelled || task.removed) return
        if (task.phase == DownloadPhase.COMPLETE && task.completion == CompletionKind.FULL) return
        val reason = ctx.schedule?.reason.orEmpty()
        if (task.status == DownloadStatus.WAITING_SYSTEM || reason == ScheduleReasons.SYSTEM) {
            if (!repair && !ScheduleReasons.isUserInitiated(reason)) return
            if (repair) return
        }
        val kind = neededKind(task)
        val batchId = ctx.batchId
        notifications.notifyPrompt(batchId)
        if (kind == DownloadWorkKind.FINALIZE) {
            enqueueFinalize(batchId, replace = !repair)
            bind(ctx.taskIds, DownloadWorkNames.finalize(batchId), backend = "wm", kind = kind)
            return
        }
        val api = sdk()
        if (api >= 34) {
            scheduleUidt(ctx, reason, repair)
        } else {
            if (reason == ScheduleReasons.SYSTEM && !ScheduleReasons.isUserInitiated(reason)) return
            enqueueTransfer(batchId, replace = !repair)
            bind(ctx.taskIds, DownloadWorkNames.transfer(batchId), backend = "wm", kind = DownloadWorkKind.TRANSFER)
        }
    }

    private suspend fun scheduleUidt(ctx: TaskContext, reason: String, repair: Boolean) {
        val batchId = ctx.batchId
        val jobId = ctx.schedule?.systemJobId ?: DownloadWorkNames.uidtJobId(batchId)
        if (uidt.isActive(jobId)) {
            noteRestored(batchId, DownloadWorkKind.TRANSFER)
            plans += Plan(batchId, DownloadWorkKind.TRANSFER, "uidt", "restored", "", jobId, true)
            bind(ctx.taskIds, DownloadWorkNames.transfer(batchId), jobId, "uidt", DownloadWorkKind.TRANSFER)
            return
        }
        val userOk = legalUserInteraction &&
            (ScheduleReasons.isUserInitiated(reason) || reason.isEmpty()) &&
            !repair
        if (!userOk) {
            // UIDT re-registration requires a user interaction (R06). A
            // constraint recovery (network restore) must not strand queued
            // tasks: resume them through WorkManager instead.
            enqueueTransfer(batchId, replace = false)
            bind(ctx.taskIds, DownloadWorkNames.transfer(batchId), backend = "wm", kind = DownloadWorkKind.TRANSFER)
            return
        }
        when (val result = uidt.register(
            UidtJobRequest(jobId, batchId, wifiOnly = prefs.wifiOnly()),
            legalUserInteraction = true,
        )) {
            is UidtRegisterResult.Created -> {
                plans += Plan(batchId, DownloadWorkKind.TRANSFER, "uidt", "created", "", result.jobId, true)
                bind(ctx.taskIds, DownloadWorkNames.transfer(batchId), result.jobId, "uidt", DownloadWorkKind.TRANSFER)
            }
            is UidtRegisterResult.Restored -> {
                plans += Plan(batchId, DownloadWorkKind.TRANSFER, "uidt", "restored", "", result.jobId, true)
                bind(ctx.taskIds, DownloadWorkNames.transfer(batchId), result.jobId, "uidt", DownloadWorkKind.TRANSFER)
            }
            UidtRegisterResult.RejectedNoUserInteraction -> {
                plans += Plan(batchId, DownloadWorkKind.TRANSFER, "uidt", "rejected", "", jobId, true)
            }
        }
    }

    private fun enqueueTransfer(batchId: String, replace: Boolean) {
        val name = DownloadWorkNames.transfer(batchId)
        val existing = work.state(name)
        if (existing?.active == true) {
            // Batch-scoped unique work: per-task scheduling must never REPLACE
            // its own running worker mid-flight.
            plans += Plan(batchId, DownloadWorkKind.TRANSFER, "wm", "already-present", name, null, true)
            return
        }
        val outcome = work.enqueueUnique(
            UniqueWorkRequest(
                uniqueName = name,
                kind = DownloadWorkKind.TRANSFER,
                batchId = batchId,
                requiresNetwork = true,
                wifiOnly = prefs.wifiOnly(),
                replace = false,
            ),
        )
        plans += Plan(
            batchId,
            DownloadWorkKind.TRANSFER,
            "wm",
            outcome.name.lowercase().replace('_', '-'),
            name,
            null,
            true,
        )
    }

    fun enqueueFinalize(batchId: String, replace: Boolean = false) {
        val name = DownloadWorkNames.finalize(batchId)
        val existing = work.state(name)
        if (existing?.active == true) {
            plans += Plan(batchId, DownloadWorkKind.FINALIZE, "wm", "already-present", name, null, false)
            return
        }
        val outcome = work.enqueueUnique(
            UniqueWorkRequest(
                uniqueName = name,
                kind = DownloadWorkKind.FINALIZE,
                batchId = batchId,
                requiresNetwork = false,
                wifiOnly = false,
                replace = false,
            ),
        )
        plans += Plan(
            batchId,
            DownloadWorkKind.FINALIZE,
            "wm",
            outcome.name.lowercase().replace('_', '-'),
            name,
            null,
            false,
        )
    }

    private suspend fun bind(
        taskIds: List<String>,
        uniqueName: String,
        jobId: Int? = null,
        backend: String,
        kind: DownloadWorkKind,
    ) {
        taskIds.forEach { id ->
            coordinator().bindSystemWork(
                taskId = id,
                uniqueWorkName = uniqueName,
                systemJobId = jobId,
                backend = backend,
                workKind = kind.name,
            )
        }
    }

    private fun neededKind(task: DownloadTask): DownloadWorkKind =
        if (task.phase == DownloadPhase.WAITING_PROCESS ||
            task.phase == DownloadPhase.MERGE_VERIFY ||
            task.phase == DownloadPhase.SAVE
        ) {
            DownloadWorkKind.FINALIZE
        } else {
            DownloadWorkKind.TRANSFER
        }

    private suspend fun liveTaskIds(batchId: String): List<String> =
        coordinator().ownedTaskIds(batchId).filter { id ->
            val snap = repository.transact { snapshot(id) } ?: return@filter false
            val t = snap.task
            !t.removed && !t.userPaused && !t.userCancelled &&
                !(t.phase == DownloadPhase.COMPLETE && t.completion == CompletionKind.FULL)
        }

    private suspend fun lookup(taskId: String): TaskContext? {
        val snap = repository.transact { snapshot(taskId) } ?: return null
        val batchId = coordinator().batchIdForTask(taskId) ?: return null
        val ids = coordinator().ownedTaskIds(batchId)
        return TaskContext(snap.task, snap.schedule, batchId, ids)
    }

    private data class TaskContext(
        val task: DownloadTask,
        val schedule: ScheduleRecord?,
        val batchId: String,
        val taskIds: List<String>,
    )
}

private fun DownloadNotificationPort.notifyPrompt(batchId: String) {
    notify(
        DownloadWorkNames.notificationId(batchId),
        DownloadNotificationPayload(
            batchId = batchId,
            title = batchId,
            text = "",
            progress = -1,
            ongoing = true,
            complete = false,
            showPause = true,
            showCancel = true,
            showResume = false,
        ),
    )
}
