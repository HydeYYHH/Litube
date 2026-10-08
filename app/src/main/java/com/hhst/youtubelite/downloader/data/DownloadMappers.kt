package com.hhst.youtubelite.downloader.data

import com.google.gson.Gson
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.AssetSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.BatchStatsCalculator
import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.ComponentSnapshot
import com.hhst.youtubelite.downloader.core.DownloadAsset
import com.hhst.youtubelite.downloader.core.DownloadBatch
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadItem
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.ItemSnapshot
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRecord
import com.hhst.youtubelite.downloader.core.ScheduleRecord
import com.hhst.youtubelite.downloader.core.TaskSnapshot

internal object DownloadJson {
    private val gson: Gson = Gson()

    fun config(json: String): DownloadConfig =
        gson.fromJson(json, DownloadConfig::class.java) ?: DownloadConfig()

    fun configJson(config: DownloadConfig): String = gson.toJson(config)

    fun strings(json: String): List<String> {
        val array = gson.fromJson(json, Array<String>::class.java) ?: emptyArray()
        return array.toList()
    }

    fun stringsJson(list: List<String>): String = gson.toJson(list)

    fun enqueue(json: String): EnqueueResult = gson.fromJson(json, EnqueueResult::class.java)

    fun enqueueJson(result: EnqueueResult): String = gson.toJson(result)
}

internal fun BatchEntity.toDomain() = DownloadBatch(
    id = id,
    source = BatchSource.valueOf(source),
    name = name,
    config = DownloadJson.config(configJson),
    createdAt = createdAt,
)

internal fun DownloadBatch.toEntity() = BatchEntity(
    id = id,
    source = source.name,
    name = name,
    configJson = DownloadJson.configJson(config),
    createdAt = createdAt,
)

internal fun TaskEntity.toDomain() = DownloadTask(
    id = id,
    videoId = videoId,
    title = title,
    author = author,
    thumbnailUrl = thumbnailUrl,
    config = DownloadJson.config(configJson),
    configFingerprint = configFingerprint,
    phase = DownloadPhase.valueOf(phase),
    status = DownloadStatus.valueOf(status),
    fileAvailability = FileAvailability.valueOf(fileAvailability),
    completion = CompletionKind.valueOf(completion),
    executionGeneration = executionGeneration,
    userPaused = userPaused,
    userCancelled = userCancelled,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
    removed = removed,
)

internal fun DownloadTask.toEntity() = TaskEntity(
    id = id,
    videoId = videoId,
    title = title,
    author = author,
    thumbnailUrl = thumbnailUrl,
    configJson = DownloadJson.configJson(config),
    configFingerprint = configFingerprint,
    phase = phase.name,
    status = status.name,
    fileAvailability = fileAvailability.name,
    completion = completion.name,
    executionGeneration = executionGeneration,
    userPaused = userPaused,
    userCancelled = userCancelled,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
    removed = removed,
)

internal fun ItemEntity.toDomain() = DownloadItem(
    id = id,
    batchId = batchId,
    taskId = taskId,
    videoId = videoId,
    title = title,
    author = author,
    thumbnailUrl = thumbnailUrl,
    position = position,
    owned = owned,
    skipped = skipped,
)

internal fun DownloadItem.toEntity() = ItemEntity(
    id = id,
    batchId = batchId,
    taskId = taskId,
    videoId = videoId,
    title = title,
    author = author,
    thumbnailUrl = thumbnailUrl,
    position = position,
    owned = owned,
    skipped = skipped,
)

internal fun AssetEntity.toDomain() = DownloadAsset(
    id = id,
    taskId = taskId,
    kind = AssetKind.valueOf(kind),
    phase = DownloadPhase.valueOf(phase),
    status = DownloadStatus.valueOf(status),
    fileAvailability = FileAvailability.valueOf(fileAvailability),
    published = published,
    failed = failed,
    outputName = outputName,
    publishedUri = publishedUri,
    errorMessage = errorMessage,
    mimeType = mimeType,
)

internal fun DownloadAsset.toEntity() = AssetEntity(
    id = id,
    taskId = taskId,
    kind = kind.name,
    phase = phase.name,
    status = status.name,
    fileAvailability = fileAvailability.name,
    published = published,
    failed = failed,
    outputName = outputName,
    publishedUri = publishedUri,
    errorMessage = errorMessage,
    mimeType = mimeType,
)

internal fun ComponentEntity.toDomain() = InputComponent(
    id = id,
    assetId = assetId,
    kind = InputComponentKind.valueOf(kind),
    mimeType = mimeType,
    expectedBytes = expectedBytes,
    container = container,
    codec = codec,
    audioTrackKey = audioTrackKey,
    resourceIdentity = resourceIdentity,
    needsRedownload = needsRedownload,
)

internal fun InputComponent.toEntity() = ComponentEntity(
    id = id,
    assetId = assetId,
    kind = kind.name,
    mimeType = mimeType,
    expectedBytes = expectedBytes,
    container = container,
    codec = codec,
    audioTrackKey = audioTrackKey,
    resourceIdentity = resourceIdentity,
    needsRedownload = needsRedownload,
)

internal fun ChunkEntity.toDomain() = DownloadChunk(
    id = id,
    componentId = componentId,
    startByte = startByte,
    endByte = endByte,
    receivedBytes = receivedBytes,
    verified = verified,
    tempPath = tempPath,
    checksum = checksum,
)

internal fun DownloadChunk.toEntity() = ChunkEntity(
    id = id,
    componentId = componentId,
    startByte = startByte,
    endByte = endByte,
    receivedBytes = receivedBytes,
    verified = verified,
    tempPath = tempPath,
    checksum = checksum,
)

internal fun PublishEntity.toDomain() = PublishRecord(
    id = id,
    assetId = assetId,
    tempFiles = DownloadJson.strings(tempFilesJson),
    targetUri = targetUri,
    phase = PublishPhase.valueOf(phase),
    keepOnCancel = keepOnCancel,
    errorMessage = errorMessage,
)

internal fun PublishRecord.toEntity() = PublishEntity(
    id = id,
    assetId = assetId,
    tempFilesJson = DownloadJson.stringsJson(tempFiles),
    targetUri = targetUri,
    phase = phase.name,
    keepOnCancel = keepOnCancel,
    errorMessage = errorMessage,
)

internal fun ScheduleEntity.toDomain() = ScheduleRecord(
    id = id,
    taskId = taskId,
    uniqueWorkName = uniqueWorkName,
    pending = pending,
    reason = reason,
    createdAt = createdAt,
    systemJobId = systemJobId,
    backend = backend,
    workKind = workKind,
)

internal fun ScheduleRecord.toEntity() = ScheduleEntity(
    id = id,
    taskId = taskId,
    uniqueWorkName = uniqueWorkName,
    pending = pending,
    reason = reason,
    createdAt = createdAt,
    systemJobId = systemJobId,
    backend = backend,
    workKind = workKind,
)

internal data class DownloadStore(
    val batches: Map<String, DownloadBatch> = emptyMap(),
    val submissions: Map<String, EnqueueResult> = emptyMap(),
    val tasks: Map<String, DownloadTask> = emptyMap(),
    val items: Map<String, DownloadItem> = emptyMap(),
    val assets: Map<String, DownloadAsset> = emptyMap(),
    val components: Map<String, InputComponent> = emptyMap(),
    val chunks: Map<String, DownloadChunk> = emptyMap(),
    val publishes: Map<String, PublishRecord> = emptyMap(),
    val schedules: Map<String, ScheduleRecord> = emptyMap(),
) {
    fun mutable(): MutableDownloadStore = MutableDownloadStore(
        batches = batches.toMutableMap(),
        submissions = submissions.toMutableMap(),
        tasks = tasks.toMutableMap(),
        items = items.toMutableMap(),
        assets = assets.toMutableMap(),
        components = components.toMutableMap(),
        chunks = chunks.toMutableMap(),
        publishes = publishes.toMutableMap(),
        schedules = schedules.toMutableMap(),
    )

    fun taskSnapshot(taskId: String): TaskSnapshot? {
        val task = tasks[taskId] ?: return null
        val taskAssets = assets.values.filter { it.taskId == taskId }.map { asset ->
            val comps = components.values.filter { it.assetId == asset.id }.map { component ->
                ComponentSnapshot(
                    component = component,
                    chunks = chunks.values.filter { it.componentId == component.id },
                )
            }
            AssetSnapshot(
                asset = asset,
                components = comps,
                publish = publishes.values.firstOrNull { it.assetId == asset.id },
            )
        }
        return TaskSnapshot(
            task = task,
            assets = taskAssets,
            schedule = schedules.values.firstOrNull { it.taskId == taskId },
            batchId = items.values.firstOrNull { it.taskId == taskId }?.batchId,
        )
    }

    fun batchView(batchId: String): BatchView? {
        val batch = batches[batchId] ?: return null
        val batchItems = items.values.filter { it.batchId == batchId }.sortedBy { it.position }
        val snaps = batchItems.mapNotNull { item ->
            taskSnapshot(item.taskId)?.let { ItemSnapshot(item, it) }
        }
        return BatchView(batch, snaps, BatchStatsCalculator.compute(snaps))
    }

    fun downloads(filter: DownloadFilter): List<TaskSnapshot> =
        tasks.values
            .asSequence()
            .filter { filter.includeRemoved || !it.removed }
            .filter { filter.videoId == null || it.videoId == filter.videoId }
            .filter { filter.phases == null || it.phase in filter.phases }
            .filter { filter.statuses == null || it.status in filter.statuses }
            .mapNotNull { taskSnapshot(it.id) }
            .sortedByDescending { it.task.createdAt }
            .toList()
}

internal class MutableDownloadStore(
    val batches: MutableMap<String, DownloadBatch>,
    val submissions: MutableMap<String, EnqueueResult>,
    val tasks: MutableMap<String, DownloadTask>,
    val items: MutableMap<String, DownloadItem>,
    val assets: MutableMap<String, DownloadAsset>,
    val components: MutableMap<String, InputComponent>,
    val chunks: MutableMap<String, DownloadChunk>,
    val publishes: MutableMap<String, PublishRecord>,
    val schedules: MutableMap<String, ScheduleRecord>,
) {
    fun freeze() = DownloadStore(
        batches = batches.toMap(),
        submissions = submissions.toMap(),
        tasks = tasks.toMap(),
        items = items.toMap(),
        assets = assets.toMap(),
        components = components.toMap(),
        chunks = chunks.toMap(),
        publishes = publishes.toMap(),
        schedules = schedules.toMap(),
    )

    fun asStore() = DownloadStore(
        batches = batches,
        submissions = submissions,
        tasks = tasks,
        items = items,
        assets = assets,
        components = components,
        chunks = chunks,
        publishes = publishes,
        schedules = schedules,
    )
}

internal fun storeFromEntities(
    batches: List<BatchEntity>,
    submissions: List<SubmissionEntity>,
    tasks: List<TaskEntity>,
    items: List<ItemEntity>,
    assets: List<AssetEntity>,
    components: List<ComponentEntity>,
    chunks: List<ChunkEntity>,
    publishes: List<PublishEntity>,
    schedules: List<ScheduleEntity>,
): DownloadStore = DownloadStore(
    batches = batches.associate { it.id to it.toDomain() },
    submissions = submissions.associate { it.submissionId to DownloadJson.enqueue(it.resultJson) },
    tasks = tasks.associate { it.id to it.toDomain() },
    items = items.associate { it.id to it.toDomain() },
    assets = assets.associate { it.id to it.toDomain() },
    components = components.associate { it.id to it.toDomain() },
    chunks = chunks.associate { it.id to it.toDomain() },
    publishes = publishes.associate { it.id to it.toDomain() },
    schedules = schedules.associate { it.id to it.toDomain() },
)
