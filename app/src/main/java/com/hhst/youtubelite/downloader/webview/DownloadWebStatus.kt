package com.hhst.youtubelite.downloader.webview

import com.google.gson.Gson
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.ui.DownloadItemUiState
import com.hhst.youtubelite.downloader.ui.VideoDownloadUiState

/** Room-derived page status. Never includes file paths. */
object DownloadWebStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val WAITING = "waiting"
    const val FAILED = "failed"
    const val COMPLETE = "complete"
    const val PARTIAL = "partial"

    private val gson = Gson()
    private val pathHints = listOf(
        "file:", "content://", "publishedUri", "publishedUris",
    )

    fun payload(state: VideoDownloadUiState): Map<String, Any?> {
        val item = state.tasks.maxWithOrNull(
            compareBy<DownloadItemUiState> { it.watchPageDownloaded }
                .thenBy { it.fullyDownloaded }
                .thenBy { it.updatedRank() },
        )
        return mapOf(
            "type" to "status",
            "videoId" to state.videoId,
            "state" to webState(item, state.watchPageDownloaded),
            "watchPageDownloaded" to state.watchPageDownloaded,
        )
    }

    fun json(state: VideoDownloadUiState): String = gson.toJson(payload(state))

    fun error(code: String, message: String): String = gson.toJson(
        mapOf(
            "type" to "error",
            "error" to code,
            "message" to message,
        ),
    )

    private fun webState(item: DownloadItemUiState?, watchPageDownloaded: Boolean): String {
        if (item?.fileMissing == true) return FAILED
        if (watchPageDownloaded) return COMPLETE
        if (item == null) return QUEUED
        if (item.completion == CompletionKind.PARTIAL || item.attachmentsOnly) return PARTIAL
        if (item.phase == DownloadPhase.COMPLETE && item.fullyDownloaded) return COMPLETE
        return when (item.status) {
            DownloadStatus.QUEUED -> QUEUED
            DownloadStatus.RUNNING, DownloadStatus.PAUSING, DownloadStatus.WAITING_SYSTEM -> RUNNING
            DownloadStatus.PAUSED -> PAUSED
            DownloadStatus.WAITING_NETWORK -> WAITING
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> FAILED
        }
    }

    fun containsPath(json: String): Boolean {
        val lower = json.lowercase()
        return pathHints.any { lower.contains(it.lowercase()) }
    }

    private fun DownloadItemUiState.updatedRank(): Long = when (status) {
        DownloadStatus.RUNNING -> 5
        DownloadStatus.PAUSING, DownloadStatus.WAITING_SYSTEM -> 4
        DownloadStatus.WAITING_NETWORK -> 3
        DownloadStatus.PAUSED, DownloadStatus.QUEUED -> 2
        DownloadStatus.FAILED -> 1
        DownloadStatus.CANCELLED -> 0
    }
}
