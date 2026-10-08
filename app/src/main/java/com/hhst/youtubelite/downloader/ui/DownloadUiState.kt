package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.BatchStats
import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadStateMachine
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.ItemSnapshot
import com.hhst.youtubelite.downloader.core.TaskSnapshot

data class DownloadItemUiState(
    val taskId: String,
    val itemId: String? = null,
    val batchId: String? = null,
    val videoId: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val phase: DownloadPhase,
    val status: DownloadStatus,
    val fileAvailability: FileAvailability,
    val completion: CompletionKind,
    val fullyDownloaded: Boolean,
    val watchPageDownloaded: Boolean = false,
    val fileMissing: Boolean = false,
    val skipped: Boolean = false,
    val progressBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val qualityLabel: String? = null,
    val audioOnly: Boolean = false,
    val attachmentsOnly: Boolean = false,
    val errorMessage: String? = null,
    val publishedUris: List<String> = emptyList(),
    val failedKinds: List<AssetKind> = emptyList(),
)

data class BatchUiState(
    val name: String,
    val stats: BatchStats,
    val items: List<DownloadItemUiState>,
)

data class VideoDownloadUiState(
    val videoId: String,
    val tasks: List<DownloadItemUiState>,
    /** Watch-page "Downloaded" — never true for partial or attachments-only. */
    val watchPageDownloaded: Boolean,
)

object DownloadUiMapper {
    fun item(snapshot: TaskSnapshot, item: ItemSnapshot? = null): DownloadItemUiState {
        val assets = snapshot.assets.map { it.asset }
        // Sidecars do not have a size until fetched. They must not turn known
        // video/audio progress into an indeterminate bar. Muxed streams also
        // leave an unused audio component placeholder behind.
        val mediaAssets = snapshot.assets.filter {
            it.asset.kind == AssetKind.VIDEO || it.asset.kind == AssetKind.AUDIO
        }
        val components = mediaAssets.ifEmpty { snapshot.assets }.flatMap { it.components }
            .filter { it.component.mimeType != null || it.component.resourceIdentity != null ||
                it.component.expectedBytes != null || it.chunks.isNotEmpty() }
        val progress = components.sumOf { component -> component.chunks.sumOf { it.receivedBytes } }
        val lengths = components.map { it.component.expectedBytes?.takeIf { bytes -> bytes > 0L } }
        val expected = lengths.takeIf { it.isNotEmpty() && it.all { bytes -> bytes != null } }
            ?.filterNotNull()?.sum()
        val allPublished = DownloadStateMachine.fullyDownloaded(assets)
        val media = assets.filter { it.kind == AssetKind.VIDEO || it.kind == AssetKind.AUDIO }
        val fileMissing = DownloadPresentation.fileMissing(
            snapshot.task.phase,
            snapshot.task.fileAvailability,
            snapshot.task.completion,
        )
        val watch = media.isNotEmpty() && allPublished && !fileMissing
        return DownloadItemUiState(
            taskId = snapshot.task.id,
            itemId = item?.item?.id,
            batchId = item?.item?.batchId ?: snapshot.batchId,
            videoId = snapshot.task.videoId,
            title = item?.item?.title ?: snapshot.task.title,
            author = item?.item?.author ?: snapshot.task.author,
            thumbnailUrl = item?.item?.thumbnailUrl ?: snapshot.task.thumbnailUrl,
            phase = snapshot.task.phase,
            status = snapshot.task.status,
            fileAvailability = snapshot.task.fileAvailability,
            completion = snapshot.task.completion,
            fullyDownloaded = allPublished,
            watchPageDownloaded = watch,
            fileMissing = fileMissing,
            skipped = item?.item?.skipped ?: false,
            progressBytes = progress,
            expectedBytes = expected,
            qualityLabel = snapshot.task.config.videoQuality,
            audioOnly = snapshot.task.config.audioOnly,
            attachmentsOnly = snapshot.task.config.attachmentsOnly,
            errorMessage = snapshot.task.errorMessage?.takeIf {
                snapshot.task.status == DownloadStatus.FAILED && it.isNotBlank()
            } ?: assets.firstOrNull { it.failed && !it.errorMessage.isNullOrBlank() }?.errorMessage
                ?: snapshot.task.errorMessage,
            publishedUris = assets.mapNotNull { it.publishedUri },
            failedKinds = assets.filter { it.failed }.map { it.kind },
        )
    }

    fun batch(view: BatchView): BatchUiState = BatchUiState(
        name = view.batch.name,
        stats = view.stats,
        items = view.items.map { item(it.task, it) },
    )

    fun video(videoId: String, tasks: List<TaskSnapshot>): VideoDownloadUiState {
        val mapped = tasks.map { item(it) }
        return VideoDownloadUiState(
            videoId = videoId,
            tasks = mapped,
            watchPageDownloaded = mapped.any { it.watchPageDownloaded },
        )
    }
}
