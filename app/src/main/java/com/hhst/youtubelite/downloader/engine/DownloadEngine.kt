package com.hhst.youtubelite.downloader.engine

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
        val started = System.nanoTime()
        AppLog.event(AppLog.Category.DOWNLOADER, "execution_start", mapOf("task" to taskId, "stage" to stage))
        try {
            execute(taskId, stage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.event(AppLog.Category.DOWNLOADER, "execution_failed", mapOf("task" to taskId, "stage" to stage), t)
            val snap = repository.transact { snapshot(taskId) } ?: return
            coordinator.reportExecution(
                taskId,
                snap.task.executionGeneration,
                DownloadStatus.FAILED,
                errorMessage = t.message,
            )
        } finally {
            AppLog.event(AppLog.Category.DOWNLOADER, "execution_finished", mapOf("task" to taskId, "stage" to stage, "duration_ms" to (System.nanoTime() - started) / 1_000_000))
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
            val name = asset.asset.outputName ?: DownloadFileNames.sanitize(
                current.task.title,
                DownloadFileNames.extensionOf(publishSource.name, if (kind == AssetKind.AUDIO) "m4a" else "mp4"),
            )
            val published = publisher.publish(
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
            )
            when (published) {
                is PublishResult.Published -> {
                    // A stale generation must keep its inputs: the row that
                    // would own the published file no longer accepts it.
                    val accepted = coordinator.reportAssetPublished(taskId, gen, kind, published.uri)
                    if (accepted) {
                        files.forEach { it.delete() }
                        if (publishSource !in files) publishSource.delete()
                    }
                }
                PublishResult.Interrupted -> return
                is PublishResult.Failed -> coordinator.reportAssetFailed(taskId, gen, kind, published.reason)
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
        return when (val muxed = finalizer.muxAndVerify(files, output, audioOnly)) {
            is MuxResult.Ok -> output
            MuxResult.Interrupted -> null
            is MuxResult.Gated -> {
                coordinator.reportAssetFailed(taskId, generation, kind, muxed.reason)
                null
            }
            is MuxResult.Failed -> {
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
