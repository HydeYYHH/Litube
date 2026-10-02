package com.hhst.youtubelite.downloader.data

import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.DownloadAsset
import com.hhst.youtubelite.downloader.core.DownloadBatch
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadItem
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.PublishRecord
import com.hhst.youtubelite.downloader.core.ScheduleRecord
import com.hhst.youtubelite.downloader.core.TaskSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Transactional task store. UI never talks to this directly; [com.hhst.youtubelite.downloader.core.DownloadCoordinator]
 * is the command surface.
 */
interface DownloadRepository {
    suspend fun <T> transact(block: suspend DownloadSession.() -> T): T

    fun observeDownloads(filter: DownloadFilter): Flow<List<TaskSnapshot>>
    fun observeBatch(batchId: String): Flow<BatchView?>
    fun observeVideo(videoId: String): Flow<List<TaskSnapshot>>
}

interface DownloadSession {
    suspend fun findSubmission(submissionId: String): EnqueueResult?
    suspend fun saveSubmission(result: EnqueueResult)

    suspend fun insertBatch(batch: DownloadBatch)
    suspend fun getBatch(id: String): DownloadBatch?

    suspend fun insertTask(task: DownloadTask)
    suspend fun updateTask(task: DownloadTask)
    suspend fun getTask(id: String): DownloadTask?
    suspend fun findLiveTaskByFingerprint(fingerprint: String): DownloadTask?
    suspend fun allTasks(): List<DownloadTask>

    suspend fun insertItem(item: DownloadItem)
    suspend fun getItem(id: String): DownloadItem?
    suspend fun itemsForBatch(batchId: String): List<DownloadItem>
    suspend fun itemsForTask(taskId: String): List<DownloadItem>
    suspend fun deleteItem(id: String)

    suspend fun insertAsset(asset: DownloadAsset)
    suspend fun updateAsset(asset: DownloadAsset)
    suspend fun assetsForTask(taskId: String): List<DownloadAsset>

    suspend fun insertComponent(component: InputComponent)
    suspend fun updateComponent(component: InputComponent)
    suspend fun componentsForAsset(assetId: String): List<InputComponent>

    suspend fun insertChunk(chunk: DownloadChunk)
    suspend fun chunksForComponent(componentId: String): List<DownloadChunk>
    suspend fun deleteUnverifiedChunks(componentId: String)
    suspend fun deleteChunks(componentId: String)

    suspend fun insertPublish(record: PublishRecord)
    suspend fun updatePublish(record: PublishRecord)
    suspend fun publishForAsset(assetId: String): PublishRecord?

    suspend fun insertSchedule(record: ScheduleRecord)
    suspend fun updateSchedule(record: ScheduleRecord)
    suspend fun scheduleForTask(taskId: String): ScheduleRecord?
    suspend fun allSchedules(): List<ScheduleRecord>
    suspend fun allPublish(): List<PublishRecord>

    suspend fun snapshot(taskId: String): TaskSnapshot?
    suspend fun batchView(batchId: String): BatchView?
    suspend fun downloads(filter: DownloadFilter): List<TaskSnapshot>
}
