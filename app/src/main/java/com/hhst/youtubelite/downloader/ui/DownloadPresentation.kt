package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.net.DownloadBitrate
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadPlan
import com.hhst.youtubelite.downloader.resolve.DownloadCodecs
import com.hhst.youtubelite.downloader.resolve.DownloadSelector
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.datasource.StreamSelection
import java.util.Locale

enum class ManagerFilter { ALL, IN_PROGRESS, COMPLETED }

enum class SizeKind { EXACT, ESTIMATE, UNKNOWN }

data class SizeCopy(
    val kind: SizeKind,
    val bytes: Long? = null,
)

enum class PhaseCopy {
    RESOLVING,
    TRANSFERRING,
    WAITING_PROCESS,
    WAITING_NETWORK,
    MERGING,
    SAVING,
    COMPLETE,
    FILE_NOT_FOUND,
    PAUSED,
    FAILED,
    CANCELLED,
    QUEUED,
}

enum class DownloadRowAction {
    PAUSE,
    RESUME,
    CANCEL,
    RETRY,
    REDOWNLOAD,
    OPEN,
    SHARE,
    COPY_ID,
    DELETE,
}

enum class SnackbarKind { STARTED, NO_NEW_TASKS }

data class DownloadSnackbar(
    val kind: SnackbarKind,
    val batchId: String? = null,
)

object DownloadPresentation {

    fun managerFilter(filter: ManagerFilter): DownloadFilter = when (filter) {
        ManagerFilter.ALL -> DownloadFilter()
        ManagerFilter.IN_PROGRESS -> DownloadFilter(
            phases = setOf(
                DownloadPhase.RESOLVE,
                DownloadPhase.TRANSFER,
                DownloadPhase.WAITING_PROCESS,
                DownloadPhase.MERGE_VERIFY,
                DownloadPhase.SAVE,
            ),
        )
        ManagerFilter.COMPLETED -> DownloadFilter(phases = setOf(DownloadPhase.COMPLETE))
    }

    fun isTerminal(item: DownloadItemUiState): Boolean =
        item.phase == DownloadPhase.COMPLETE ||
            item.status == DownloadStatus.FAILED ||
            item.status == DownloadStatus.CANCELLED

    fun phaseCopy(item: DownloadItemUiState): PhaseCopy {
        if (item.fileMissing) return PhaseCopy.FILE_NOT_FOUND
        when (item.status) {
            DownloadStatus.WAITING_NETWORK -> return PhaseCopy.WAITING_NETWORK
            DownloadStatus.PAUSED, DownloadStatus.PAUSING -> return PhaseCopy.PAUSED
            DownloadStatus.FAILED -> return PhaseCopy.FAILED
            DownloadStatus.CANCELLED -> return PhaseCopy.CANCELLED
            DownloadStatus.QUEUED -> if (item.phase == DownloadPhase.TRANSFER ||
                item.phase == DownloadPhase.RESOLVE
            ) {
                return if (item.phase == DownloadPhase.RESOLVE) PhaseCopy.RESOLVING else PhaseCopy.QUEUED
            }
            else -> Unit
        }
        return when (item.phase) {
            DownloadPhase.RESOLVE -> PhaseCopy.RESOLVING
            DownloadPhase.TRANSFER -> PhaseCopy.TRANSFERRING
            DownloadPhase.WAITING_PROCESS -> PhaseCopy.WAITING_PROCESS
            DownloadPhase.MERGE_VERIFY -> PhaseCopy.MERGING
            DownloadPhase.SAVE -> PhaseCopy.SAVING
            DownloadPhase.COMPLETE -> PhaseCopy.COMPLETE
        }
    }

    fun sizeCopy(plan: DownloadPlan): SizeCopy {
        val media = listOfNotNull(plan.video, plan.audio, plan.muxed)
        if (media.isEmpty()) return SizeCopy(SizeKind.UNKNOWN)
        val lengths = media.map { DownloadBitrate.contentLengthFromUrl(it.format.url) }
        val expected = media.map { it.expectedBytes }
        if (expected.any { it == null }) {
            // Display estimate: bitrate × duration when trusted bytes are missing.
            val estimates = media.map { it.estimatedBytes }
            val known = if (estimates.all { it != null }) {
                estimates.filterNotNull().sum()
            } else {
                expected.filterNotNull().sum()
            }
            return if (known <= 0L) SizeCopy(SizeKind.UNKNOWN) else SizeCopy(SizeKind.ESTIMATE, known)
        }
        val total = expected.filterNotNull().sum()
        val exact = lengths.all { it != null && it > 0L }
        return SizeCopy(if (exact) SizeKind.EXACT else SizeKind.ESTIMATE, total)
    }

    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < mb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            bytes < gb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            else -> String.format(Locale.US, "%.2f GB", bytes / gb)
        }
    }

    private fun downloadableVideos(catalog: DownloadCatalog): List<Format> =
        catalog.formats
            .filter { (it.videoOnly || StreamSelection.isMuxed(it)) && it.height > 0 &&
                DownloadSelector.isFileStream(it) && DownloadCodecs.videoEnabled(it) &&
                (if (it.videoOnly) catalog.formats.any { audio -> audio.audioOnly &&
                    DownloadSelector.isFileStream(audio) && DownloadCodecs.comboEnabled(it, audio, false) }
                 else DownloadCodecs.comboEnabled(it, null, false)) }
            .sortedWith(compareByDescending<Format> { it.height }.thenByDescending { it.fps }.thenByDescending { it.videoOnly })

    private fun qualityLabel(format: Format): String = "${format.height}p" + if (format.fps > 30) format.fps.toString() else ""

    fun qualityOptions(catalog: DownloadCatalog): List<String> = downloadableVideos(catalog).map(::qualityLabel).distinct()

    fun qualityItag(catalog: DownloadCatalog, label: String): Int? =
        downloadableVideos(catalog).firstOrNull { qualityLabel(it) == label }?.itag

    /** Phases where bytes are actively moving or about to move. */
    fun isActivePhase(phase: DownloadPhase): Boolean = when (phase) {
        DownloadPhase.RESOLVE,
        DownloadPhase.TRANSFER,
        DownloadPhase.WAITING_PROCESS,
        DownloadPhase.MERGE_VERIFY,
        DownloadPhase.SAVE,
        -> true

        else -> false
    }

    /** Keep received bytes visible during pauses; percentages require a known total. */
    fun progressText(status: DownloadStatus, progressBytes: Long, expectedBytes: Long?): String? {
        if (status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED) return null
        if (status != DownloadStatus.RUNNING && progressBytes <= 0L) return null
        val received = progressBytes.coerceAtLeast(0L)
        return if (expectedBytes != null && expectedBytes > 0L) {
            val percent = (received.toDouble() / expectedBytes * 100).toInt().coerceIn(0, 100)
            "$percent% · ${formatBytes(received)} / ${formatBytes(expectedBytes)}"
        } else {
            received.takeIf { it > 0L }?.let(::formatBytes)
        }
    }

    fun progressFraction(progressBytes: Long, expectedBytes: Long?): Float? {
        if (expectedBytes == null || expectedBytes <= 0L) return null
        return (progressBytes.toDouble() / expectedBytes).toFloat().coerceIn(0f, 1f)
    }

    fun failureReason(item: DownloadItemUiState): String? {
        if (item.status != DownloadStatus.FAILED && item.failedKinds.isEmpty()) return null
        val message = item.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return if (message.length > 120) message.take(119) + "…" else message
    }

    fun actionsFor(item: DownloadItemUiState): List<DownloadRowAction> =
        buildList {
            // Completion and failure are orthogonal: a partially completed task
            // opens what exists and retries what failed.
            val anyFailed = item.failedKinds.isNotEmpty() || item.status == DownloadStatus.FAILED
            val hasOpenable = item.publishedUris.isNotEmpty() && !item.fileMissing
            when {
                item.phase == DownloadPhase.COMPLETE -> {
                    if (hasOpenable) {
                        add(DownloadRowAction.OPEN)
                        add(DownloadRowAction.SHARE)
                    }
                    if (anyFailed) add(DownloadRowAction.RETRY) else add(DownloadRowAction.REDOWNLOAD)
                }
                item.status == DownloadStatus.RUNNING ||
                    item.status == DownloadStatus.PAUSING ||
                    item.status == DownloadStatus.WAITING_SYSTEM -> {
                    add(DownloadRowAction.PAUSE)
                    add(DownloadRowAction.CANCEL)
                }
                item.status == DownloadStatus.PAUSED ||
                    item.status == DownloadStatus.WAITING_NETWORK ||
                    item.status == DownloadStatus.QUEUED -> {
                    add(DownloadRowAction.RESUME)
                    add(DownloadRowAction.CANCEL)
                }
                item.status == DownloadStatus.FAILED -> {
                    add(DownloadRowAction.RETRY)
                    add(DownloadRowAction.REDOWNLOAD)
                }
                else -> Unit
            }
            add(DownloadRowAction.COPY_ID)
            add(DownloadRowAction.DELETE)
        }.distinct()

    fun snackbarFor(result: EnqueueResult): DownloadSnackbar =
        if (result.newCount == 0) {
            DownloadSnackbar(SnackbarKind.NO_NEW_TASKS, batchId = result.batchId)
        } else {
            DownloadSnackbar(
                SnackbarKind.STARTED,
                batchId = result.batchId,
            )
        }

    fun fileMissing(
        phase: DownloadPhase,
        availability: FileAvailability,
        completion: CompletionKind,
    ): Boolean = phase == DownloadPhase.COMPLETE &&
        completion != CompletionKind.NONE &&
        availability != FileAvailability.EXISTS

}
