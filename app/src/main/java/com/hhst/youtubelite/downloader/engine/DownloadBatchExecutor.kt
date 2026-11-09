package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.data.DownloadRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Runs [DownloadEngine] for a batch under the current generation. Workers and
 * UIDT must call this instead of writing Room themselves.
 */
class DownloadBatchExecutor(
    private val engine: DownloadEngine,
    private val repository: DownloadRepository,
    private val coordinator: DownloadCoordinator,
    private val scheduler: BackgroundDownloadScheduler,
    maxConcurrent: Int = DownloadSettings.MAX_CONCURRENT_ITEMS,
) {
    private val gate = Semaphore(maxConcurrent.coerceAtLeast(1))
    private val runs = mutableMapOf<Pair<String, DownloadWorkKind>, BatchRun>()

    suspend fun executeTransfer(batchId: String) = runExclusive(batchId, DownloadWorkKind.TRANSFER) {
        runOwned(batchId) { transferOne(it, batchId) }
        drain(batchId, drainable = ::drainableTransfer) { transferOne(it, batchId) }
    }

    suspend fun executeFinalize(batchId: String) = runExclusive(batchId, DownloadWorkKind.FINALIZE) {
        runOwned(batchId) { finalizeOne(it) }
        drain(batchId, drainable = ::drainableFinalize) { finalizeOne(it) }
    }

    private suspend fun runExclusive(batchId: String, kind: DownloadWorkKind, block: suspend () -> Unit) {
        val key = batchId to kind
        val run = synchronized(runs) { runs.getOrPut(key, ::BatchRun).also { it.users++ } }
        try {
            run.mutex.withLock { block() }
        } finally {
            synchronized(runs) {
                if (--run.users == 0) runs.remove(key)
            }
        }
    }

    private class BatchRun {
        val mutex = Mutex()
        var users = 0
    }

    private suspend fun runOwned(batchId: String, block: suspend (String) -> Unit) {
        val ids = coordinator.ownedTaskIds(batchId)
        coroutineScope {
            ids.map { taskId ->
                async {
                    gate.withPermit { block(taskId) }
                }
            }.awaitAll()
        }
    }

    /**
     * Late arrivals (resume/retry/first transfer finishing while this run was in flight)
     * must be picked up here: duplicate enqueues for the batch collapse to the running worker.
     */
    private suspend fun drain(
        batchId: String,
        drainable: (DownloadTask) -> Boolean,
        block: suspend (String) -> Unit,
    ) {
        var guard = 0
        while (guard++ < 8) {
            val pending = coordinator.ownedTaskIds(batchId).filter { id ->
                val snap = repository.transact { snapshot(id) } ?: return@filter false
                drainable(snap.task)
            }
            if (pending.isEmpty()) return
            coroutineScope {
                pending.map { taskId ->
                    async {
                        gate.withPermit { block(taskId) }
                    }
                }.awaitAll()
            }
        }
    }

    suspend fun onSystemStop(taskId: String) {
        val snap = repository.transact { snapshot(taskId) } ?: return
        val task = snap.task
        if (task.userPaused || task.userCancelled || task.removed) return
        coordinator.reportExecution(
            taskId,
            task.executionGeneration,
            DownloadStatus.WAITING_SYSTEM,
        )
    }

    private suspend fun transferOne(taskId: String, batchId: String) {
        val snap = repository.transact { snapshot(taskId) } ?: return
        if (!runnable(snap.task)) return
        if (needsFinalizeOnly(snap.task)) {
            scheduler.enqueueFinalize(batchId)
            return
        }
        engine.runTransfer(taskId)
        val after = repository.transact { snapshot(taskId) } ?: return
        if (!runnable(after.task) && after.task.userPaused) return
        if (needsFinalizeOnly(after.task) ||
            after.task.phase == DownloadPhase.WAITING_PROCESS
        ) {
            scheduler.enqueueFinalize(batchId)
        }
    }

    private suspend fun finalizeOne(taskId: String) {
        val snap = repository.transact { snapshot(taskId) } ?: return
        if (!runnable(snap.task)) return
        // Stage admission: a batch mate still in TRANSFER must never be finalized — bytes are not on disk yet.
        if (!needsFinalizeOnly(snap.task)) return
        engine.runFinalize(taskId)
    }

    private fun needsFinalizeOnly(task: DownloadTask): Boolean =
        workKindForPhase(task.phase) == DownloadWorkKind.FINALIZE

    private fun drainableTransfer(task: DownloadTask): Boolean =
        runnable(task) && task.status == DownloadStatus.QUEUED &&
            (task.phase == DownloadPhase.RESOLVE || task.phase == DownloadPhase.TRANSFER)

    private fun drainableFinalize(task: DownloadTask): Boolean =
        runnable(task) && needsFinalizeOnly(task)

    private fun runnable(task: DownloadTask): Boolean =
        !task.removed && !task.userPaused && !task.userCancelled &&
            task.status != DownloadStatus.CANCELLED &&
            task.status != DownloadStatus.PAUSED &&
            !(task.phase == DownloadPhase.COMPLETE && task.completion == CompletionKind.FULL)
}
