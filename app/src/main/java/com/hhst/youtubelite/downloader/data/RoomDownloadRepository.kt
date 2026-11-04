package com.hhst.youtubelite.downloader.data

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.hhst.youtubelite.downloader.core.BatchStatsCalculator
import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.AssetSnapshot
import com.hhst.youtubelite.downloader.core.ComponentSnapshot
import com.hhst.youtubelite.downloader.core.DownloadAsset
import com.hhst.youtubelite.downloader.core.DownloadBatch
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadItem
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.ItemSnapshot
import com.hhst.youtubelite.downloader.core.PublishRecord
import com.hhst.youtubelite.downloader.core.ScheduleRecord
import com.hhst.youtubelite.downloader.core.TaskSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import com.hhst.youtubelite.diagnostics.*
import kotlin.coroutines.coroutineContext

class RoomDownloadRepository(
    private val db: DownloaderDatabase,
) : DownloadRepository {
    private val dao = db.downloads()

    override suspend fun <T> transact(block: suspend DownloadSession.() -> T): T = try {
        db.withTransaction { RoomSession(dao).block() }
    } catch (failure: Throwable) {
        if (failure !is kotlinx.coroutines.CancellationException) AppLog.event(AppLog.Category.STORAGE, "database_transaction_failed",
            mapOf("database" to "downloads"), failure, context = coroutineContext[DiagnosticCoroutineContext]?.diagnostic)
        throw failure
    }

    override fun observeDownloads(filter: DownloadFilter): Flow<List<TaskSnapshot>> =
        observeGraph { downloads(filter) }

    override fun observeBatch(batchId: String): Flow<BatchView?> =
        observeGraph { batchView(batchId) }

    // One query at a time, with at most one pending refresh. Conflate before
    // reading Room, so a slow snapshot cannot accumulate obsolete transactions.
    private fun <T> observeGraph(read: suspend DownloadSession.() -> T): Flow<T> =
        callbackFlow {
            val observer = graphObserver { trySend(Unit) }
            db.invalidationTracker.addObserver(observer)
            trySend(Unit)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }.buffer(Channel.CONFLATED).map { transact(read) }

    override fun observeVideo(videoId: String): Flow<List<TaskSnapshot>> =
        observeDownloads(DownloadFilter(videoId = videoId))

    private fun graphObserver(onChange: () -> Unit) = object : InvalidationTracker.Observer(
        "dl_task",
        "dl_item",
        "dl_asset",
        "dl_component",
        "dl_chunk",
        "dl_publish",
        "dl_schedule",
        "dl_batch",
    ) {
        override fun onInvalidated(tables: Set<String>) = onChange()
    }
}

private class RoomSession(
    private val dao: DownloadsDao,
) : DownloadSession {
    override suspend fun findSubmission(submissionId: String): EnqueueResult? =
        dao.getSubmission(submissionId)?.let { DownloadJson.enqueue(it.resultJson) }

    override suspend fun saveSubmission(result: EnqueueResult) {
        dao.insertSubmission(
            SubmissionEntity(
                submissionId = result.submissionId,
                resultJson = DownloadJson.enqueueJson(result),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun insertBatch(batch: DownloadBatch) {
        dao.insertBatch(batch.toEntity())
    }

    override suspend fun insertTask(task: DownloadTask) {
        dao.insertTask(task.toEntity())
    }

    override suspend fun updateTask(task: DownloadTask) {
        dao.updateTask(task.toEntity())
    }

    override suspend fun getTask(id: String): DownloadTask? = dao.getTask(id)?.toDomain()

    override suspend fun findLiveTaskByFingerprint(fingerprint: String): DownloadTask? =
        dao.findLiveTaskByFingerprint(fingerprint)?.toDomain()

    override suspend fun allTasks(): List<DownloadTask> = dao.allTasks().map { it.toDomain() }

    override suspend fun insertItem(item: DownloadItem) {
        dao.insertItem(item.toEntity())
    }

    override suspend fun getItem(id: String): DownloadItem? = dao.getItem(id)?.toDomain()

    override suspend fun itemsForBatch(batchId: String): List<DownloadItem> =
        dao.itemsForBatch(batchId).map { it.toDomain() }

    override suspend fun itemsForTask(taskId: String): List<DownloadItem> =
        dao.itemsForTask(taskId).map { it.toDomain() }

    override suspend fun deleteItem(id: String) {
        dao.deleteItem(id)
    }

    override suspend fun insertAsset(asset: DownloadAsset) {
        dao.insertAsset(asset.toEntity())
    }

    override suspend fun updateAsset(asset: DownloadAsset) {
        dao.updateAsset(asset.toEntity())
    }

    override suspend fun assetsForTask(taskId: String): List<DownloadAsset> =
        dao.assetsForTask(taskId).map { it.toDomain() }

    override suspend fun insertComponent(component: InputComponent) {
        dao.insertComponent(component.toEntity())
    }

    override suspend fun updateComponent(component: InputComponent) {
        dao.updateComponent(component.toEntity())
    }

    override suspend fun componentsForAsset(assetId: String): List<InputComponent> =
        dao.componentsForAsset(assetId).map { it.toDomain() }

    override suspend fun insertChunk(chunk: DownloadChunk) {
        dao.insertChunk(chunk.toEntity())
    }

    override suspend fun deleteUnverifiedChunks(componentId: String) {
        dao.deleteUnverifiedChunks(componentId)
    }

    override suspend fun deleteChunks(componentId: String) {
        dao.deleteChunks(componentId)
    }

    override suspend fun insertPublish(record: PublishRecord) {
        dao.insertPublish(record.toEntity())
    }

    override suspend fun updatePublish(record: PublishRecord) {
        dao.updatePublish(record.toEntity())
    }

    override suspend fun publishForAsset(assetId: String): PublishRecord? =
        dao.publishForAsset(assetId)?.toDomain()

    override suspend fun insertSchedule(record: ScheduleRecord) {
        dao.insertSchedule(record.toEntity())
    }

    override suspend fun updateSchedule(record: ScheduleRecord) {
        dao.updateSchedule(record.toEntity())
    }

    override suspend fun scheduleForTask(taskId: String): ScheduleRecord? =
        dao.scheduleForTask(taskId)?.toDomain()

    override suspend fun allSchedules(): List<ScheduleRecord> =
        dao.allSchedules().map { it.toDomain() }

    override suspend fun snapshot(taskId: String): TaskSnapshot? {
        val task = dao.getTask(taskId) ?: return null
        return taskSnapshots(listOf(task))[taskId]
    }

    override suspend fun batchView(batchId: String): BatchView? {
        val batch = dao.getBatch(batchId)?.toDomain() ?: return null
        val items = dao.itemsForBatch(batchId).map { it.toDomain() }
        val tasks = items.map { it.taskId }.distinct().chunked(QUERY_BATCH_SIZE)
            .flatMap { dao.tasksByIds(it) }
        val snapshots = taskSnapshots(tasks)
        val snaps = items.mapNotNull { item ->
            snapshots[item.taskId]?.takeUnless { it.task.removed }?.let { ItemSnapshot(item, it) }
        }
        return BatchView(batch, snaps, BatchStatsCalculator.compute(snaps))
    }

    override suspend fun downloads(filter: DownloadFilter): List<TaskSnapshot> {
        val tasks = dao.tasksForDownloads(
            videoId = filter.videoId, includeRemoved = filter.includeRemoved,
            allPhases = filter.phases == null, phases = filter.phases.orEmpty().map { it.name },
            allStatuses = filter.statuses == null, statuses = filter.statuses.orEmpty().map { it.name },
        )
        return taskSnapshots(tasks).values.sortedByDescending { it.task.createdAt }
    }

    private suspend fun taskSnapshots(tasks: List<TaskEntity>): Map<String, TaskSnapshot> = buildMap {
        // Stay below SQLite's bind limit, even for large download histories.
        for (group in tasks.chunked(QUERY_BATCH_SIZE)) {
            val ids = group.map { it.id }
            val items = dao.itemsForTasks(ids).groupBy { it.taskId }
            val assets = dao.assetsForTasks(ids).map { it.toDomain() }.groupBy { it.taskId }
            val components = dao.componentsForTasks(ids).map { it.toDomain() }.groupBy { it.assetId }
            val chunks = dao.chunksForTasks(ids).map { it.toDomain() }.groupBy { it.componentId }
            val publishes = dao.publishesForTasks(ids).map { it.toDomain() }.associateBy { it.assetId }
            val schedules = dao.schedulesForTasks(ids).map { it.toDomain() }.associateBy { it.taskId }
            for (task in group) {
                put(task.id, TaskSnapshot(
                    task = task.toDomain(),
                    assets = assets[task.id].orEmpty().map { asset ->
                        AssetSnapshot(asset, components[asset.id].orEmpty().map { component ->
                            ComponentSnapshot(component, chunks[component.id].orEmpty())
                        }, publishes[asset.id])
                    },
                    schedule = schedules[task.id],
                    batchId = items[task.id]?.firstOrNull()?.batchId,
                ))
            }
        }
    }

    private companion object {
        const val QUERY_BATCH_SIZE = 900
    }
}
