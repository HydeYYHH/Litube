package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.AssetKind
import kotlinx.coroutines.CompletableDeferred
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.DownloadPublishedUris
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import java.io.File

/**
 * Room vs system jobs vs temp files vs publish records.
 *
 * - submitted-but-unscheduled (QUEUED + pending, no system job) is repaired
 * - scheduled-but-missing-system-job is repaired unless waiting-system / force-stop
 * - an already-active system job is attached (restore), never double-enqueued
 * - WAITING_SYSTEM is not treated as user-resume
 */
class DownloadStartupReconciler(
    private val repository: DownloadRepository,
    private val coordinator: DownloadCoordinator,
    private val scheduler: BackgroundDownloadScheduler,
    private val publisher: DownloadPublisher,
    private val directories: DownloadDirectories,
) {
    private val initialized = CompletableDeferred<Unit>()
    suspend fun awaitInitialization() = initialized.await()

    suspend fun reconcile() {
        try {
        coordinator.onProcessRestore()
        reconcilePublish()
        reconcilePublishedFiles()
        reconcileTemp()
        reconcileSchedules()
        initialized.complete(Unit)
        } catch (failure: Throwable) {
            initialized.completeExceptionally(failure)
            throw failure
        }
    }

    private suspend fun reconcilePublish() {
        val pending = repository.transact {
            val out = mutableListOf<Triple<String, AssetKind, PublishRequest>>()
            allTasks().forEach { task ->
                if (task.userPaused || task.userCancelled || task.removed) return@forEach
                assetsForTask(task.id).forEach { asset ->
                    if (asset.published) return@forEach
                    val publish = publishForAsset(asset.id) ?: return@forEach
                    if (publish.phase != PublishPhase.IN_PROGRESS && publish.phase != PublishPhase.PENDING) {
                        return@forEach
                    }
                    val sourcePath = publish.tempFiles.firstOrNull() ?: return@forEach
                    val source = File(sourcePath)
                    if (!source.isFile) return@forEach
                    out += Triple(
                        task.id,
                        asset.kind,
                        PublishRequest(
                            publishId = publish.id,
                            assetId = asset.id,
                            displayName = asset.outputName ?: source.name,
                            mimeType = asset.mimeType ?: "application/octet-stream",
                            source = source,
                            existingUri = publish.targetUri ?: asset.publishedUri,
                            existingPhase = publish.phase,
                        ),
                    )
                }
            }
            out
        }
        pending.forEach { (taskId, kind, request) ->
            // Read the generation before publishing so a concurrent user
            // action can never make the old operation adopt a new one.
            val gen = repository.transact { getTask(taskId)?.executionGeneration } ?: return@forEach
            when (val result = publisher.publish(request)) {
                is PublishResult.Published -> {
                    coordinator.reportAssetPublished(taskId, gen, kind, result.uri)
                }
                else -> Unit
            }
        }
    }

    private suspend fun reconcilePublishedFiles() {
        data class Probe(
            val taskId: String,
            val generation: Long,
            val kind: AssetKind,
            val uri: String,
        )
        val probes = repository.transact {
            allTasks().flatMap { task ->
                if (task.removed) return@flatMap emptyList()
                assetsForTask(task.id).mapNotNull { asset ->
                    val uri = asset.publishedUri ?: return@mapNotNull null
                    if (!asset.published) return@mapNotNull null
                    Probe(task.id, task.executionGeneration, asset.kind, uri)
                }
            }
        }
        probes.forEach { probe ->
            val availability = when {
                !DownloadPublishedUris.isOpenable(probe.uri) -> FileAvailability.INACCESSIBLE
                publisher.exists(probe.uri) -> FileAvailability.EXISTS
                else -> FileAvailability.MISSING
            }
            coordinator.reportFileAvailability(probe.taskId, probe.generation, probe.kind, availability)
        }
    }

    private suspend fun reconcileTemp() {
        val tasks = repository.transact { allTasks() }
        tasks.forEach { task ->
            if (task.removed || task.userCancelled) {
                directories.deleteTask(task.id)
            }
        }
    }

    private suspend fun reconcileSchedules() {
        val schedules = repository.transact { allSchedules() }
        val tasks = repository.transact { allTasks().associateBy { it.id } }
        val seenBatches = mutableSetOf<String>()
        for (sched in schedules) {
            val task = tasks[sched.taskId] ?: continue
            val batchId = coordinator.batchIdForTask(task.id) ?: continue
            if (task.userPaused || task.userCancelled || task.removed) {
                scheduler.cancel(task.id)
                continue
            }
            if (task.status == DownloadStatus.FAILED || task.phase == DownloadPhase.COMPLETE) continue
            if (task.status == DownloadStatus.WAITING_SYSTEM || sched.reason == ScheduleReasons.SYSTEM) {
                continue
            }
            val kind = workKindForPhase(task.phase)
            if (!seenBatches.add("$batchId:${kind.name}")) continue
            if (scheduler.systemHasWork(batchId, kind)) {
                scheduler.noteRestored(batchId)
                continue
            }
            if (sched.pending &&
                task.status != DownloadStatus.PAUSED &&
                task.status != DownloadStatus.CANCELLED
            ) {
                scheduler.repair(task.id)
            }
        }
    }
}
