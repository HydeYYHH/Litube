package com.hhst.youtubelite.downloader.engine

object DownloadWorkNames {
    const val TRANSFER_PREFIX = "dl-transfer-"
    const val FINALIZE_PREFIX = "dl-finalize-"
    const val KEY_BATCH_ID = "batchId"
    const val KEY_TASK_ID = "taskId"

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
    const val NETWORK = "NETWORK"
    const val SYSTEM = "SYSTEM"

    fun isUserInitiated(reason: String): Boolean =
        reason == ENQUEUE || reason == RESUME || reason == RETRY
}
