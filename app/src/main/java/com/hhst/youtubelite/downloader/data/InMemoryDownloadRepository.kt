package com.hhst.youtubelite.downloader.data

import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.DownloadAsset
import com.hhst.youtubelite.downloader.core.DownloadBatch
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadItem
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.PublishRecord
import com.hhst.youtubelite.downloader.core.ScheduleRecord
import com.hhst.youtubelite.downloader.core.TaskSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [DownloadRepository] for unit and instrumentation tests; production binds [RoomDownloadRepository]. */
class InMemoryDownloadRepository : DownloadRepository {
    private val mutex = Mutex()
    private val snapshots = MutableStateFlow(DownloadStore())

    override suspend fun <T> transact(block: suspend DownloadSession.() -> T): T =
        mutex.withLock {
            val working = snapshots.value.mutable()
            val result = MemorySession(working).block()
            snapshots.value = working.freeze()
            result
        }

    override fun observeDownloads(filter: DownloadFilter): Flow<List<TaskSnapshot>> =
        snapshots.map { it.downloads(filter) }.distinctUntilChanged()

    override fun observeBatch(batchId: String): Flow<BatchView?> =
        snapshots.map { it.batchView(batchId) }.distinctUntilChanged()

    override fun observeVideo(videoId: String): Flow<List<TaskSnapshot>> =
        snapshots.map { it.downloads(DownloadFilter(videoId = videoId)) }.distinctUntilChanged()
}

private class MemorySession(
    private val store: MutableDownloadStore,
) : DownloadSession {
    private val view get() = store.asStore()

    override suspend fun findSubmission(submissionId: String): EnqueueResult? =
        store.submissions[submissionId]

    override suspend fun saveSubmission(result: EnqueueResult) {
        store.submissions[result.submissionId] = result
    }

    override suspend fun insertBatch(batch: DownloadBatch) {
        store.batches[batch.id] = batch
    }

    override suspend fun insertTask(task: DownloadTask) {
        store.tasks[task.id] = task
    }

    override suspend fun updateTask(task: DownloadTask) {
        store.tasks[task.id] = task
    }

    override suspend fun getTask(id: String): DownloadTask? = store.tasks[id]

    override suspend fun findLiveTaskByFingerprint(fingerprint: String): DownloadTask? =
        store.tasks.values
            .filter {
                it.configFingerprint == fingerprint &&
                    !it.removed &&
                    !it.userCancelled &&
                    it.status != DownloadStatus.CANCELLED
            }
            .maxByOrNull { it.createdAt }

    override suspend fun allTasks(): List<DownloadTask> = store.tasks.values.toList()

    override suspend fun insertItem(item: DownloadItem) {
        store.items[item.id] = item
    }

    override suspend fun getItem(id: String): DownloadItem? = store.items[id]

    override suspend fun itemsForBatch(batchId: String): List<DownloadItem> =
        store.items.values.filter { it.batchId == batchId }.sortedBy { it.position }

    override suspend fun itemsForTask(taskId: String): List<DownloadItem> =
        store.items.values.filter { it.taskId == taskId }

    override suspend fun deleteItem(id: String) {
        store.items.remove(id)
    }

    override suspend fun insertAsset(asset: DownloadAsset) {
        store.assets[asset.id] = asset
    }

    override suspend fun updateAsset(asset: DownloadAsset) {
        store.assets[asset.id] = asset
    }

    override suspend fun assetsForTask(taskId: String): List<DownloadAsset> =
        store.assets.values.filter { it.taskId == taskId }

    override suspend fun insertComponent(component: InputComponent) {
        store.components[component.id] = component
    }

    override suspend fun updateComponent(component: InputComponent) {
        store.components[component.id] = component
    }

    override suspend fun componentsForAsset(assetId: String): List<InputComponent> =
        store.components.values.filter { it.assetId == assetId }

    override suspend fun insertChunk(chunk: DownloadChunk) {
        store.chunks[chunk.id] = chunk
    }

    override suspend fun deleteUnverifiedChunks(componentId: String) {
        store.chunks.entries.removeAll { it.value.componentId == componentId && !it.value.verified }
    }

    override suspend fun deleteChunks(componentId: String) {
        store.chunks.entries.removeAll { it.value.componentId == componentId }
    }

    override suspend fun insertPublish(record: PublishRecord) {
        store.publishes[record.id] = record
    }

    override suspend fun updatePublish(record: PublishRecord) {
        store.publishes[record.id] = record
    }

    override suspend fun publishForAsset(assetId: String): PublishRecord? =
        store.publishes.values.firstOrNull { it.assetId == assetId }

    override suspend fun insertSchedule(record: ScheduleRecord) {
        store.schedules[record.id] = record
    }

    override suspend fun updateSchedule(record: ScheduleRecord) {
        store.schedules[record.id] = record
    }

    override suspend fun scheduleForTask(taskId: String): ScheduleRecord? =
        store.schedules.values.firstOrNull { it.taskId == taskId }

    override suspend fun allSchedules(): List<ScheduleRecord> = store.schedules.values.toList()

    override suspend fun snapshot(taskId: String): TaskSnapshot? = view.taskSnapshot(taskId)

    override suspend fun batchView(batchId: String): BatchView? = view.batchView(batchId)

    override suspend fun downloads(filter: DownloadFilter): List<TaskSnapshot> = view.downloads(filter)
}
