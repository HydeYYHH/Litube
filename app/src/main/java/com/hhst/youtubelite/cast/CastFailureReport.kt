package com.hhst.youtubelite.cast

import android.os.Build
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.diagnostics.DiagnosticRedaction
import java.time.Instant
import java.util.UUID

data class CastFailureReport(val id: String, val text: String) {
    companion object {
        fun create(protocol: String, device: CastDevice?, videoId: String?, stage: String, error: Throwable): CastFailureReport {
            val id = UUID.randomUUID().toString()
            val request = generateSequence(error) { it.cause }.take(10).filterIsInstance<DlnaRequestException>().firstOrNull()
            return CastFailureReport(id, buildString {
                appendLine("Litube cast report")
                appendLine("Report: $id")
                appendLine("Time: ${Instant.now()}")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android: ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Protocol: $protocol")
                appendLine("Device: ${device?.name?.take(160) ?: "unknown"}")
                appendLine("Route: ${device?.id?.let { DiagnosticRedaction.identity(it) } ?: "unknown"}")
                appendLine("Video: ${videoId ?: "unknown"}")
                appendLine("Stage: ${request?.stage ?: stage}")
                request?.let {
                    appendLine("Endpoint: ${it.endpoint.scheme}://${it.endpoint.host}:${it.endpoint.port}${it.endpoint.encodedPath}")
                    appendLine("HTTP: ${it.status ?: "no response"}; UPnP: ${it.code ?: "none"}")
                }
                appendLine("Error:")
                append(DiagnosticRedaction.text(error.stackTraceToString()))
            })
        }
    }
}
