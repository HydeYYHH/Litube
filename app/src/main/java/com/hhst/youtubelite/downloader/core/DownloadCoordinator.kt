package com.hhst.youtubelite.downloader.core

import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.data.DownloadSession
import com.hhst.youtubelite.extractor.VideoId
import kotlinx.coroutines.flow.Flow

class DownloadCoordinator(
    private val repository: DownloadRepository,
    private val transport: DownloadTransport = NoOpTransport,
    private val scheduler: DownloadScheduler = NoOpScheduler,
    private val publisher: DownloadPublisher = NoOpPublisher,
    private val ids: IdFactory = UuidIdFactory,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeDownloads(filter: DownloadFilter = DownloadFilter()): Flow<List<TaskSnapshot>> =
        repository.observeDownloads(filter)

    fun observeBatch(batchId: String): Flow<BatchView?> = repository.observeBatch(batchId)

    fun observeVideo(videoId: String): Flow<List<TaskSnapshot>> = repository.observeVideo(videoId)

    suspend fun enqueue(request: DownloadRequest, submissionId: String): EnqueueResult =
        enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.VIDEO,
                name = request.title.ifBlank { request.videoId },
                items = listOf(request),
                config = request.config,
            ),
            selection = BatchSelection(indexes = setOf(0)),
            submissionId = submissionId,
        )

    suspend fun enqueueBatch(
        snapshot: BatchSnapshot,
        selection: BatchSelection,
        submissionId: String,
        forceNew: Boolean = false,
    ): EnqueueResult {
        DownloadSnapshotGuard.validate(snapshot)?.let { reason ->
            throw IllegalArgumentException(reason.message)
        }
        if (selection.indexes.any { it !in snapshot.items.indices }) {
            throw IllegalArgumentException("selection index out of snapshot range")
        }
        val effects = SideEffects()
        val result = repository.transact {
            findSubmission(submissionId)?.let { return@transact it }
            addBatch(snapshot, selection, submissionId, forceNew, effects)
        }
        applyEffects(effects)
        return result
    }

    suspend fun reportPublish(
        taskId: String,
        generation: Long,
        kind: AssetKind,
        phase: PublishPhase,
        targetUri: String? = null,
        tempFiles: List<String>? = null,
        errorMessage: String? = null,
        keepOnCancel: Boolean? = null,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        val asset = assetsForTask(taskId).firstOrNull { it.kind == kind } ?: return@transact false
        val publish = publishForAsset(asset.id) ?: return@transact false
        updatePublish(
            publish.copy(
                phase = phase,
                targetUri = targetUri ?: publish.targetUri,
                tempFiles = tempFiles ?: publish.tempFiles,
                errorMessage = errorMessage ?: publish.errorMessage,
                keepOnCancel = keepOnCancel ?: publish.keepOnCancel,
            ),
        )
        true
    }

    suspend fun reportComponentReset(
        taskId: String,
        generation: Long,
        componentId: String,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        assetsForTask(taskId).forEach { asset ->
            componentsForAsset(asset.id).forEach { component ->
                if (component.id == componentId) {
                    deleteChunks(component.id)
                    updateComponent(component.copy(needsRedownload = false))
                }
            }
        }
        true
    }

    suspend fun pause(target: DownloadTarget) {
        val effects = SideEffects()
        repository.transact { pauseTarget(target, effects) }
        applyEffects(effects)
    }

    suspend fun resume(target: DownloadTarget) {
        val effects = SideEffects()
        repository.transact { resumeTarget(target, effects) }
        applyEffects(effects)
    }

    suspend fun cancel(target: DownloadTarget) {
        val effects = SideEffects()
        repository.transact { cancelTarget(target, effects) }
        applyEffects(effects)
    }

    suspend fun retryFailed(target: DownloadTarget) {
        val effects = SideEffects()
        repository.transact { retryTarget(target, effects) }
        applyEffects(effects)
    }

    suspend fun redownload(target: DownloadTarget): EnqueueResult {
        val requests = repository.transact { requestsFor(target) }
        if (requests.isEmpty()) {
            return EnqueueResult(0, 0, emptyList(), emptyList(), batchId = "", submissionId = "")
        }
        return enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.VIDEO,
                name = requests.first().title.ifBlank { "Redownload" },
                items = requests,
                config = requests.first().config,
            ),
            selection = BatchSelection(requests.indices.toSet()),
            submissionId = ids.next("sub"),
            forceNew = true,
        )
    }

    suspend fun remove(target: DownloadTarget, mode: RemoveMode) {
        val effects = SideEffects()
        repository.transact { removeTarget(target, mode, effects) }
        applyEffects(effects)
    }

    suspend fun reportExecution(
        taskId: String,
        generation: Long,
        status: DownloadStatus,
        phase: DownloadPhase? = null,
        errorMessage: String? = null,
    ): Boolean = repository.transact {
        val task = getTask(taskId) ?: return@transact false
        when (DownloadStateMachine.acceptBackground(task, generation, status)) {
            BackgroundDecision.APPLY -> {
                updateTask(
                    task.copy(
                        status = status,
                        phase = phase ?: task.phase,
                        errorMessage = errorMessage ?: task.errorMessage,
                        updatedAt = clock(),
                    ),
                )
                true
            }
            else -> false
        }
    }

    suspend fun reportAssetPublished(
        taskId: String,
        generation: Long,
        kind: AssetKind,
        uri: String,
        fileAvailability: FileAvailability = FileAvailability.EXISTS,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        val asset = assetsForTask(taskId).firstOrNull { it.kind == kind } ?: return@transact false
        updateAsset(
            asset.copy(
                published = true,
                failed = false,
                phase = DownloadPhase.COMPLETE,
                status = DownloadStatus.QUEUED,
                fileAvailability = fileAvailability,
                publishedUri = uri,
                errorMessage = null,
            ),
        )
        val publish = publishForAsset(asset.id)
        if (publish != null) {
            updatePublish(
                publish.copy(
                    targetUri = uri,
                    phase = PublishPhase.PUBLISHED,
                    keepOnCancel = true,
                    tempFiles = emptyList(),
                ),
            )
        }
        rollup(taskId)
        true
    }

    suspend fun reportAssetFailed(
        taskId: String,
        generation: Long,
        kind: AssetKind,
        message: String,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        val asset = assetsForTask(taskId).firstOrNull { it.kind == kind } ?: return@transact false
        updateAsset(
            asset.copy(
                published = false,
                failed = true,
                status = DownloadStatus.FAILED,
                errorMessage = message,
            ),
        )
        rollup(taskId)
        true
    }

    suspend fun reportChunk(
        taskId: String,
        generation: Long,
        chunk: DownloadChunk,
        expectedBytes: Long? = null,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        // A transfer restarting at byte zero replaces the old object's progress.
        if (!chunk.verified && chunk.startByte == 0L && chunk.receivedBytes == 0L) {
            deleteChunks(chunk.componentId)
        }
        if (expectedBytes != null && expectedBytes > 0L) {
            val component = assetsForTask(taskId).flatMap { componentsForAsset(it.id) }
                .firstOrNull { it.id == chunk.componentId } ?: return@transact false
            if (component.expectedBytes != expectedBytes) {
                updateComponent(component.copy(expectedBytes = expectedBytes))
            }
        }
        insertChunk(chunk)
        true
    }

    suspend fun reportFileAvailability(
        taskId: String,
        generation: Long,
        kind: AssetKind,
        availability: FileAvailability,
    ): Boolean = repository.transact {
        val task = getTask(taskId) ?: return@transact false
        if (generation != task.executionGeneration) return@transact false
        val asset = assetsForTask(taskId).firstOrNull { it.kind == kind } ?: return@transact false
        updateAsset(asset.copy(fileAvailability = availability))
        val assets = assetsForTask(taskId)
        updateTask(
            task.copy(
                fileAvailability = DownloadStateMachine.fileAvailability(assets),
                updatedAt = clock(),
            ),
        )
        true
    }

    /**
     * Writes resolved mime, expected bytes, and stream identity onto the
     * current generation's component/asset rows. Stale generations are ignored.
     */
    suspend fun reportResolved(
        taskId: String,
        generation: Long,
        updates: List<ResolvedComponentUpdate>,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        updates.forEach { update ->
            val asset = assetsForTask(taskId).firstOrNull { it.kind == update.assetKind } ?: return@forEach
            val components = componentsForAsset(asset.id)
            val component = components.firstOrNull { it.kind == update.componentKind }
                ?: components.singleOrNull()
                ?: return@forEach
            updateComponent(
                component.copy(
                    mimeType = update.mimeType ?: component.mimeType,
                    expectedBytes = update.expectedBytes,
                    container = update.container ?: component.container,
                    codec = update.codec ?: component.codec,
                    audioTrackKey = update.audioTrackKey ?: component.audioTrackKey,
                    resourceIdentity = update.resourceIdentity ?: component.resourceIdentity,
                ),
            )
            if (update.outputName != null || update.assetMimeType != null) {
                val latest = assetsForTask(taskId).first { it.id == asset.id }
                updateAsset(
                    latest.copy(
                        outputName = update.outputName ?: latest.outputName,
                        mimeType = update.assetMimeType ?: latest.mimeType,
                    ),
                )
            }
        }
        true
    }

    /**
     * URL refresh could not prove the same media object: discard unverified
     * bytes and fetch the component again.
     */
    suspend fun reportComponentNeedsRedownload(
        taskId: String,
        generation: Long,
        resourceIdentity: String? = null,
    ): Boolean = repository.transact {
        if (!acceptProgress(taskId, generation)) return@transact false
        var marked = false
        assetsForTask(taskId).forEach { asset ->
            componentsForAsset(asset.id).forEach { component ->
                val match = resourceIdentity.isNullOrBlank() ||
                    component.resourceIdentity == resourceIdentity
                if (match) {
                    updateComponent(component.copy(needsRedownload = true))
                    marked = true
                }
            }
        }
        marked
    }

    suspend fun onNetworkRestored() {
        val effects = SideEffects()
        repository.transact {
            allTasks().forEach { task ->
                if (task.userPaused || task.userCancelled || task.removed) return@forEach
                if (task.status == DownloadStatus.WAITING_NETWORK) {
                    updateTask(
                        task.copy(
                            status = DownloadStatus.QUEUED,
                            updatedAt = clock(),
                        ),
                    )
                    markSchedule(task.id, pending = true, reason = "NETWORK", effects)
                }
            }
        }
        applyEffects(effects)
    }

    /**
     * Interrupted RUNNING work becomes waiting-system. Does not schedule: a
     * still-alive system job is a restore path, and a missing job after
     * force-stop must wait for a later user action.
     */
    suspend fun onProcessRestore() {
        repository.transact {
            allTasks().forEach { task ->
                if (task.userPaused || task.userCancelled || task.removed) return@forEach
                val assets = assetsForTask(task.id)
                if (assets.isNotEmpty() && assets.all { it.published || it.failed }) {
                    // Repair terminal rows overwritten by a stale system job.
                    rollup(task.id)
                    return@forEach
                }
                if (task.status == DownloadStatus.RUNNING) {
                    updateTask(
                        task.copy(
                            status = DownloadStatus.WAITING_SYSTEM,
                            updatedAt = clock(),
                        ),
                    )
                    val existing = scheduleForTask(task.id)
                    if (existing != null) {
                        updateSchedule(existing.copy(reason = "SYSTEM"))
                    }
                }
            }
        }
    }

    /** Persist WorkManager unique name / UIDT job id after the system accepts work. */
    suspend fun bindSystemWork(
        taskId: String,
        uniqueWorkName: String? = null,
        systemJobId: Int? = null,
        clearJobId: Boolean = false,
        backend: String? = null,
        workKind: String? = null,
    ) {
        repository.transact {
            val existing = scheduleForTask(taskId) ?: return@transact
            updateSchedule(
                existing.copy(
                    uniqueWorkName = uniqueWorkName ?: existing.uniqueWorkName,
                    systemJobId = if (clearJobId) null else (systemJobId ?: existing.systemJobId),
                    backend = backend ?: existing.backend,
                    workKind = workKind ?: existing.workKind,
                ),
            )
        }
    }

    suspend fun batchIdForTask(taskId: String): String? = repository.transact {
        itemsForTask(taskId).firstOrNull()?.batchId
    }

    suspend fun ownedTaskIds(batchId: String): List<String> = repository.transact {
        itemsForBatch(batchId).filter { it.owned }.map { it.taskId }.distinct()
    }


    private suspend fun DownloadSession.addBatch(
        snapshot: BatchSnapshot,
        selection: BatchSelection,
        submissionId: String,
        forceNew: Boolean,
        effects: SideEffects,
    ): EnqueueResult {
        val ordered = selection.indexes.sorted()
        val batchId = ids.next("batch")
        insertBatch(
            DownloadBatch(
                id = batchId,
                source = snapshot.source,
                name = snapshot.name,
                config = snapshot.config,
                createdAt = clock(),
            ),
        )
        val existing = mutableListOf<TaskRef>()
        val created = mutableListOf<TaskRef>()
        var newCount = 0
        var skippedCount = 0
        ordered.forEachIndexed { position, index ->
            val request = snapshot.items[index]
            val config = selection.configOverrides[index] ?: request.config
            val outcome = addItem(
                batchId = batchId,
                position = position,
                request = request.copy(config = config),
                forceNew = forceNew,
                effects = effects,
            )
            if (outcome.owned) {
                created += outcome
                newCount++
            } else {
                existing += outcome
                skippedCount++
            }
        }
        val result = EnqueueResult(
            newCount = newCount,
            skippedCount = skippedCount,
            existing = existing,
            created = created,
            batchId = batchId,
            submissionId = submissionId,
        )
        saveSubmission(result)
        return result
    }

    private suspend fun DownloadSession.addItem(
        batchId: String,
        position: Int,
        request: DownloadRequest,
        forceNew: Boolean,
        effects: SideEffects,
    ): TaskRef {
        val videoId = VideoId.parse(request.videoId)
            ?: throw IllegalArgumentException("invalid video id: ${request.videoId}")
        val fingerprint = request.config.fingerprint(videoId)
        val live = if (forceNew) null else findLiveTaskByFingerprint(fingerprint)
        val now = clock()
        if (live != null) {
            insertItem(
                DownloadItem(
                    id = ids.next("item"),
                    batchId = batchId,
                    taskId = live.id,
                    videoId = videoId,
                    title = request.title.ifBlank { live.title },
                    author = request.author ?: live.author,
                    thumbnailUrl = request.thumbnailUrl ?: live.thumbnailUrl,
                    position = position,
                    owned = false,
                    skipped = true,
                ),
            )
            return TaskRef(live.id, videoId, owned = false)
        }
        val taskId = ids.next("task")
        insertTask(
            DownloadTask(
                id = taskId,
                videoId = videoId,
                title = request.title.ifBlank { videoId },
                author = request.author,
                thumbnailUrl = request.thumbnailUrl,
                config = request.config,
                configFingerprint = fingerprint,
                phase = DownloadPhase.RESOLVE,
                status = DownloadStatus.QUEUED,
                fileAvailability = FileAvailability.MISSING,
                completion = CompletionKind.NONE,
                executionGeneration = 0L,
                userPaused = false,
                userCancelled = false,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        createAssets(taskId, request.title.ifBlank { videoId }, request.config)
        insertItem(
            DownloadItem(
                id = ids.next("item"),
                batchId = batchId,
                taskId = taskId,
                videoId = videoId,
                title = request.title.ifBlank { videoId },
                author = request.author,
                thumbnailUrl = request.thumbnailUrl,
                position = position,
                owned = true,
                skipped = false,
            ),
        )
        markSchedule(taskId, pending = true, reason = "ENQUEUE", effects)
        return TaskRef(taskId, videoId, owned = true)
    }

    private suspend fun DownloadSession.createAssets(
        taskId: String,
        title: String,
        config: DownloadConfig,
    ) {
        val kinds = buildList {
            if (config.attachmentsOnly) {
                if (config.includeSubtitle) add(AssetKind.SUBTITLE)
                if (config.includeCover) add(AssetKind.COVER)
            } else {
                if (config.audioOnly) add(AssetKind.AUDIO) else add(AssetKind.VIDEO)
                if (config.includeSubtitle) add(AssetKind.SUBTITLE)
                if (config.includeCover) add(AssetKind.COVER)
            }
        }
        kinds.forEach { kind ->
            val assetId = ids.next("asset")
            insertAsset(
                DownloadAsset(
                    id = assetId,
                    taskId = taskId,
                    kind = kind,
                    phase = DownloadPhase.RESOLVE,
                    status = DownloadStatus.QUEUED,
                    fileAvailability = FileAvailability.MISSING,
                    published = false,
                    failed = false,
                    outputName = outputName(kind, title, config),
                    publishedUri = null,
                    errorMessage = null,
                ),
            )
            insertPublish(
                PublishRecord(
                    id = ids.next("pub"),
                    assetId = assetId,
                    phase = PublishPhase.PENDING,
                ),
            )
            val componentKinds = when (kind) {
                AssetKind.VIDEO -> listOf(InputComponentKind.VIDEO, InputComponentKind.AUDIO)
                AssetKind.AUDIO -> listOf(InputComponentKind.AUDIO)
                AssetKind.SUBTITLE, AssetKind.COVER -> listOf(InputComponentKind.VIDEO)
            }
            componentKinds.forEach { componentKind ->
                insertComponent(
                    InputComponent(
                        id = ids.next("comp"),
                        assetId = assetId,
                        kind = if (kind == AssetKind.SUBTITLE || kind == AssetKind.COVER) {
                            InputComponentKind.VIDEO
                        } else {
                            componentKind
                        },
                    ),
                )
            }
        }
    }

    private suspend fun DownloadSession.pauseTarget(target: DownloadTarget, effects: SideEffects) {
        taskIds(target, ownedOnly = true).forEach { pauseTask(it, effects) }
    }

    private suspend fun DownloadSession.pauseTask(taskId: String, effects: SideEffects) {
        val task = getTask(taskId) ?: return
        if (task.removed || task.userCancelled || task.status == DownloadStatus.CANCELLED) return
        if (task.phase == DownloadPhase.COMPLETE && task.completion == CompletionKind.FULL) return
        if (task.userPaused && task.status == DownloadStatus.PAUSED) return
        updateTask(
            task.copy(
                userPaused = true,
                status = DownloadStatus.PAUSED,
                executionGeneration = task.executionGeneration + 1,
                updatedAt = clock(),
            ),
        )
        if (task.phase == DownloadPhase.TRANSFER) {
            assetsForTask(taskId).forEach { asset ->
                componentsForAsset(asset.id).forEach { deleteUnverifiedChunks(it.id) }
            }
            effects.cancelInFlight += taskId
        }
        markSchedule(taskId, pending = false, reason = "PAUSE", effects)
        effects.unschedule += taskId
    }

    private suspend fun DownloadSession.resumeTarget(target: DownloadTarget, effects: SideEffects) {
        taskIds(target, ownedOnly = true).forEach { taskId ->
            val task = getTask(taskId) ?: return@forEach
            if (task.removed || task.userCancelled || task.status == DownloadStatus.CANCELLED) return@forEach
            // Explicit user resume also covers system waits (WAITING_SYSTEM)
            // and network waits — not just user pauses.
            val resumable = task.userPaused ||
                task.status == DownloadStatus.PAUSED ||
                task.status == DownloadStatus.PAUSING ||
                task.status == DownloadStatus.WAITING_SYSTEM ||
                task.status == DownloadStatus.WAITING_NETWORK
            if (!resumable) return@forEach
            updateTask(
                task.copy(
                    userPaused = false,
                    status = DownloadStatus.QUEUED,
                    executionGeneration = task.executionGeneration + 1,
                    updatedAt = clock(),
                ),
            )
            markSchedule(taskId, pending = true, reason = "RESUME", effects)
        }
    }

    private suspend fun DownloadSession.cancelTarget(target: DownloadTarget, effects: SideEffects) {
        taskIds(target, ownedOnly = true).forEach { cancelTask(it, effects) }
    }

    private suspend fun DownloadSession.cancelTask(taskId: String, effects: SideEffects) {
        val task = getTask(taskId) ?: return
        if (task.removed || task.userCancelled || task.status == DownloadStatus.CANCELLED) return
        if (task.phase == DownloadPhase.COMPLETE && task.completion == CompletionKind.FULL) return
        updateTask(
            task.copy(
                userCancelled = true,
                userPaused = false,
                status = DownloadStatus.CANCELLED,
                executionGeneration = task.executionGeneration + 1,
                updatedAt = clock(),
            ),
        )
        assetsForTask(taskId).forEach { asset ->
            if (asset.published) return@forEach
            componentsForAsset(asset.id).forEach { deleteChunks(it.id) }
            val publish = publishForAsset(asset.id) ?: return@forEach
            updatePublish(
                publish.copy(
                    phase = PublishPhase.DELETE_INTENT,
                    tempFiles = emptyList(),
                    keepOnCancel = false,
                ),
            )
            publish.tempFiles.forEach { effects.deletes += DeleteOp(taskId, asset.id, it) }
        }
        effects.cancelInFlight += taskId
        effects.unschedule += taskId
        markSchedule(taskId, pending = false, reason = "CANCEL", effects)
    }

    private suspend fun DownloadSession.retryTarget(target: DownloadTarget, effects: SideEffects) {
        taskIds(target, ownedOnly = true).forEach { taskId ->
            val task = getTask(taskId) ?: return@forEach
            if (task.removed || task.userCancelled) return@forEach
            val failed = assetsForTask(taskId).filter { it.failed }
            if (failed.isEmpty()) return@forEach
            failed.forEach { asset ->
                updateAsset(
                    asset.copy(
                        failed = false,
                        published = false,
                        status = DownloadStatus.QUEUED,
                        phase = DownloadPhase.RESOLVE,
                        errorMessage = null,
                    ),
                )
            }
            updateTask(
                task.copy(
                    status = DownloadStatus.QUEUED,
                    phase = DownloadPhase.RESOLVE,
                    userPaused = false,
                    errorMessage = null,
                    executionGeneration = task.executionGeneration + 1,
                    updatedAt = clock(),
                ),
            )
            rollup(taskId)
            val rolled = getTask(taskId) ?: return@forEach
            if (rolled.completion != CompletionKind.FULL) {
                updateTask(
                    rolled.copy(
                        status = DownloadStatus.QUEUED,
                        phase = DownloadPhase.RESOLVE,
                        updatedAt = clock(),
                    ),
                )
                markSchedule(taskId, pending = true, reason = "RETRY", effects)
            }
        }
    }

    private suspend fun DownloadSession.removeTarget(
        target: DownloadTarget,
        mode: RemoveMode,
        effects: SideEffects,
    ) {
        when (target) {
            is DownloadTarget.Batch -> {
                itemsForBatch(target.batchId).forEach { item ->
                    if (item.owned) {
                        removeTask(item.taskId, mode, effects)
                    } else {
                        deleteItem(item.id)
                    }
                }
            }
            is DownloadTarget.Item -> {
                val item = getItem(target.itemId) ?: return
                if (item.owned) {
                    removeTask(item.taskId, mode, effects)
                } else {
                    deleteItem(item.id)
                }
            }
            is DownloadTarget.Task -> removeTask(target.taskId, mode, effects)
        }
    }

    private suspend fun DownloadSession.removeTask(
        taskId: String,
        mode: RemoveMode,
        effects: SideEffects,
    ) {
        val task = getTask(taskId) ?: return
        updateTask(
            task.copy(
                removed = true,
                executionGeneration = task.executionGeneration + 1,
                updatedAt = clock(),
            ),
        )
        effects.unschedule += taskId
        effects.cancelInFlight += taskId
        markSchedule(taskId, pending = false, reason = "REMOVE", effects)
        if (mode == RemoveMode.RECORD_AND_FILES) {
            assetsForTask(taskId).forEach { asset ->
                val publish = publishForAsset(asset.id) ?: return@forEach
                updatePublish(publish.copy(phase = PublishPhase.DELETE_INTENT))
                asset.publishedUri?.let {
                    effects.deletes += DeleteOp(taskId, asset.id, it, recordRemoval = true)
                }
                publish.tempFiles.forEach { effects.deletes += DeleteOp(taskId, asset.id, it) }
            }
        }
    }

    private suspend fun DownloadSession.requestsFor(target: DownloadTarget): List<DownloadRequest> {
        val tasks = when (target) {
            is DownloadTarget.Task -> listOfNotNull(getTask(target.taskId))
            is DownloadTarget.Item -> {
                val item = getItem(target.itemId) ?: return emptyList()
                listOfNotNull(getTask(item.taskId))
            }
            is DownloadTarget.Batch -> itemsForBatch(target.batchId).mapNotNull { getTask(it.taskId) }
        }
        return tasks.filter { !it.removed }.map {
            DownloadRequest(
                videoId = it.videoId,
                title = it.title,
                author = it.author,
                thumbnailUrl = it.thumbnailUrl,
                config = it.config,
            )
        }
    }

    private suspend fun DownloadSession.taskIds(
        target: DownloadTarget,
        ownedOnly: Boolean,
    ): List<String> = when (target) {
        is DownloadTarget.Task -> listOf(target.taskId)
        is DownloadTarget.Item -> {
            val item = getItem(target.itemId) ?: return emptyList()
            if (ownedOnly && !item.owned) emptyList() else listOf(item.taskId)
        }
        is DownloadTarget.Batch -> itemsForBatch(target.batchId)
            .filter { !ownedOnly || it.owned }
            .map { it.taskId }
            .distinct()
    }

    private suspend fun DownloadSession.acceptProgress(taskId: String, generation: Long): Boolean {
        val task = getTask(taskId) ?: return false
        return DownloadStateMachine.acceptBackground(
            task,
            generation,
            DownloadStatus.RUNNING,
        ) == BackgroundDecision.APPLY
    }

    private suspend fun DownloadSession.rollup(taskId: String) {
        val task = getTask(taskId) ?: return
        val assets = assetsForTask(taskId)
        updateTask(
            task.copy(
                completion = DownloadStateMachine.completionOf(assets),
                phase = DownloadStateMachine.rollupPhase(assets, task.phase),
                status = DownloadStateMachine.rollupStatus(assets, task),
                fileAvailability = DownloadStateMachine.fileAvailability(assets),
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun DownloadSession.markSchedule(
        taskId: String,
        pending: Boolean,
        reason: String,
        effects: SideEffects,
    ) {
        val existing = scheduleForTask(taskId)
        if (existing != null) {
            updateSchedule(existing.copy(pending = pending, reason = reason))
        } else {
            insertSchedule(
                ScheduleRecord(
                    id = ids.next("sched"),
                    taskId = taskId,
                    uniqueWorkName = "download-$taskId",
                    pending = pending,
                    reason = reason,
                    createdAt = clock(),
                ),
            )
        }
        if (pending) effects.schedule += taskId else effects.unschedule += taskId
    }

    private suspend fun applyEffects(effects: SideEffects) {
        effects.cancelInFlight.distinct().forEach { transport.cancelInFlight(it) }
        effects.unschedule.distinct().forEach { scheduler.cancel(it) }
        effects.schedule.distinct().forEach { scheduler.schedule(it) }
        effects.deletes.distinctBy { it.assetId to it.uri }.forEach { op ->
            val result = publisher.delete(op.uri)
            repository.transact {
                val publish = publishForAsset(op.assetId) ?: return@transact
                if (result.ok) {
                    updatePublish(
                        publish.copy(
                            phase = PublishPhase.DELETED,
                            targetUri = null,
                            errorMessage = null,
                            tempFiles = emptyList(),
                        ),
                    )
                    val found = assetsForTask(op.taskId).firstOrNull { it.id == op.assetId }
                    if (found != null && (found.publishedUri == op.uri || found.publishedUri == null || op.uri == found.publishedUri)) {
                        if (found.publishedUri == op.uri) {
                            updateAsset(
                                found.copy(
                                    publishedUri = null,
                                    fileAvailability = FileAvailability.MISSING,
                                ),
                            )
                        }
                    }
                } else {
                    updatePublish(
                        publish.copy(
                            phase = PublishPhase.DELETE_INTENT,
                            errorMessage = result.reason,
                        ),
                    )
                    if (op.recordRemoval) {
                        // A failed file delete must stay visible and retryable,
                        // not vanish behind a record that was already dropped.
                        getTask(op.taskId)?.let { task ->
                            if (task.removed) {
                                updateTask(task.copy(removed = false, updatedAt = clock()))
                            }
                        }
                    }
                }
            }
        }
    }

    private class SideEffects {
        val cancelInFlight = mutableListOf<String>()
        val schedule = mutableListOf<String>()
        val unschedule = mutableListOf<String>()
        val deletes = mutableListOf<DeleteOp>()
    }

    private data class DeleteOp(
        val taskId: String,
        val assetId: String,
        val uri: String,
        val recordRemoval: Boolean = false,
    )

    private companion object {
        fun outputName(kind: AssetKind, title: String, config: DownloadConfig): String {
            val base = (config.fileNameTemplate ?: title).replace(Regex("""[\\/:*?"<>|]"""), "_")
            return when (kind) {
                AssetKind.VIDEO -> "$base.mp4"
                AssetKind.AUDIO -> "$base.m4a"
                AssetKind.SUBTITLE -> "$base.${config.subtitleLanguage ?: "und"}.srt"
                AssetKind.COVER -> "$base.jpg"
            }
        }
    }
}
