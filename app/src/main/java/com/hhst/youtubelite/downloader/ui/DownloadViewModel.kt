package com.hhst.youtubelite.downloader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.DefaultDownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadInteraction
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.core.DownloadSnapshotGuard
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.downloader.core.SnapshotReject
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadPlan
import com.hhst.youtubelite.downloader.resolve.DownloadSelection
import com.hhst.youtubelite.downloader.resolve.DownloadSelector
import com.hhst.youtubelite.downloader.resolve.DownloadUnavailableReason
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Thin facade over [DownloadCoordinator]. UI must not talk to Worker/Service/DB.
 * Catalog preview and prefs live here so composables never call those either.
 */
class DownloadViewModel(
    private val coordinator: DownloadCoordinator,
    private val catalogs: DownloadCatalogSource? = null,
    val prefs: DownloadPrefs = DefaultDownloadPrefs,
    private val interaction: DownloadInteraction = DownloadInteraction { },
    private val downloadHttp: OkHttpClient? = null,
    private val haptics: HapticsController? = null,
) : ViewModel() {

    fun observeDownloads(filter: DownloadFilter = DownloadFilter()): Flow<List<DownloadItemUiState>> =
        coordinator.observeDownloads(filter)
            .map { list -> list.map { DownloadUiMapper.item(it) } }
            .uiThrottle(critical = ::progressIsCritical)

    fun observeDownloads(filter: ManagerFilter): Flow<List<DownloadItemUiState>> =
        observeDownloads(DownloadPresentation.managerFilter(filter))

    fun observeBatch(batchId: String): Flow<BatchUiState?> =
        coordinator.observeBatch(batchId)
            .map { view -> view?.let(DownloadUiMapper::batch) }
            .uiThrottle { prev, next ->
                progressIsCritical(prev?.items, next?.items.orEmpty())
            }

    fun observeVideo(videoId: String): Flow<VideoDownloadUiState> =
        coordinator.observeVideo(videoId)
            .map { DownloadUiMapper.video(videoId, it) }
            .uiThrottle { prev, next ->
                progressIsCritical(prev?.tasks, next.tasks)
            }

    suspend fun enqueue(request: DownloadRequest, submissionId: String): EnqueueResult =
        coordinator.enqueue(request, submissionId)

    suspend fun enqueueBatch(
        snapshot: BatchSnapshot,
        selection: BatchSelection,
        submissionId: String,
    ): EnqueueResult {
        DownloadSnapshotGuard.validate(snapshot)?.let { throw IllegalArgumentException(it.message) }
        return coordinator.enqueueBatch(snapshot, selection, submissionId)
    }

    fun validateSnapshot(snapshot: BatchSnapshot, payloadBytes: Int? = null): SnapshotReject? =
        DownloadSnapshotGuard.validate(snapshot, payloadBytes)

    sealed interface CatalogState {
        data class Ready(val catalog: DownloadCatalog) : CatalogState
        data class Failed(val reason: DownloadUnavailableReason) : CatalogState
    }

    suspend fun loadCatalog(videoId: String, fresh: Boolean = false): CatalogState {
        val source = catalogs ?: return CatalogState.Failed(DownloadUnavailableReason.EXTRACTION_FAILED)
        return try {
            CatalogState.Ready(if (fresh) source.refresh(videoId) else source.catalog(videoId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val causes = generateSequence(failure as Throwable?) { it.cause }.toList()
            val reason = when {
                causes.any { it.message.orEmpty().contains("SESSION_") } -> DownloadUnavailableReason.SESSION_CHANGED
                causes.any { it is IOException } -> DownloadUnavailableReason.NETWORK_ERROR
                else -> DownloadUnavailableReason.EXTRACTION_FAILED
            }
            CatalogState.Failed(reason)
        }
    }

    suspend fun preview(videoId: String, config: DownloadConfig): PreviewState =
        when (val result = loadCatalog(videoId)) {
            is CatalogState.Ready -> preview(result.catalog, config)
            is CatalogState.Failed -> PreviewState.Unavailable(result.reason, result.reason.name)
        }

    fun preview(catalog: DownloadCatalog, config: DownloadConfig): PreviewState {
        return when (val selection = DownloadSelector.select(catalog, config)) {
            is DownloadSelection.Ready -> PreviewState.Ready(
                catalog = catalog,
                config = config,
                size = DownloadPresentation.sizeCopy(selection.plan),
                plan = selection.plan,
            )
            is DownloadSelection.Failed -> PreviewState.Unavailable(selection.reason, selection.message)
        }
    }

    /**
     * Final confirm only. Opening the sheet or changing preview options must
     * not call this. A new [submissionId] is minted here so process-death
     * restore cannot replay a submit.
     */
    suspend fun confirmSingle(request: DownloadRequest, persistPrefs: Boolean = true): EnqueueResult {
        if (persistPrefs) prefs.setLastConfig(request.config)
        return coordinator.enqueue(request, UUID.randomUUID().toString()).also { haptics?.perform(HapticsController.Event.CONFIRM) }
    }

    suspend fun confirmBatch(
        snapshot: BatchSnapshot,
        selection: BatchSelection,
        persistPrefs: Boolean = true,
    ): EnqueueResult {
        DownloadSnapshotGuard.validate(snapshot)?.let { throw IllegalArgumentException(it.message) }
        if (persistPrefs) prefs.setLastConfig(snapshot.config)
        return coordinator.enqueueBatch(snapshot, selection, UUID.randomUUID().toString()).also { haptics?.perform(HapticsController.Event.CONFIRM) }
    }

    fun pause(target: DownloadTarget) = viewModelScope.launch { coordinator.pause(target) }

    fun resume(target: DownloadTarget) = viewModelScope.launch {
        interaction.noteUserInitiated()
        coordinator.resume(target)
    }

    fun cancel(target: DownloadTarget) = viewModelScope.launch { coordinator.cancel(target) }

    fun retryFailed(target: DownloadTarget) = viewModelScope.launch {
        interaction.noteUserInitiated()
        coordinator.retryFailed(target)
    }

    fun redownload(target: DownloadTarget) = viewModelScope.launch {
        interaction.noteUserInitiated()
        coordinator.redownload(target)
    }

    fun remove(target: DownloadTarget, mode: RemoveMode) = viewModelScope.launch { coordinator.remove(target, mode) }

    fun clearHistory(alsoDeleteFiles: Boolean = false) = viewModelScope.launch {
        val mode = if (alsoDeleteFiles) RemoveMode.RECORD_AND_FILES else RemoveMode.RECORD_ONLY
        val terminal = coordinator.observeDownloads(DownloadFilter())
            .map { list -> list.map { DownloadUiMapper.item(it) }.filter(DownloadPresentation::isTerminal) }
            .first()
        terminal.forEach { coordinator.remove(DownloadTarget.Task(it.taskId), mode) }
    }

    fun setWifiOnly(value: Boolean) = prefs.setWifiOnly(value)

    fun setDefaultQuality(value: String) = prefs.setDefaultQuality(value)

    fun setMaxConnections(value: Int) {
        val n = value.coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)
        prefs.setMaxConnections(n)
        downloadHttp?.dispatcher?.maxRequests = n
    }

    sealed class PreviewState {
        data class Ready(
            val catalog: DownloadCatalog,
            val config: DownloadConfig,
            val size: SizeCopy,
            val plan: DownloadPlan = DownloadPlan(),
        ) : PreviewState()

        data class Unavailable(
            val reason: DownloadUnavailableReason,
            val message: String,
                ) : PreviewState()
    }
}
