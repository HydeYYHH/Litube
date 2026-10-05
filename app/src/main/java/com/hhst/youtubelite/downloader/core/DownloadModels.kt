package com.hhst.youtubelite.downloader.core

/** Pipeline stage. Orthogonal to [DownloadStatus]. */
enum class DownloadPhase {
    RESOLVE,
    TRANSFER,
    WAITING_PROCESS,
    MERGE_VERIFY,
    SAVE,
    COMPLETE,
}

/** Runtime / interruptible state. Orthogonal to [DownloadPhase]. */
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSING,
    PAUSED,
    WAITING_NETWORK,
    WAITING_SYSTEM,
    FAILED,
    CANCELLED,
}

enum class FileAvailability {
    EXISTS,
    MISSING,
    INACCESSIBLE,
}

enum class AssetKind {
    VIDEO,
    AUDIO,
    SUBTITLE,
    COVER,
}

enum class InputComponentKind {
    VIDEO,
    AUDIO,
}

enum class RemoveMode {
    RECORD_ONLY,
    RECORD_AND_FILES,
}

enum class CompletionKind {
    NONE,
    PARTIAL,
    FULL,
}

enum class BatchSource {
    VIDEO,
    PLAYLIST,
    QUEUE,
    MIX,
    SHARE,
}

enum class PublishPhase {
    PENDING,
    IN_PROGRESS,
    PUBLISHED,
    FAILED,
    DELETE_INTENT,
    DELETED,
}

/** How a command selects work. */
sealed interface DownloadTarget {
    data class Task(val taskId: String) : DownloadTarget
    data class Item(val itemId: String) : DownloadTarget
    data class Batch(val batchId: String) : DownloadTarget
}

data class DownloadConfig(
    val videoQuality: String? = null,
    val audioOnly: Boolean = false,
    val audioTrack: String? = null,
    val includeSubtitle: Boolean = false,
    val subtitleLanguage: String? = null,
    val includeCover: Boolean = false,
    val fileNameTemplate: String? = null,
    /** Subtitle/cover without a video or audio asset. */
    val attachmentsOnly: Boolean = false,
    /**
     * The itag is a selection hint only — never identity; re-validate
     * against the current catalog and omit from [fingerprint].
     */
    val videoItagHint: Int? = null,
    val audioItagHint: Int? = null,
) {
    /** Stable business identity of this config (no Gson key-order). */
    fun fingerprint(videoId: String): String = listOf(
        videoId,
        videoQuality.orEmpty(),
        if (audioOnly) "1" else "0",
        audioTrack.orEmpty(),
        if (includeSubtitle) "1" else "0",
        subtitleLanguage.orEmpty(),
        if (includeCover) "1" else "0",
        fileNameTemplate.orEmpty(),
        if (attachmentsOnly) "1" else "0",
    ).joinToString("|")
}



data class DownloadRequest(
    val videoId: String,
    val title: String = "",
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val config: DownloadConfig = DownloadConfig(),
)

data class BatchSnapshot(
    val source: BatchSource,
    val name: String,
    val items: List<DownloadRequest>,
    val config: DownloadConfig = DownloadConfig(),
)

data class BatchSelection(
    val indexes: Set<Int>,
    val configOverrides: Map<Int, DownloadConfig> = emptyMap(),
)

data class TaskRef(
    val taskId: String,
    val videoId: String,
    val owned: Boolean,
)

data class EnqueueResult(
    val newCount: Int,
    val skippedCount: Int,
    val existing: List<TaskRef>,
    val created: List<TaskRef> = emptyList(),
    val batchId: String,
    val submissionId: String,
)

data class DownloadFilter(
    val videoId: String? = null,
    val phases: Set<DownloadPhase>? = null,
    val statuses: Set<DownloadStatus>? = null,
    val includeRemoved: Boolean = false,
)

data class DownloadBatch(
    val id: String,
    val source: BatchSource,
    val name: String,
    val config: DownloadConfig,
    val createdAt: Long,
)

data class DownloadItem(
    val id: String,
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

data class DownloadTask(
    val id: String,
    val videoId: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val config: DownloadConfig,
    val configFingerprint: String,
    val phase: DownloadPhase,
    val status: DownloadStatus,
    val fileAvailability: FileAvailability,
    val completion: CompletionKind,
    val executionGeneration: Long,
    val userPaused: Boolean,
    val userCancelled: Boolean,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val removed: Boolean = false,
)

data class DownloadAsset(
    val id: String,
    val taskId: String,
    val kind: AssetKind,
    val phase: DownloadPhase,
    val status: DownloadStatus,
    val fileAvailability: FileAvailability,
    val published: Boolean,
    val failed: Boolean,
    val outputName: String?,
    val publishedUri: String?,
    val errorMessage: String?,
    val mimeType: String? = null,
)

data class InputComponent(
    val id: String,
    val assetId: String,
    val kind: InputComponentKind,
    val mimeType: String? = null,
    val expectedBytes: Long? = null,
    val container: String? = null,
    val codec: String? = null,
    val audioTrackKey: String? = null,
    val resourceIdentity: String? = null,
    val needsRedownload: Boolean = false,
)

/** Generation-guarded resolve payload written onto component/asset rows. */
data class ResolvedComponentUpdate(
    val assetKind: AssetKind,
    val componentKind: InputComponentKind,
    val mimeType: String? = null,
    val expectedBytes: Long? = null,
    val container: String? = null,
    val codec: String? = null,
    val audioTrackKey: String? = null,
    val resourceIdentity: String? = null,
    val outputName: String? = null,
    val assetMimeType: String? = null,
)

data class DownloadChunk(
    val id: String,
    val componentId: String,
    val startByte: Long,
    val endByte: Long,
    val receivedBytes: Long,
    val verified: Boolean,
    val tempPath: String? = null,
    val checksum: String? = null,
)

data class PublishRecord(
    val id: String,
    val assetId: String,
    val tempFiles: List<String> = emptyList(),
    val targetUri: String? = null,
    val phase: PublishPhase = PublishPhase.PENDING,
    val keepOnCancel: Boolean = false,
    val errorMessage: String? = null,
)

data class ScheduleRecord(
    val id: String,
    val taskId: String,
    val uniqueWorkName: String,
    val pending: Boolean,
    val reason: String,
    val createdAt: Long,
    val systemJobId: Int? = null,
    val backend: String = "",
    val workKind: String = "",
)

data class ComponentSnapshot(
    val component: InputComponent,
    val chunks: List<DownloadChunk>,
)

data class AssetSnapshot(
    val asset: DownloadAsset,
    val components: List<ComponentSnapshot>,
    val publish: PublishRecord?,
)

data class TaskSnapshot(
    val task: DownloadTask,
    val assets: List<AssetSnapshot>,
    val schedule: ScheduleRecord?,
    val batchId: String? = null,
)

data class ItemSnapshot(
    val item: DownloadItem,
    val task: TaskSnapshot,
) {
    val skipped: Boolean get() = item.skipped
    val owned: Boolean get() = item.owned
}

data class BatchStats(
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val skipped: Int = 0,
    val running: Int = 0,
    val queued: Int = 0,
    val paused: Int = 0,
    val total: Int = 0,
)

data class BatchView(
    val batch: DownloadBatch,
    val items: List<ItemSnapshot>,
    val stats: BatchStats,
)

object DownloadLimits {
    const val MAX_SNAPSHOT_ITEMS = 500
    const val MAX_SNAPSHOT_BYTES = 1 * 1024 * 1024
    /** Native queue cap; [player.queue.QueueRepository] stays at 50. */
    const val NATIVE_QUEUE_CAP = 50
}

object BatchStatsCalculator {
    fun compute(items: List<ItemSnapshot>): BatchStats {
        var completed = 0
        var failed = 0
        var cancelled = 0
        var skipped = 0
        var running = 0
        var queued = 0
        var paused = 0
        for (item in items) {
            if (item.skipped) {
                skipped++
                continue
            }
            val task = item.task.task
            when {
                task.userCancelled || task.status == DownloadStatus.CANCELLED -> cancelled++
                task.completion == CompletionKind.FULL &&
                    task.phase == DownloadPhase.COMPLETE -> completed++
                task.status == DownloadStatus.FAILED -> failed++
                task.status == DownloadStatus.PAUSED ||
                    task.status == DownloadStatus.PAUSING ||
                    task.userPaused -> paused++
                task.status == DownloadStatus.RUNNING ||
                    task.status == DownloadStatus.WAITING_NETWORK ||
                    task.status == DownloadStatus.WAITING_SYSTEM -> running++
                else -> queued++
            }
        }
        return BatchStats(
            completed = completed,
            failed = failed,
            cancelled = cancelled,
            skipped = skipped,
            running = running,
            queued = queued,
            paused = paused,
            total = items.size,
        )
    }
}
