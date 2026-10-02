package com.hhst.youtubelite.downloader.data

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.hhst.youtubelite.downloader.core.AssetSnapshot
import com.hhst.youtubelite.downloader.core.BatchStatsCalculator
import com.hhst.youtubelite.downloader.core.BatchView
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class RoomDownloadRepository(
    private val db: DownloaderDatabase,
) : DownloadRepository {
    private val dao = db.downloads()

    override suspend fun <T> transact(block: suspend DownloadSession.() -> T): T =
        db.withTransaction { RoomSession(dao).block() }

    override fun observeDownloads(filter: DownloadFilter): Flow<List<TaskSnapshot>> =
        callbackFlow {
            suspend fun push() { send(transact { downloads(filter) }) }
            val observer = graphObserver { launch { push() } }
            db.invalidationTracker.addObserver(observer)
            push()
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }

    override fun observeBatch(batchId: String): Flow<BatchView?> =
        callbackFlow {
            suspend fun push() { send(transact { batchView(batchId) }) }
            val observer = graphObserver { launch { push() } }
            db.invalidationTracker.addObserver(observer)
            push()
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }

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

    override suspend fun getBatch(id: String): DownloadBatch? = dao.getBatch(id)?.toDomain()

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

    override suspend fun chunksForComponent(componentId: String): List<DownloadChunk> =
        dao.chunksForComponent(componentId).map { it.toDomain() }

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

    override suspend fun allPublish(): List<PublishRecord> =
        dao.allPublish().map { it.toDomain() }

    override suspend fun snapshot(taskId: String): TaskSnapshot? {
        val task = dao.getTask(taskId)?.toDomain() ?: return null
        val assets = dao.assetsForTask(taskId).map { entity ->
            val asset = entity.toDomain()
            val components = dao.componentsForAsset(asset.id).map { component ->
                ComponentSnapshot(
                    component = component.toDomain(),
                    chunks = dao.chunksForComponent(component.id).map { it.toDomain() },
                )
            }
            AssetSnapshot(
                asset = asset,
                components = components,
                publish = dao.publishForAsset(asset.id)?.toDomain(),
            )
        }
        return TaskSnapshot(
            task = task,
            assets = assets,
            schedule = dao.scheduleForTask(taskId)?.toDomain(),
            batchId = dao.itemsForTask(taskId).firstOrNull()?.batchId,
        )
    }

    override suspend fun batchView(batchId: String): BatchView? {
        val batch = dao.getBatch(batchId)?.toDomain() ?: return null
        val items = dao.itemsForBatch(batchId).map { it.toDomain() }
        val snaps = items.mapNotNull { item ->
            snapshot(item.taskId)?.let { ItemSnapshot(item, it) }
        }
        return BatchView(batch, snaps, BatchStatsCalculator.compute(snaps))
    }

    override suspend fun downloads(filter: DownloadFilter): List<TaskSnapshot> {
        val store = storeFromEntities(
            batches = dao.allBatches(),
            submissions = emptyList(),
            tasks = dao.allTasks(),
            items = dao.allItems(),
            assets = dao.allAssets(),
            components = dao.allComponents(),
            chunks = dao.allChunks(),
            publishes = dao.allPublish(),
            schedules = dao.allSchedules(),
        )
        return store.downloads(filter)
    }
}
