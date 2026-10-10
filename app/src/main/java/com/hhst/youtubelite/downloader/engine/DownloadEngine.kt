package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.diagnostics.DiagnosticCoroutineContext
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.AssetSnapshot
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadFinalizer
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import com.hhst.youtubelite.downloader.core.DownloadResolveOutcome
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTransport
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.core.PublishPhase
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.core.TaskSnapshot
import com.hhst.youtubelite.downloader.core.TransferResult
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.DownloadFileNames
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

enum class EngineStage {
    TRANSFER,
    FINALIZE,
    ALL,
}

/**
 * In-process file loop: resolve → chunked transfer → mux/verify → publish.
 * Workers/UIDT wrap this; they must not bypass generation checks.
 */
class DownloadEngine(
    private val coordinator: DownloadCoordinator,
    private val repository: DownloadRepository,
    private val resolver: DownloadResolver,
    private val transport: DownloadTransport,
    private val finalizer: DownloadFinalizer,
    private val publisher: DownloadPublisher,
    private val directories: DownloadDirectories,
) {
    suspend fun run(taskId: String) = run(taskId, EngineStage.ALL)

    suspend fun runTransfer(taskId: String) = run(taskId, EngineStage.TRANSFER)

    suspend fun runFinalize(taskId: String) = run(taskId, EngineStage.FINALIZE)

    suspend fun run(taskId: String, stage: EngineStage) {
        val initial = repository.transact { snapshot(taskId) }
        val context = DiagnosticContext(taskId = taskId, videoId = initial?.task?.videoId, generation = initial?.task?.executionGeneration)
        val operation = AppLog.operation(AppLog.Category.DOWNLOADER, "execution", context, mapOf("stage" to stage))
        var failure: Throwable? = null
        var cancelledByCoroutine = false
        try {
            withContext(DiagnosticCoroutineContext(context)) { execute(taskId, stage) }
        } catch (cancelled: CancellationException) {
            cancelledByCoroutine = true
            throw cancelled
        } catch (t: Throwable) {
            failure = t
            val snap = repository.transact { snapshot(taskId) } ?: return
            coordinator.reportExecution(
                taskId,
                snap.task.executionGeneration,
                DownloadStatus.FAILED,
                errorMessage = t.message,
            )
        } finally {
            withContext(NonCancellable) {
                val latest = runCatching { repository.transact { snapshot(taskId) } }.getOrNull()?.task
                val outcome = when {
                    latest?.userPaused == true -> DiagnosticOutcome.DEFERRED
                    latest == null || latest.userCancelled || latest.removed -> DiagnosticOutcome.CANCELLED
                    latest.executionGeneration != context.generation -> DiagnosticOutcome.SUPERSEDED
                    cancelledByCoroutine -> DiagnosticOutcome.CANCELLED
                    failure != null || latest.status == DownloadStatus.FAILED -> DiagnosticOutcome.FAILURE
                    latest.phase == DownloadPhase.COMPLETE -> DiagnosticOutcome.SUCCESS
                    else -> DiagnosticOutcome.DEFERRED
                }
                operation.finish(outcome, latest?.status?.name ?: "task_missing", failure,
                    mapOf("phase" to latest?.phase, "status" to latest?.status))
            }
        }
    }

    private suspend fun execute(taskId: String, stage: EngineStage) {
        val initial = repository.transact { snapshot(taskId) } ?: return
        if (!runnable(initial)) return
        if (stage != EngineStage.FINALIZE) {
            when (resolver.resolve(taskId)) {
                is DownloadResolveOutcome.Ready -> Unit
                is DownloadResolveOutcome.Failed,
                DownloadResolveOutcome.Stale,
                DownloadResolveOutcome.Cancelled,
                DownloadResolveOutcome.Ignored,
                -> return
            }
            val afterResolve = repository.transact { snapshot(taskId) } ?: return
            if (!runnable(afterResolve)) return
            val gen = afterResolve.task.executionGeneration
            if (!coordinator.reportExecution(taskId, gen, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)) return
            val sources = resolver.sourcesOf(taskId)
            val snap = repository.transact { snapshot(taskId) } ?: return
            for (assetSnap in snap.assets) {
                coroutineContext.ensureActive()
                val latest = repository.transact { snapshot(taskId) } ?: return
                if (!runnable(latest)) return
                val asset = latest.assets.firstOrNull { it.asset.id == assetSnap.asset.id } ?: continue
                if (asset.asset.published || asset.asset.failed) continue
                val transferred = transferAsset(taskId, gen, asset.asset.kind, asset, sources)
                when (transferred) {
                    TransferResult.Completed -> Unit
                    TransferResult.Paused, TransferResult.Cancelled -> return
                    TransferResult.WaitingNetwork -> {
                        coordinator.reportExecution(
                            taskId,
                            gen,
                            DownloadStatus.WAITING_NETWORK,
                            DownloadPhase.TRANSFER,
                        )
                        return
                    }
                    is TransferResult.Failed -> {
                        coordinator.reportAssetFailed(taskId, gen, asset.asset.kind, transferred.reason)
                    }
                }
            }
            if (stage == EngineStage.TRANSFER) {
                val after = repository.transact { snapshot(taskId) } ?: return
                if (!runnable(after)) return
                val needsLocal = after.assets.any { !it.asset.published && !it.asset.failed }
                if (needsLocal) {
                    coordinator.reportExecution(
                        taskId,
                        after.task.executionGeneration,
                        DownloadStatus.QUEUED,
                        DownloadPhase.WAITING_PROCESS,
                    )
                }
                return
            }
        }
        finalizeAssets(taskId)
    }

    private suspend fun finalizeAssets(taskId: String) {
        val snap = repository.transact { snapshot(taskId) } ?: return
        if (!runnable(snap)) return
        val gen = snap.task.executionGeneration
        val sources = resolver.sourcesOf(taskId)
        for (assetSnap in snap.assets) {
            coroutineContext.ensureActive()
            val latest = repository.transact { snapshot(taskId) } ?: return
            if (!runnable(latest)) return
            val asset = latest.assets.firstOrNull { it.asset.id == assetSnap.asset.id } ?: continue
            if (asset.asset.published || asset.asset.failed) continue
            val kind = asset.asset.kind
            val files = componentFiles(taskId, asset, sources)
            val publishSource = if (kind == AssetKind.SUBTITLE || kind == AssetKind.COVER) {
                files.firstOrNull()
            } else {
                muxAsset(taskId, gen, kind, files, audioOnly = kind == AssetKind.AUDIO)
            }
            val current = repository.transact { snapshot(taskId) } ?: return
            if (!runnable(current)) return
            val latestAsset = current.assets.firstOrNull { it.asset.kind == kind }
            if (publishSource == null) {
                if (latestAsset?.asset?.failed != true) return
                continue
            }
            if (!coordinator.reportExecution(taskId, gen, DownloadStatus.RUNNING, DownloadPhase.SAVE)) return
            coordinator.reportPublish(
                taskId,
                gen,
                kind,
                PublishPhase.IN_PROGRESS,
                tempFiles = listOf(publishSource.path),
            )
            val mime = asset.asset.mimeType ?: mimeFor(kind)
            val publishing = AppLog.operation(AppLog.Category.DOWNLOADER, "publish_file",
                (coroutineContext[DiagnosticCoroutineContext]?.diagnostic ?: DiagnosticContext(taskId = taskId, generation = gen)).child(),
                mapOf("asset" to kind, "mime" to mime, "source_bytes" to publishSource.length(), "available_storage_bytes" to publishSource.parentFile?.usableSpace))
            val name = asset.asset.outputName ?: DownloadFileNames.sanitize(
                current.task.title,
                DownloadFileNames.extensionOf(publishSource.name, if (kind == AssetKind.AUDIO) "m4a" else "mp4"),
            )
            val published = try { publisher.publish(
                PublishRequest(
                    publishId = asset.publish?.id ?: asset.asset.id,
                    assetId = asset.asset.id,
                    displayName = name,
                    mimeType = mime,
                    source = publishSource,
                    existingUri = asset.publish?.targetUri ?: asset.asset.publishedUri,
                    existingPhase = asset.publish?.phase ?: PublishPhase.PENDING,
                    onTargetCreated = { uri ->
                        coordinator.reportPublish(
                            taskId,
                            gen,
                            kind,
                            PublishPhase.IN_PROGRESS,
                            targetUri = uri,
                        )
                    },
                ),
            ) } catch (failure: Throwable) {
                publishing.finish(if (failure is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE,
                    "publish_exception", failure); throw failure
            }
            when (published) {
                is PublishResult.Published -> {
                    publishing.finish(DiagnosticOutcome.SUCCESS, "published")
                    // A stale generation must keep its inputs: the row that
                    // would own the published file no longer accepts it.
                    val accepted = coordinator.reportAssetPublished(taskId, gen, kind, published.uri)
                    if (accepted) {
                        files.forEach { it.delete() }
                        if (publishSource !in files) publishSource.delete()
                    }
                }
                PublishResult.Interrupted -> { publishing.finish(DiagnosticOutcome.CANCELLED, "interrupted"); return }
                is PublishResult.Failed -> {
                    publishing.finish(DiagnosticOutcome.FAILURE, published.reason)
                    coordinator.reportAssetFailed(taskId, gen, kind, published.reason)
                }
            }
        }
    }

    private suspend fun transferAsset(
        taskId: String,
        generation: Long,
        kind: AssetKind,
        asset: AssetSnapshot,
        sources: List<DownloadComponentSource>,
    ): TransferResult {
        val wanted = sources.filter { it.assetKind == kind }
        if (wanted.isEmpty()) return TransferResult.Failed("no-source")
        val toFetch = asset.components.filter { snap ->
            wanted.any { src -> src.componentKind == snap.component.kind }
        }.ifEmpty {
            if (wanted.any { it.sidecar }) asset.components else emptyList()
        }
        if (toFetch.isEmpty()) return TransferResult.Failed("no-source")
        for (componentSnap in toFetch) {
            val component = componentSnap.component
            val source = wanted.firstOrNull { it.componentKind == component.kind } ?: continue
            if (component.needsRedownload) {
                coordinator.reportComponentReset(taskId, generation, component.id)
            }
            val dest = if (source.sidecar) {
                val ext = DownloadFileNames.extensionOf(asset.asset.outputName ?: source.url, "bin")
                directories.sidecarFile(taskId, asset.asset.id, ext)
            } else {
                directories.componentFile(taskId, component.id)
            }
            val result = transport.downloadComponent(
                taskId = taskId,
                component = component,
                source = source,
                dest = dest,
                verified = componentSnap.chunks,
                onProgress = { chunk, total -> coordinator.reportChunk(taskId, generation, chunk, total) },
                onChunk = { chunk -> coordinator.reportChunk(taskId, generation, chunk) },
            )
            if (result != TransferResult.Completed) return result
        }
        return TransferResult.Completed
    }

    private suspend fun muxAsset(
        taskId: String,
        generation: Long,
        kind: AssetKind,
        files: List<File>,
        audioOnly: Boolean,
    ): File? {
        if (!coordinator.reportExecution(taskId, generation, DownloadStatus.RUNNING, DownloadPhase.MERGE_VERIFY)) {
            return null
        }
        val output = directories.muxFile(taskId, kind.name, audioOnly)
        val merging = AppLog.operation(AppLog.Category.DOWNLOADER, "merge_verify",
            (coroutineContext[DiagnosticCoroutineContext]?.diagnostic ?: DiagnosticContext(taskId = taskId, generation = generation)).child(),
            mapOf("asset" to kind, "input_count" to files.size, "input_bytes" to files.sumOf { it.length() }, "available_storage_bytes" to output.parentFile?.usableSpace))
        val muxed = try { finalizer.muxAndVerify(files, output, audioOnly) } catch (failure: Throwable) {
            merging.finish(if (failure is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE,
                "merge_exception", failure); throw failure
        }
        return when (muxed) {
            is MuxResult.Ok -> { merging.finish(DiagnosticOutcome.SUCCESS, "verified", fields = mapOf("output_bytes" to output.length())); output }
            MuxResult.Interrupted -> { merging.finish(DiagnosticOutcome.CANCELLED, "interrupted"); null }
            is MuxResult.Gated -> {
                merging.finish(DiagnosticOutcome.DEFERRED, muxed.reason)
                coordinator.reportAssetFailed(taskId, generation, kind, muxed.reason)
                null
            }
            is MuxResult.Failed -> {
                merging.finish(DiagnosticOutcome.FAILURE, muxed.reason)
                coordinator.reportAssetFailed(taskId, generation, kind, muxed.reason)
                null
            }
        }
    }

    private fun componentFiles(
        taskId: String,
        asset: AssetSnapshot,
        sources: List<DownloadComponentSource>,
    ): List<File> {
        val kinds = sources.filter { it.assetKind == asset.asset.kind }.map { it.componentKind }.toSet()
        val fromChunks = asset.components
            .filter { kinds.isEmpty() || it.component.kind in kinds }
            .flatMap { snap -> snap.chunks.mapNotNull { it.tempPath?.let(::File) } }
            .distinct()
            .filter { it.isFile && it.length() > 0L }
        if (fromChunks.isNotEmpty()) return fromChunks
        return asset.components
            .filter { kinds.isEmpty() || it.component.kind in kinds }
            .map { directories.componentFile(taskId, it.component.id) }
            .filter { it.isFile && it.length() > 0L }
    }

    private fun runnable(snap: TaskSnapshot): Boolean {
        val task = snap.task
        return !task.removed && !task.userPaused && !task.userCancelled &&
            task.status != DownloadStatus.CANCELLED &&
            task.status != DownloadStatus.PAUSED &&
            task.status != DownloadStatus.FAILED && task.phase != DownloadPhase.COMPLETE
    }

    private fun mimeFor(kind: AssetKind): String = when (kind) {
        AssetKind.VIDEO -> "video/mp4"
        AssetKind.AUDIO -> "audio/mp4"
        AssetKind.SUBTITLE -> "text/vtt"
        AssetKind.COVER -> "image/jpeg"
    }
}
