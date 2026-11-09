package com.hhst.youtubelite.downloader.resolve

import kotlinx.serialization.encodeToString
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.BackgroundDecision
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadResolveOutcome
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadStateMachine
import com.hhst.youtubelite.downloader.core.DownloadTask
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.ResolvedComponentUpdate
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.net.DownloadRangeMode
import com.hhst.youtubelite.downloader.net.DownloadResourceIdentity
import com.hhst.youtubelite.downloader.net.RecoveredSource
import com.hhst.youtubelite.extractor.MediaRequestPlan
import com.hhst.youtubelite.downloader.net.YoutubeDownloadRequestAdapter
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fills download metadata and selection during RESOLVE; does not start the transfer.
 * Request plans are produced so transport can run later on an independent HTTP client.
 */
class DownloadResolverImpl(
    private val coordinator: DownloadCoordinator,
    private val repository: DownloadRepository,
    private val catalogs: DownloadCatalogSource,
    private val poTokens: DownloadPoTokenLifecycle = DownloadPoTokenLifecycle(catalogs),
) : DownloadResolver {

    val lastPlan: DownloadPlan? get() = lastReadyPlan
    private var lastReadyPlan: DownloadPlan? = null
    private data class ResolvedSources(val generation: Long, val values: List<DownloadComponentSource>)
    private val sources = ConcurrentHashMap<String, ResolvedSources>()
    private data class FallbackCatalog(
        val generation: Long,
        val catalog: DownloadCatalog,
        val scope: String,
        val expires: Long,
        val sizeBytes: Int,
    )
    private val fallbackCatalogs = LinkedHashMap<String, FallbackCatalog>(16, .75f, true)

    override fun sourcesOf(taskId: String, generation: Long): List<DownloadComponentSource> =
        sources[taskId]?.takeIf { it.generation == generation }?.values.orEmpty()

    override suspend fun resolve(taskId: String, generation: Long): DownloadResolveOutcome {
        val task = currentTask(taskId, generation) ?: return DownloadResolveOutcome.Stale
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
                    DownloadUnavailableReason.EXTRACTION_FAILED,
                    t.message ?: "resolve failed",
                ),
            )
        }
    }

    suspend fun refreshAfter403(taskId: String, generation: Long): IdentityRefreshResult? {
        val snapshot = repository.transact { snapshot(taskId)?.takeIf { it.task.accepts(generation) } } ?: return null
        val task = snapshot.task
        val previous = snapshot.assets.flatMap { asset ->
            asset.components.mapNotNull { it.component.resourceIdentity }
        }
        val result = poTokens.refreshAfter403(task.videoId, previous)
        if (currentTask(taskId, generation) == null) return null
        result.decisions.filter { it.needsRedownload }.forEach { decision ->
            coordinator.reportComponentNeedsRedownload(
                taskId,
                generation,
                decision.previousIdentity,
            )
        }
        return result.takeIf { currentTask(taskId, generation) != null }
    }

    override suspend fun recoverForbidden(taskId: String, generation: Long): Boolean {
        val result = refreshAfter403(taskId, generation) ?: return false
        if (result.exhausted) return false
        val task = currentTask(taskId, generation) ?: return false
        val catalog = result.catalog ?: return false
        return when (val selected = DownloadSelector.select(catalog, task.config)) {
            is DownloadSelection.Ready -> ready(task, generation, catalog, selected.plan) is DownloadResolveOutcome.Ready
            is DownloadSelection.Failed -> false
        }
    }

    suspend fun recoverSource(taskId: String, generation: Long, identity: String?): RecoveredSource? {
        if (currentTask(taskId, generation) == null) return null
        val previous = sourcesOf(taskId, generation).firstOrNull { it.resourceIdentity == identity }
        val ok = recoverForbidden(taskId, generation)
        val snapshot = repository.transact { snapshot(taskId)?.takeIf { it.task.accepts(generation) } } ?: return null
        // Strict identity match only: a first-source fallback could hand an audio
        // refresh a video URL and splice foreign bytes.
        val next = sourcesOf(taskId, generation).firstOrNull { identity == null || it.resourceIdentity == identity }
            ?: previous?.let { old -> sourcesOf(taskId, generation).firstOrNull { it.assetKind == old.assetKind && it.componentKind == old.componentKind } }
            ?: return RecoveredSource(
                source = DownloadComponentSource(
                    assetKind = AssetKind.VIDEO,
                    componentKind = InputComponentKind.VIDEO,
                    url = "",
                ),
                needsRedownload = true,
                exhausted = true,
            )
        val needs = snapshot.assets.flatMap { it.components }
            .any { it.component.resourceIdentity == identity && it.component.needsRedownload }
        return RecoveredSource(source = next, needsRedownload = needs, exhausted = !ok)
    }

    /** Uses only the existing catalog; there is no session recapture or token mint in this step. */
    suspend fun backupSource(taskId: String, generation: Long, identity: String?): RecoveredSource? {
        val task = currentTask(taskId, generation) ?: return null
        val previous = sourcesOf(taskId, generation).firstOrNull { it.resourceIdentity == identity } ?: return null
        val profile = previous.requestPlan?.client ?: return null
        val cached = synchronized(fallbackCatalogs) { fallbackCatalogs[taskId] }
            ?.takeIf { it.generation == generation && it.scope == catalogs.scope() && it.expires > System.currentTimeMillis() } ?: return null
        val catalog = cached.catalog.copy(formats = cached.catalog.formats.filter { it.requestPlan?.client != profile })
        val selection = DownloadSelector.select(catalog, task.config) as? DownloadSelection.Ready ?: return null
        if (ready(task, generation, catalog, selection.plan) !is DownloadResolveOutcome.Ready) return null
        if (currentTask(taskId, generation) == null) return null
        val next = sourcesOf(taskId, generation).firstOrNull { it.assetKind == previous.assetKind && it.componentKind == previous.componentKind } ?: return null
        return RecoveredSource(next, identity == null || !DownloadResourceIdentity.proven(identity, next.resourceIdentity.orEmpty()), false)
    }

    private suspend fun ready(
        task: DownloadTask,
        generation: Long,
        catalog: DownloadCatalog,
        plan: DownloadPlan,
    ): DownloadResolveOutcome {
        if (currentTask(task.id, generation) == null) return DownloadResolveOutcome.Stale
        val resolvedSources = buildSources(task, plan)
        val now = System.currentTimeMillis()
        val expiry = catalog.formats.mapNotNull { it.expiresAtMillis }.minOrNull() ?: now + 120_000
        val fallback = FallbackCatalog(generation, catalog, catalogs.scope(), minOf(now + 120_000, expiry - 30_000),
            kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(DownloadCatalog::class.java), catalog).toByteArray().size)
        val updates = buildUpdates(task, plan)
        val previous = repository.transact { snapshot(task.id)?.takeIf { it.task.accepts(generation) } }
            ?: return DownloadResolveOutcome.Stale
        previous.assets.forEach { asset -> asset.components.forEach { saved ->
            val update = updates.firstOrNull { it.assetKind == asset.asset.kind && it.componentKind == saved.component.kind }
            val old = saved.component.resourceIdentity
            val next = update?.resourceIdentity
            if (old != null && next != null && saved.chunks.any { it.verified && it.receivedBytes > 0 } &&
                !DownloadResourceIdentity.proven(old, next)) {
                coordinator.reportComponentNeedsRedownload(task.id, generation, old)
            }
        } }
        if (!coordinator.reportResolved(task.id, generation, updates)) {
            return if (task.userCancelled) DownloadResolveOutcome.Cancelled else DownloadResolveOutcome.Stale
        }
        // Serialize this check and cache publication with pause/resume transactions;
        // an old suspended result must not replace a newer plan.
        val committed = repository.transact {
            if (getTask(task.id)?.accepts(generation) != true) return@transact false
            sources[task.id] = ResolvedSources(generation, resolvedSources)
            lastReadyPlan = plan
            synchronized(fallbackCatalogs) {
                fallbackCatalogs[task.id] = fallback
                val publishedAt = System.currentTimeMillis()
                fallbackCatalogs.entries.removeAll { it.value.expires <= publishedAt }
                var size = fallbackCatalogs.values.sumOf { it.sizeBytes.toLong() }
                while ((size > 8 * 1024 * 1024 || fallbackCatalogs.size > 128) && fallbackCatalogs.isNotEmpty()) {
                    size -= fallbackCatalogs.remove(fallbackCatalogs.keys.first())!!.sizeBytes
                }
            }
            true
        }
        if (!committed) return DownloadResolveOutcome.Stale
        // An unavailable attachment fails its own asset only; media proceeds.
        plan.subtitleFailure?.let {
            coordinator.reportAssetFailed(task.id, generation, AssetKind.SUBTITLE, it.name)
        }
        plan.coverFailure?.let {
            coordinator.reportAssetFailed(task.id, generation, AssetKind.COVER, it.name)
        }
        if (!coordinator.reportExecution(task.id, generation, DownloadStatus.QUEUED, DownloadPhase.TRANSFER)) {
            return DownloadResolveOutcome.Stale
        }
        return DownloadResolveOutcome.Ready(task.id, generation)
    }

    private suspend fun fail(
        task: DownloadTask,
        generation: Long,
        failed: DownloadSelection.Failed,
    ): DownloadResolveOutcome {
        if (currentTask(task.id, generation) == null) return DownloadResolveOutcome.Stale
        val kinds = repository.transact { assetsForTask(task.id) }.map { it.kind }.distinct()
        kinds.forEach { kind ->
            coordinator.reportAssetFailed(task.id, generation, kind, failed.reason.name)
        }
        coordinator.reportExecution(
            task.id,
            generation,
            DownloadStatus.FAILED,
            DownloadPhase.RESOLVE,
            errorMessage = if (failed.reason == DownloadUnavailableReason.EXTRACTION_FAILED) {
                failed.message
            } else failed.reason.name,
        )
        return DownloadResolveOutcome.Failed(failed.reason.name)
    }

    private suspend fun currentTask(taskId: String, generation: Long): DownloadTask? =
        repository.transact { getTask(taskId)?.takeIf { it.accepts(generation) } }

    private fun DownloadTask.accepts(generation: Long): Boolean = !removed &&
        DownloadStateMachine.acceptBackground(this, generation, DownloadStatus.RUNNING) == BackgroundDecision.APPLY

    private fun buildUpdates(
        task: DownloadTask,
        plan: DownloadPlan,
    ): List<ResolvedComponentUpdate> {
        val updates = mutableListOf<ResolvedComponentUpdate>()
        val videoChoice = plan.video ?: plan.muxed
        if (videoChoice != null && !task.config.audioOnly) {
            val format = videoChoice.format
            val base = outputBase(task)
            updates += ResolvedComponentUpdate(
                assetKind = AssetKind.VIDEO,
                componentKind = InputComponentKind.VIDEO,
                mimeType = format.mimeType.ifBlank { "video/mp4" },
                expectedBytes = videoChoice.expectedBytes,
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
            val audioAsset = if (task.config.audioOnly) AssetKind.AUDIO else AssetKind.VIDEO
            val base = outputBase(task)
            updates += ResolvedComponentUpdate(
                assetKind = audioAsset,
                componentKind = InputComponentKind.AUDIO,
                mimeType = format.mimeType.ifBlank { "audio/mp4" },
                expectedBytes = audioChoice.expectedBytes,
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
            add(sidecarSource(task.videoId, AssetKind.SUBTITLE, sub.url, sub.mimeType, sub.requestPlan))
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
            requestPlan = choice.format.requestPlan,
        )
    }

    private fun sidecarSource(
        videoId: String,
        kind: AssetKind,
        url: String,
        mime: String,
        requestPlan: MediaRequestPlan? = null,
    ): DownloadComponentSource {
        val plan = YoutubeDownloadRequestAdapter.adaptUrl(videoId, url)
        return DownloadComponentSource(
            assetKind = kind,
            componentKind = InputComponentKind.VIDEO,
            url = url,
            mimeType = mime,
            sidecar = true,
            // Timed-text and covers are whole responses, not media byte ranges.
            rangeModeName = DownloadRangeMode.NONE.name,
            methodName = plan.method.name,
            cookiePolicyName = plan.cookiePolicy.name,
            client = plan.client,
            headers = plan.headers,
            requestPlan = requestPlan,
        )
    }
}
