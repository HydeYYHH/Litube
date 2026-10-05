package com.hhst.youtubelite.downloader.core

enum class BackgroundDecision {
    APPLY,
    STALE_GENERATION,
    USER_PAUSE,
    USER_CANCEL,
    COMPLETED_HOLDS,
    FAILED_HOLDS,
}

/** Phase/status/generation rules. Encoded here so tests can pin the matrix. */
object DownloadStateMachine {

    private val activeStatuses = setOf(
        DownloadStatus.QUEUED,
        DownloadStatus.RUNNING,
        DownloadStatus.WAITING_NETWORK,
        DownloadStatus.WAITING_SYSTEM,
        DownloadStatus.PAUSING,
    )

    fun acceptBackground(
        task: DownloadTask,
        generation: Long,
        incoming: DownloadStatus,
    ): BackgroundDecision {
        if (generation != task.executionGeneration) return BackgroundDecision.STALE_GENERATION
        if (task.userCancelled || task.status == DownloadStatus.CANCELLED) {
            return BackgroundDecision.USER_CANCEL
        }
        val paused = task.userPaused ||
            task.status == DownloadStatus.PAUSED ||
            task.status == DownloadStatus.PAUSING
        if (paused && incoming in activeStatuses) return BackgroundDecision.USER_PAUSE
        if (task.status == DownloadStatus.FAILED && incoming in activeStatuses) {
            return BackgroundDecision.FAILED_HOLDS
        }
        if (task.phase == DownloadPhase.COMPLETE &&
            task.completion == CompletionKind.FULL &&
            incoming in activeStatuses
        ) {
            return BackgroundDecision.COMPLETED_HOLDS
        }
        return BackgroundDecision.APPLY
    }

    fun completionOf(assets: List<DownloadAsset>): CompletionKind {
        if (assets.isEmpty()) return CompletionKind.NONE
        val published = assets.count { it.published }
        return when {
            published == assets.size -> CompletionKind.FULL
            published > 0 -> CompletionKind.PARTIAL
            else -> CompletionKind.NONE
        }
    }

    fun fullyDownloaded(assets: List<DownloadAsset>): Boolean =
        assets.isNotEmpty() && assets.all { it.published }

    fun fileAvailability(assets: List<DownloadAsset>): FileAvailability {
        val published = assets.filter { it.published }
        if (published.isEmpty()) return FileAvailability.MISSING
        if (published.any { it.fileAvailability == FileAvailability.INACCESSIBLE }) {
            return FileAvailability.INACCESSIBLE
        }
        if (published.any { it.fileAvailability == FileAvailability.MISSING }) {
            return FileAvailability.MISSING
        }
        return FileAvailability.EXISTS
    }

    fun rollupPhase(assets: List<DownloadAsset>, current: DownloadPhase): DownloadPhase {
        return if (assets.isNotEmpty() && assets.all { it.published || it.failed }) {
            DownloadPhase.COMPLETE
        } else {
            current
        }
    }

    fun rollupStatus(assets: List<DownloadAsset>, task: DownloadTask): DownloadStatus {
        if (task.userCancelled) return DownloadStatus.CANCELLED
        if (task.userPaused) return DownloadStatus.PAUSED
        val allTerminal = assets.all { it.published || it.failed }
        val anyFailed = assets.any { it.failed }
        return when {
            allTerminal && anyFailed -> DownloadStatus.FAILED
            allTerminal && assets.all { it.published } -> DownloadStatus.QUEUED
            else -> task.status
        }
    }
}
