package com.hhst.youtubelite.downloader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "dl_batch")
data class BatchEntity(
    @PrimaryKey val id: String,
    val source: String,
    val name: String,
    val configJson: String,
    val createdAt: Long,
)

@Entity(tableName = "dl_submission")
data class SubmissionEntity(
    @PrimaryKey val submissionId: String,
    val resultJson: String,
    val createdAt: Long,
)

@Entity(
    tableName = "dl_task",
    indices = [Index(value = ["configFingerprint"]), Index(value = ["videoId"])],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val videoId: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val configJson: String,
    val configFingerprint: String,
    val phase: String,
    val status: String,
    val fileAvailability: String,
    val completion: String,
    val executionGeneration: Long,
    val userPaused: Boolean,
    val userCancelled: Boolean,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val removed: Boolean,
)

@Entity(
    tableName = "dl_item",
    foreignKeys = [
        ForeignKey(
            entity = BatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("batchId"), Index("taskId"), Index("videoId")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val taskId: String,
    val videoId: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val position: Int,
    val owned: Boolean,
    val skipped: Boolean,
)

@Entity(
    tableName = "dl_asset",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val kind: String,
    val phase: String,
    val status: String,
    val fileAvailability: String,
    val published: Boolean,
    val failed: Boolean,
    val outputName: String?,
    val publishedUri: String?,
    val errorMessage: String?,
    val mimeType: String? = null,
)

@Entity(
    tableName = "dl_component",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assetId")],
)
data class ComponentEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val kind: String,
    val mimeType: String?,
    val expectedBytes: Long?,
    val container: String? = null,
    val codec: String? = null,
    val audioTrackKey: String? = null,
    val resourceIdentity: String? = null,
    val needsRedownload: Boolean = false,
)

@Entity(
    tableName = "dl_chunk",
    foreignKeys = [
        ForeignKey(
            entity = ComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["componentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("componentId")],
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val componentId: String,
    val startByte: Long,
    val endByte: Long,
    val receivedBytes: Long,
    val verified: Boolean,
    val tempPath: String?,
    val checksum: String? = null,
)

@Entity(
    tableName = "dl_publish",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["assetId"], unique = true)],
)
data class PublishEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val tempFilesJson: String,
    val targetUri: String?,
    val phase: String,
    val keepOnCancel: Boolean,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "dl_schedule",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId"], unique = true)],
)
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val uniqueWorkName: String,
    val pending: Boolean,
    val reason: String,
    val createdAt: Long,
    val systemJobId: Int? = null,
    val backend: String = "",
    val workKind: String = "",
)
