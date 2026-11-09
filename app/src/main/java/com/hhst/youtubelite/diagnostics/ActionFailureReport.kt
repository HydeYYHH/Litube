package com.hhst.youtubelite.diagnostics

import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.hhst.youtubelite.data.media.MediaException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Copyable, sanitized outcome report for a failed account write: operation, outcome
 * class derived from the server reply shape, HTTP status and reason; tokens, cookies
 * and authorization values never enter the text.
 */
data class ActionFailureReport(val id: String, val operation: String, val summary: String, val text: String) {
    companion object {
        fun create(
            operation: String,
            error: Throwable,
            detail: Map<String, Any?> = emptyMap(),
            timestamp: Long = System.currentTimeMillis(),
        ): ActionFailureReport {
            val id = UUID.randomUUID().toString()
            val failure = (error as? MediaException)?.failure
            val outcome = outcome(error)
            val status = failure?.status ?: (error as? UnconfirmedActionException)?.httpStatus
            val context = (error as? UnconfirmedActionException)?.detail.orEmpty() + detail
            val reason = when {
                failure != null -> failure.code.take(96)
                error is UnconfirmedActionException -> "action_unconfirmed"
                error.message != null -> error.message!!.take(96)
                else -> error.javaClass.simpleName
            }
            val report = buildString {
                appendLine("Litube action report")
                appendLine("Report: $id")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(timestamp))}")
                appendLine("Operation: ${DiagnosticRedaction.text(operation)}")
                appendLine("Outcome: $outcome")
                appendLine("HTTP status: ${status ?: "none"}")
                appendLine("Reason: ${DiagnosticRedaction.text(reason)}")
                failure?.retryAfterMs?.let { appendLine("Retry after ms: $it") }
                if (context.isNotEmpty()) {
                    appendLine()
                    appendLine("Detail")
                    DiagnosticRedaction.fields(context).forEach { (key, value) -> appendLine("$key: $value") }
                }
            }
            return ActionFailureReport(id, operation, outcome, report)
        }

        /** Response-structure class: how the write outcome was (or was not) established. */
        internal fun outcome(error: Throwable): String = when {
            error is SignInRequiredException -> "sign_in_required"
            error is UnconfirmedActionException -> "unconfirmed"
            error !is MediaException -> "unexpected:${error.javaClass.simpleName}"
            else -> when (error.failure.kind) {
                RequestFailure.AUTH -> "sign_in_required"
                RequestFailure.HTTP -> if (error.failure.status == 429) "rate_limited" else "refused"
                RequestFailure.VERIFICATION -> "verification"
                RequestFailure.TRANSPORT -> "transport"
                RequestFailure.PARSE -> "unparsed_response"
                RequestFailure.INVALID -> "invalid_request"
                RequestFailure.CANCELLED -> "cancelled"
                RequestFailure.UNSUPPORTED -> "unsupported"
                else -> "unexpected"
            }
        }
    }
}

fun writeFailureNotice(
    operation: String,
    error: Throwable,
    detail: Map<String, Any?> = emptyMap(),
    message: String? = null,
): AppNotice {
    val report = ActionFailureReport.create(operation, error, detail)
    AppLog.event(AppLog.Category.NETWORK, "write_failure", mapOf(
        "operation" to report.operation,
        "outcome" to report.summary,
        "report_id" to report.id,
    ), failure = error)
    return AppNotice.Failure(message ?: report.summary, report)
}
