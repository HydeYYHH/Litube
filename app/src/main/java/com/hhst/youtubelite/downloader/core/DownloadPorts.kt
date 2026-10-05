package com.hhst.youtubelite.downloader.core

import java.io.File
import java.util.UUID
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan

/** Metadata, selection, and PoToken-aware resolve. */
interface DownloadResolver {
    suspend fun resolve(taskId: String): DownloadResolveOutcome
    fun sourcesOf(taskId: String): List<DownloadComponentSource> = emptyList()
    suspend fun recoverForbidden(taskId: String): Boolean = false
}

sealed class DownloadResolveOutcome {
    data class Ready(val taskId: String, val generation: Long) : DownloadResolveOutcome()
    data class Failed(val reason: String, val message: String) : DownloadResolveOutcome()
    data object Stale : DownloadResolveOutcome()
    data object Cancelled : DownloadResolveOutcome()
    data object Ignored : DownloadResolveOutcome()
}

/**
 * Resolved byte source. URLs are not identity; [resourceIdentity] is the `dl:` key.
 */
data class DownloadComponentSource(
    val assetKind: AssetKind,
    val componentKind: InputComponentKind,
    val url: String,
    val resourceIdentity: String? = null,
    val expectedBytes: Long? = null,
    val mimeType: String? = null,
    val sidecar: Boolean = false,
    val rangeModeName: String? = null,
    val methodName: String? = null,
    val cookiePolicyName: String? = null,
    val client: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val postPulse: Boolean = false,
    @Transient val requestPlan: RequestPlan? = null,
)

/**
 * Byte-range HTTP. Pause/cancel must drop the current request while keeping
 * verified chunks.
 */
interface DownloadTransport {
    fun cancelInFlight(taskId: String)
    suspend fun downloadComponent(
        taskId: String,
        component: InputComponent,
        source: DownloadComponentSource,
        dest: File,
        verified: List<DownloadChunk>,
        // Live bytes are unverified; only onChunk supplies resumable checkpoints.
        onProgress: suspend (DownloadChunk, Long?) -> Boolean = { _, _ -> true },
        onChunk: suspend (DownloadChunk) -> Boolean,
    ): TransferResult = TransferResult.Failed("transport not configured")
}

sealed class TransferResult {
    data object Completed : TransferResult()
    data object Paused : TransferResult()
    data object Cancelled : TransferResult()
    data object WaitingNetwork : TransferResult()
    data class Failed(val reason: String) : TransferResult()
}

/** Sample-based mux / verify. */
interface DownloadFinalizer {
    suspend fun muxAndVerify(
        inputs: List<File>,
        output: File,
        audioOnly: Boolean,
    ): MuxResult
}

sealed class MuxResult {
    data class Ok(val durationUs: Long) : MuxResult()
    data class Gated(val reason: String) : MuxResult()
    data class Failed(val reason: String) : MuxResult()
    data object Interrupted : MuxResult()
}

/**
 * Production implementation is WorkManager (API 26–33) / UIDT (API 34+) plus
 * a network-free FinalizeWorker. JVM tests may keep an in-process scheduler.
 */
interface DownloadScheduler {
    suspend fun schedule(taskId: String)
    suspend fun cancel(taskId: String)
}

data class DeleteResult(
    val ok: Boolean,
    val reason: String? = null,
) {
    companion object {
        val OK = DeleteResult(true)
        fun fail(reason: String) = DeleteResult(false, reason)
    }
}

data class PublishRequest(
    val publishId: String,
    val assetId: String,
    val displayName: String,
    val mimeType: String,
    val source: File,
    val existingUri: String? = null,
    val existingPhase: PublishPhase = PublishPhase.PENDING,
    /**
     * Called as soon as the backend target exists, before any bytes are
     * copied, so the target URI is durable and a crash cannot orphan it.
     */
    val onTargetCreated: (suspend (String) -> Unit)? = null,
)

sealed class PublishResult {
    data class Published(val uri: String) : PublishResult()
    data class Failed(val reason: String) : PublishResult()
    data object Interrupted : PublishResult()
}

interface DownloadPublisher {
    fun delete(uri: String): DeleteResult
    /** Unknown backends assume the file still exists so reconcile cannot wipe history. */
    fun exists(uri: String): Boolean = true
    suspend fun publish(request: PublishRequest): PublishResult =
        PublishResult.Failed("publisher not configured")
}

fun interface IdFactory {
    fun next(kind: String): String
}

object UuidIdFactory : IdFactory {
    override fun next(kind: String): String = "$kind-${UUID.randomUUID()}"
}

object NoOpTransport : DownloadTransport {
    override fun cancelInFlight(taskId: String) = Unit
}

object NoOpScheduler : DownloadScheduler {
    override suspend fun schedule(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
}

object NoOpPublisher : DownloadPublisher {
    override fun delete(uri: String): DeleteResult = DeleteResult.OK
}

object NoOpResolver : DownloadResolver {
    override suspend fun resolve(taskId: String): DownloadResolveOutcome = DownloadResolveOutcome.Ignored
}

object NoOpFinalizer : DownloadFinalizer {
    override suspend fun muxAndVerify(
        inputs: List<File>,
        output: File,
        audioOnly: Boolean,
    ): MuxResult = MuxResult.Failed("finalizer not configured")
}
