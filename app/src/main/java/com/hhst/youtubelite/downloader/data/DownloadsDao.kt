package com.hhst.youtubelite.downloader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DownloadsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(entity: BatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubmission(entity: SubmissionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(entity: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(entity: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(entity: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponent(entity: ComponentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(entity: ChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPublish(entity: PublishEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(entity: ScheduleEntity)

    @Update
    suspend fun updateTask(entity: TaskEntity)

    @Update
    suspend fun updateAsset(entity: AssetEntity)

    @Update
    suspend fun updateComponent(entity: ComponentEntity)

    @Update
    suspend fun updatePublish(entity: PublishEntity)

    @Update
    suspend fun updateSchedule(entity: ScheduleEntity)

    @Query("SELECT * FROM dl_batch WHERE id = :id")
    suspend fun getBatch(id: String): BatchEntity?

    @Query("SELECT * FROM dl_submission WHERE submissionId = :id")
    suspend fun getSubmission(id: String): SubmissionEntity?

    @Query("SELECT * FROM dl_task WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query(
        """
        SELECT * FROM dl_task
        WHERE configFingerprint = :fingerprint
          AND removed = 0
          AND userCancelled = 0
          AND status != 'CANCELLED'
        ORDER BY createdAt DESC
        LIMIT 1
        """,
    )
    suspend fun findLiveTaskByFingerprint(fingerprint: String): TaskEntity?

    @Query("SELECT * FROM dl_item WHERE id = :id")
    suspend fun getItem(id: String): ItemEntity?

    @Query("SELECT * FROM dl_item WHERE batchId = :batchId ORDER BY position ASC")
    suspend fun itemsForBatch(batchId: String): List<ItemEntity>

    @Query("SELECT * FROM dl_item WHERE taskId = :taskId")
    suspend fun itemsForTask(taskId: String): List<ItemEntity>

    @Query("DELETE FROM dl_item WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("SELECT * FROM dl_asset WHERE taskId = :taskId")
    suspend fun assetsForTask(taskId: String): List<AssetEntity>

    @Query("SELECT * FROM dl_component WHERE assetId = :assetId")
    suspend fun componentsForAsset(assetId: String): List<ComponentEntity>

    @Query("SELECT * FROM dl_chunk WHERE componentId = :componentId")
    suspend fun chunksForComponent(componentId: String): List<ChunkEntity>

    @Query("DELETE FROM dl_chunk WHERE componentId = :componentId AND verified = 0")
    suspend fun deleteUnverifiedChunks(componentId: String)

    @Query("DELETE FROM dl_chunk WHERE componentId = :componentId")
    suspend fun deleteChunks(componentId: String)

    @Query("SELECT * FROM dl_publish WHERE assetId = :assetId")
    suspend fun publishForAsset(assetId: String): PublishEntity?

    @Query("SELECT * FROM dl_schedule WHERE taskId = :taskId")
    suspend fun scheduleForTask(taskId: String): ScheduleEntity?

    @Query("SELECT * FROM dl_task")
    suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT * FROM dl_batch")
    suspend fun allBatches(): List<BatchEntity>

    @Query("SELECT * FROM dl_item")
    suspend fun allItems(): List<ItemEntity>

    @Query("SELECT * FROM dl_asset")
    suspend fun allAssets(): List<AssetEntity>

    @Query("SELECT * FROM dl_component")
    suspend fun allComponents(): List<ComponentEntity>

    @Query("SELECT * FROM dl_chunk")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT * FROM dl_publish")
    suspend fun allPublish(): List<PublishEntity>

    @Query("SELECT * FROM dl_schedule")
    suspend fun allSchedules(): List<ScheduleEntity>

}
