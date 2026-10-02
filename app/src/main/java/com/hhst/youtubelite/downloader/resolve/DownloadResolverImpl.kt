package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadResolveOutcome
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.ResolvedComponentUpdate
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.net.RecoveredSource
import com.hhst.youtubelite.downloader.net.YoutubeDownloadRequestAdapter
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fills download metadata and selection during RESOLVE. Does not start the
 * transfer itself. Request plans are produced so transport can run later on an
 * independent HTTP client / working directory.
 */
class DownloadResolverImpl(
    private val coordinator: DownloadCoordinator,
    private val repository: DownloadRepository,
    private val catalogs: DownloadCatalogSource,
    private val poTokens: DownloadPoTokenLifecycle = DownloadPoTokenLifecycle(catalogs),
) : DownloadResolver {

    val lastPlan: DownloadPlan? get() = lastReadyPlan
    private var lastReadyPlan: DownloadPlan? = null
    private var lastIdentities: List<String> = emptyList()
    private val sources = ConcurrentHashMap<String, List<DownloadComponentSource>>()

    override fun sourcesOf(taskId: String): List<DownloadComponentSource> = sources[taskId].orEmpty()

    override suspend fun resolve(taskId: String): DownloadResolveOutcome {
        val task = repository.transact { getTask(taskId) } ?: return DownloadResolveOutcome.Stale
        val generation = task.executionGeneration
        if (!coordinator.reportExecution(taskId, generation, DownloadStatus.RUNNING, DownloadPhase.RESOLVE)) {
            return if (task.userCancelled) DownloadResolveOutcome.Cancelled else DownloadResolveOutcome.Stale
        }
        return try {
            val catalog = catalogs.catalog(task.videoId)
            when (val selected = DownloadSelector.select(catalog, task.config)) {
                is DownloadSelection.Failed -> fail(task, generation, selected)
                is DownloadSelection.Ready -> ready(task, generation, catalog, selected.plan)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            fail(
                task,
                generation,
                DownloadSelection.Failed(
                    DownloadUnavailableReason.NO_FILE_STREAMS,
                    t.message ?: "resolve failed",
                ),
            )
        }
    }

    /**
     * 403 refresh that verifies media identity and marks components
     * that cannot be proven as needing a full re-download.
     */
    suspend fun refreshAfter403(taskId: String): IdentityRefreshResult? {
        val snapshot = repository.transact { snapshot(taskId) } ?: return null
        val task = snapshot.task
        val previous = snapshot.assets.flatMap { asset ->
            asset.components.mapNotNull { it.component.resourceIdentity }
        }
        val result = poTokens.refreshAfter403(task.videoId, previous)
        result.decisions.filter { it.needsRedownload }.forEach { decision ->
            coordinator.reportComponentNeedsRedownload(
                taskId,
                task.executionGeneration,
                decision.previousIdentity,
            )
        }
        return result
    }

    override suspend fun recoverForbidden(taskId: String): Boolean {
        val result = refreshAfter403(taskId) ?: return false
        if (result.exhausted) return false
        return resolve(taskId) is DownloadResolveOutcome.Ready
    }

    suspend fun recoverSource(taskId: String, identity: String?): RecoveredSource? {
        val ok = recoverForbidden(taskId)
        // Strict identity match only: falling back to the first source could
        // hand an audio refresh a video URL and splice foreign bytes.
        val next = sourcesOf(taskId).firstOrNull { identity == null || it.resourceIdentity == identity }
            ?: return RecoveredSource(
                source = DownloadComponentSource(
                    assetKind = AssetKind.VIDEO,
                    componentKind = InputComponentKind.VIDEO,
                    url = "",
                ),
                needsRedownload = true,
                exhausted = true,
            )
        val needs = repository.transact { snapshot(taskId) }
            ?.assets
            ?.flatMap { it.components }
            ?.any { it.component.resourceIdentity == identity && it.component.needsRedownload }
            ?: !ok
        return RecoveredSource(source = next, needsRedownload = needs, exhausted = !ok)
    }

    private suspend fun ready(
        task: DownloadTask,
        generation: Long,
        catalog: DownloadCatalog,
        plan: DownloadPlan,
    ): DownloadResolveOutcome {
        lastReadyPlan = plan
        sources[task.id] = buildSources(task, plan)
        val updates = buildUpdates(task, catalog, plan)
        lastIdentities = updates.mapNotNull { it.resourceIdentity }
        if (!coordinator.reportResolved(task.id, generation, updates)) {
            return if (task.userCancelled) DownloadResolveOutcome.Cancelled else DownloadResolveOutcome.Stale
        }
        // An unavailable attachment fails its own asset only; media proceeds.
        plan.subtitleFailure?.let {
            coordinator.reportAssetFailed(task.id, generation, AssetKind.SUBTITLE, it.name)
        }
        plan.coverFailure?.let {
            coordinator.reportAssetFailed(task.id, generation, AssetKind.COVER, it.name)
        }
        coordinator.reportExecution(task.id, generation, DownloadStatus.QUEUED, DownloadPhase.TRANSFER)
        return DownloadResolveOutcome.Ready(task.id, generation)
    }

    private suspend fun fail(
        task: DownloadTask,
        generation: Long,
        failed: DownloadSelection.Failed,
    ): DownloadResolveOutcome {
        val kinds = repository.transact { assetsForTask(task.id) }.map { it.kind }.distinct()
        kinds.forEach { kind ->
            coordinator.reportAssetFailed(task.id, generation, kind, failed.reason.name)
        }
        coordinator.reportExecution(
            task.id,
            generation,
            DownloadStatus.FAILED,
            DownloadPhase.RESOLVE,
            errorMessage = failed.reason.name,
        )
        return DownloadResolveOutcome.Failed(failed.reason.name, failed.message)
    }

    private fun buildUpdates(
        task: DownloadTask,
        catalog: DownloadCatalog,
        plan: DownloadPlan,
    ): List<ResolvedComponentUpdate> {
        val updates = mutableListOf<ResolvedComponentUpdate>()
        val videoChoice = plan.video ?: plan.muxed
        if (videoChoice != null && !task.config.audioOnly) {
            val format = videoChoice.format
            YoutubeDownloadRequestAdapter.adapt(task.videoId, format)
            val base = outputBase(task)
            updates += ResolvedComponentUpdate(
                assetKind = AssetKind.VIDEO,
                componentKind = InputComponentKind.VIDEO,
                mimeType = format.mimeType.ifBlank { "video/mp4" },
                expectedBytes = videoChoice.expectedBytes
                    ?: DownloadBitrate.expectedBytes(format, catalog.durationSec),
                container = format.container,
                codec = format.codec,
                resourceIdentity = videoChoice.resourceIdentity,
                outputName = "$base.mp4",
                assetMimeType = "video/mp4",
            )
        }
        val audioChoice = plan.audio
        if (audioChoice != null) {
            val format = audioChoice.format
            YoutubeDownloadRequestAdapter.adapt(task.videoId, format)
            val audioAsset = if (task.config.audioOnly) AssetKind.AUDIO else AssetKind.VIDEO
            val componentKind =
                if (task.config.audioOnly) InputComponentKind.AUDIO else InputComponentKind.AUDIO
            val base = outputBase(task)
            updates += ResolvedComponentUpdate(
                assetKind = audioAsset,
                componentKind = componentKind,
                mimeType = format.mimeType.ifBlank { "audio/mp4" },
                expectedBytes = audioChoice.expectedBytes
                    ?: DownloadBitrate.expectedBytes(format, catalog.durationSec),
                container = format.container,
                codec = format.codec,
                audioTrackKey = audioChoice.audioTrackKey,
                resourceIdentity = audioChoice.resourceIdentity,
                outputName = if (task.config.audioOnly) "$base.m4a" else null,
                assetMimeType = if (task.config.audioOnly) "audio/mp4" else null,
            )
        }
        plan.subtitle?.let { sub ->
            val base = outputBase(task)
            val lang = sub.language ?: "und"
            updates += ResolvedComponentUpdate(
                assetKind = AssetKind.SUBTITLE,
                componentKind = InputComponentKind.VIDEO,
                mimeType = sub.mimeType,
                expectedBytes = null,
                outputName = "$base.$lang.${sub.extension}",
                assetMimeType = sub.mimeType,
            )
        }
        plan.cover?.let { cover ->
            val base = outputBase(task)
            updates += ResolvedComponentUpdate(
                assetKind = AssetKind.COVER,
                componentKind = InputComponentKind.VIDEO,
                mimeType = cover.mimeType,
                expectedBytes = null,
                outputName = "$base.${cover.extension}",
                assetMimeType = cover.mimeType,
            )
        }
        return updates
    }

    private fun outputBase(task: DownloadTask): String =
        task.config.fileNameTemplate?.takeIf { it.isNotBlank() }
            ?: task.title.ifBlank { task.videoId }

    private fun buildSources(
        task: DownloadTask,
        plan: DownloadPlan,
    ): List<DownloadComponentSource> = buildList {
        val videoChoice = plan.video ?: plan.muxed
        if (videoChoice != null && !task.config.audioOnly) {
            add(mediaSource(task.videoId, AssetKind.VIDEO, InputComponentKind.VIDEO, videoChoice))
        }
        plan.audio?.let { audio ->
            val asset = if (task.config.audioOnly) AssetKind.AUDIO else AssetKind.VIDEO
            add(mediaSource(task.videoId, asset, InputComponentKind.AUDIO, audio))
        }
        plan.subtitle?.let { sub ->
            add(sidecarSource(task.videoId, AssetKind.SUBTITLE, sub.url, sub.mimeType))
        }
        plan.cover?.let { cover ->
            add(sidecarSource(task.videoId, AssetKind.COVER, cover.url, cover.mimeType))
        }
    }

    private fun mediaSource(
        videoId: String,
        assetKind: AssetKind,
        componentKind: InputComponentKind,
        choice: DownloadMediaChoice,
    ): DownloadComponentSource {
        val plan = YoutubeDownloadRequestAdapter.adapt(videoId, choice.format)
        return DownloadComponentSource(
            assetKind = assetKind,
            componentKind = componentKind,
            url = choice.format.url,
            resourceIdentity = choice.resourceIdentity,
            expectedBytes = choice.expectedBytes,
            mimeType = choice.format.mimeType,
            rangeModeName = plan.rangeMode.name,
            methodName = plan.method.name,
            cookiePolicyName = plan.cookiePolicy.name,
            client = plan.client,
            headers = plan.headers,
            postPulse = plan.postPulse,
        )
    }

    private fun sidecarSource(
        videoId: String,
        kind: AssetKind,
        url: String,
        mime: String,
    ): DownloadComponentSource {
        val plan = YoutubeDownloadRequestAdapter.adaptUrl(videoId, url)
        return DownloadComponentSource(
            assetKind = kind,
            componentKind = InputComponentKind.VIDEO,
            url = url,
            mimeType = mime,
            sidecar = true,
            rangeModeName = plan.rangeMode.name,
            methodName = plan.method.name,
            cookiePolicyName = plan.cookiePolicy.name,
            client = plan.client,
            headers = plan.headers,
            postPulse = false,
        )
    }
}
