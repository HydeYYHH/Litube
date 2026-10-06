package com.hhst.youtubelite.downloader.engine

data class UniqueWorkRequest(
    val uniqueName: String,
    val kind: DownloadWorkKind,
    val batchId: String,
    val requiresNetwork: Boolean,
    val wifiOnly: Boolean,
    val replace: Boolean,
)

data class WorkSnapshot(
    val uniqueName: String,
    val active: Boolean,
    val kind: DownloadWorkKind,
)

enum class EnqueueOutcome {
    CREATED,
    ALREADY_PRESENT,
    REPLACED,
}

interface WorkEnqueuePort {
    fun enqueueUnique(request: UniqueWorkRequest): EnqueueOutcome
    fun cancelUnique(name: String)
    fun state(name: String): WorkSnapshot?
}

data class UidtJobRequest(
    val jobId: Int,
    val batchId: String,
    val estimatedBytes: Long = 0L,
    val wifiOnly: Boolean = false,
)

sealed class UidtRegisterResult {
    data class Created(val jobId: Int) : UidtRegisterResult()
    data class Restored(val jobId: Int) : UidtRegisterResult()
    data object RejectedNoUserInteraction : UidtRegisterResult()
}

interface UidtJobPort {
    fun register(request: UidtJobRequest, legalUserInteraction: Boolean): UidtRegisterResult
    fun cancel(jobId: Int)
    fun isActive(jobId: Int): Boolean
}
