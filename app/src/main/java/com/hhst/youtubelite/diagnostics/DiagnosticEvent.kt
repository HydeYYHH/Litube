package com.hhst.youtubelite.diagnostics

import com.google.gson.annotations.SerializedName
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR, FATAL }
enum class DiagnosticOutcome { SUCCESS, FAILURE, CANCELLED, DEFERRED, SUPERSEDED }

/** Capture this at the operation boundary, then pass it through callbacks and IPC. */
data class DiagnosticContext(
    @SerializedName("operation_id") val operationId: String = UUID.randomUUID().toString(),
    @SerializedName("trace_id") val traceId: String = operationId,
    @SerializedName("parent_operation_id") val parentOperationId: String? = null,
    @SerializedName("video_id") val videoId: String? = null,
    @SerializedName("task_id") val taskId: String? = null,
    val generation: Long? = null,
    @SerializedName("tab_id") val tabId: Long? = null,
    @SerializedName("document_generation") val documentGeneration: Long? = null,
    @SerializedName("cast_session_id") val castSessionId: String? = null,
    @SerializedName("request_id") val requestId: String? = null,
) {
    fun child(requestId: String? = null): DiagnosticContext = copy(
        operationId = UUID.randomUUID().toString(), parentOperationId = operationId, requestId = requestId,
    )
}

data class DiagnosticEvent(
    @SerializedName("schema_version") val schemaVersion: Int = 2,
    val time: Long,
    @SerializedName("elapsed_ms") val elapsedMs: Long,
    val sequence: Long,
    val process: String,
    val pid: Int,
    val session: String,
    val category: String,
    val level: DiagnosticLevel,
    val event: String,
    val context: DiagnosticContext?,
    val fields: Map<String, Any?> = emptyMap(),
    val error: DiagnosticFailure? = null,
    @SerializedName("record_kind") val recordKind: String = "event",
    @SerializedName("incident_id") val incidentId: String? = null,
)

data class DiagnosticCause(val type: String, val code: String?, val message: String?, val frames: List<String>)
data class DiagnosticFailure(@SerializedName("error_id") val errorId: String, val causes: List<DiagnosticCause>,
    @SerializedName("stack_reference") val stackReference: Boolean = false)

fun interface DiagnosticSnapshotProvider { fun snapshot(): Map<String, Any?> }

class DiagnosticOperation internal constructor(
    val context: DiagnosticContext,
    private val finishEvent: (DiagnosticOutcome, String?, Throwable?, Map<String, Any?>) -> Unit,
) {
    private val ended = AtomicBoolean()
    fun finish(outcome: DiagnosticOutcome, reason: String? = null, failure: Throwable? = null, fields: Map<String, Any?> = emptyMap()) {
        if (ended.compareAndSet(false, true)) finishEvent(outcome, reason, failure, fields)
    }
}

/** A frozen execution identity travels with structured concurrency, including repository callbacks. */
class DiagnosticCoroutineContext(val diagnostic: DiagnosticContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticCoroutineContext>
}
