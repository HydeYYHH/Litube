package com.hhst.youtubelite.diagnostics

import android.os.SystemClock
import java.util.UUID

/** Java-friendly protocol telemetry. Payloads, receiver credentials and friendly names are excluded. */
class CastDiagnosticTrace {
    val sessionId: String = UUID.randomUUID().toString()
    @Volatile var context: DiagnosticContext = DiagnosticContext(castSessionId = sessionId); private set
    private val sessionOperation by lazy { AppLog.operation(AppLog.Category.CAST, "session", context) }
    private var finished = false
    private data class Pending(val operation: DiagnosticOperation, val at: Long, val type: String)
    private val pending = linkedMapOf<Int, Pending>()
    fun video(videoId: String?, generation: Long) { context = context.copy(videoId = videoId, generation = generation) }
    fun phase(name: String) { sessionOperation; AppLog.event(AppLog.Category.CAST, name, context = context); AppLog.snapshot(AppLog.Category.CAST, context, mapOf("phase" to name, "pending_requests" to synchronized(this) { pending.keys.toList() })) }
    fun error(name: String, failure: Throwable?) { AppLog.event(AppLog.Category.CAST, name, failure = failure, critical = true, context = context) }
    @Synchronized fun request(type: String, id: Int) {
        if (finished) return
        sessionOperation
        if (type == "GET_STATUS" || type == "PING" || type == "PONG") { AppLog.detail(AppLog.Category.CAST, "protocol_poll", mapOf("type" to type), context); return }
        if (!type.matches(Regex("[A-Z_]{1,40}"))) return
        while (pending.size >= 64) pending.remove(pending.keys.first())?.operation?.finish(DiagnosticOutcome.SUPERSEDED, "diagnostic_capacity")
        pending.remove(id)?.operation?.finish(DiagnosticOutcome.SUPERSEDED, "request_id_reused")
        pending[id] = Pending(AppLog.operation(AppLog.Category.CAST, "command", context.child(id.toString()), mapOf("command" to type)), SystemClock.elapsedRealtime(), type)
    }
    @Synchronized fun response(type: String, id: Int) {
        if (!type.matches(Regex("[A-Z_]{1,40}"))) return
        val failed = type in setOf("LAUNCH_ERROR", "LOAD_FAILED", "INVALID_REQUEST", "LOAD_TIMEOUT")
        val cancelled = type == "LOAD_CANCELLED"
        val matchedId = if (id == 0 && type == "RECEIVER_STATUS") pending.entries.firstOrNull { it.value.type == "LAUNCH" }?.key else id
        val operation = matchedId?.let { pending.remove(it)?.operation }
        if (operation != null) operation.finish(if (cancelled) DiagnosticOutcome.CANCELLED else if (failed) DiagnosticOutcome.FAILURE else DiagnosticOutcome.SUCCESS, type)
        else if (failed) AppLog.event(AppLog.Category.CAST, "command_rejected", mapOf("reason" to type, "request_id" to id), critical = true, context = context)
        else AppLog.detail(AppLog.Category.CAST, "protocol_response", mapOf("type" to type, "request_id" to id), context)
    }
    @Synchronized fun sample() {
        val now = SystemClock.elapsedRealtime()
        pending.entries.filter { now - it.value.at >= 15_000 }.map { it.key }.forEach { response("LOAD_TIMEOUT", it) }
    }
    @Synchronized fun finish() {
        if (finished) return
        finished = true
        pending.values.forEach { it.operation.finish(DiagnosticOutcome.CANCELLED, "session_ended") }; pending.clear()
        AppLog.event(AppLog.Category.CAST, "session_ended", context = context)
        sessionOperation.finish(DiagnosticOutcome.CANCELLED, "session_ended")
    }
}
