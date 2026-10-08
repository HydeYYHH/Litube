package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.DownloadPhase

/** Phases past byte transfer (mux / verify / save) need finalize-family work. */
fun workKindForPhase(phase: DownloadPhase): DownloadWorkKind =
    if (phase == DownloadPhase.WAITING_PROCESS || phase == DownloadPhase.MERGE_VERIFY ||
        phase == DownloadPhase.SAVE
    ) DownloadWorkKind.FINALIZE else DownloadWorkKind.TRANSFER

object DownloadWorkNames {
    private const val TRANSFER_PREFIX = "dl-transfer-"
    const val FINALIZE_PREFIX = "dl-finalize-"
    const val KEY_BATCH_ID = "batchId"

    fun transfer(batchId: String): String = TRANSFER_PREFIX + batchId

    fun finalize(batchId: String): String = FINALIZE_PREFIX + batchId

    fun notificationId(batchId: String): Int =
        0x44000000 + (batchId.hashCode() and 0x00FFFFFF)

    fun uidtJobId(batchId: String): Int =
        0x5E000000 + (batchId.hashCode() and 0x00FFFFFF)
}

enum class DownloadWorkKind {
    TRANSFER,
    MUX,
    SAVE,
    FINALIZE,
}

object ScheduleReasons {
    const val ENQUEUE = "ENQUEUE"
    const val RESUME = "RESUME"
    const val RETRY = "RETRY"
    const val SYSTEM = "SYSTEM"

    fun isUserInitiated(reason: String): Boolean =
        reason == ENQUEUE || reason == RESUME || reason == RETRY
}
