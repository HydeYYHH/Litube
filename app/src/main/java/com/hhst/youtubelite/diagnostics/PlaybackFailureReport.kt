package com.hhst.youtubelite.diagnostics

import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PlaybackFailureReport(val id: String, val videoId: String?, val summary: String, val text: String) {
    companion object {
        private const val MAX_LENGTH = 48_000

        fun create(
            summary: String,
            error: Throwable,
            phase: String,
            context: DiagnosticContext?,
            environment: Map<String, Any?>,
            playback: Map<String, Any?>,
            events: List<DiagnosticEvent>,
            timestamp: Long = System.currentTimeMillis(),
        ): PlaybackFailureReport {
            val id = UUID.randomUUID().toString()
            val report = buildString {
                appendLine("Litube playback report")
                appendLine("Report: $id")
                val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                appendLine("Time: ${time.format(Date(timestamp))}")
                appendLine("Video: ${context?.videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: "unknown"}")
                appendLine("Phase: ${DiagnosticRedaction.text(phase)}")
                appendLine("Trace: ${context?.traceId ?: "unknown"}")
                appendLine("Generation: ${context?.generation ?: "unknown"}")
                appendLine()
                appendLine("Environment")
                DiagnosticRedaction.fields(environment).forEach { (key, value) -> appendLine("$key: $value") }
                appendLine()
                appendLine("Playback")
                DiagnosticRedaction.fields(playback).forEach { (key, value) -> appendLine("$key: $value") }
                appendLine()
                appendLine("Exception")
                val seen = mutableSetOf<Throwable>()
                var cause: Throwable? = error
                while (cause != null && seen.size < 8 && seen.add(cause)) {
                    appendLine(cause.javaClass.name)
                    cause.message?.let { appendLine(DiagnosticRedaction.text(it).take(512)) }
                    cause.stackTrace.take(24).forEach { appendLine("  at ${DiagnosticRedaction.text(it.toString())}") }
                    cause = cause.cause
                    if (cause != null) appendLine("Caused by:")
                }
                appendLine()
                appendLine("Recent events")
                events.filter { context != null && it.context?.traceId == context.traceId }.takeLast(32).forEach {
                    appendLine("${it.elapsedMs} ${it.category}.${it.event} ${DiagnosticRedaction.fields(it.fields)}")
                    it.error?.causes?.take(4)?.forEach { cause -> appendLine("  ${cause.type} ${cause.code.orEmpty()}") }
                }
            }.let { if (it.length <= MAX_LENGTH) it else it.take(MAX_LENGTH - 24) + "\n[report truncated]\n" }
            return PlaybackFailureReport(id, context?.videoId, summary, report)
        }
    }
}
