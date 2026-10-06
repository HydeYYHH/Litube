package com.hhst.youtubelite.diagnostics

import java.io.File

/** Bounded disk storage shared by the independent process writers. */
class DiagnosticFiles(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000
        const val SEGMENT_BYTES = 512 * 1024
    }

    fun files(): List<File> = directory.listFiles()?.filter { it.isFile && it.extension == "jsonl" }.orEmpty()

    fun prune(maxBytes: Long = MAX_BYTES, retentionMs: Long = RETENTION_MS) {
        val cutoff = clock() - retentionMs
        files().filter { it.lastModified() < cutoff }.forEach { it.delete() }
        val remaining = files().sortedWith(compareBy<File> { it.name.contains("-critical-") }.thenBy { it.lastModified() })
        var size = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (size <= maxBytes) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }
}

object DiagnosticRedaction {
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val headers = Regex("(?im)(authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+")
    private val credentials = Regex("(?i)(cookie|authorization|password|token|visitor_data|signature|sig|key)\\s*[:=]\\s*[^\\s,;]+")
    private val urls = Regex("https?://[^\\s<>\"]+")
    private val paths = Regex("/(?:storage|data|sdcard)/[^\\s:]+")
    fun text(raw: String): String = raw.take(32_768)
        .replace(headers) { "${it.groupValues[1]}=[redacted]" }
        .replace(bearer, "Bearer [redacted]")
        .replace(urls) { match -> match.value.substringBefore('?').substringBefore('#') }
        .replace(credentials) { "${it.groupValues[1]}=[redacted]" }
        .replace(paths, "[local path]")
    /** Exception messages may contain response bodies; retain types and call sites instead. */
    fun failure(error: Throwable): String = text(buildString {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.size < 8 && seen.add(current)) {
            appendLine(current.javaClass.name)
            current.stackTrace.take(64).forEach { appendLine("  at $it") }
            current = current.cause
            if (current != null) append("Caused by: ")
        }
    })
    fun field(key: String, value: Any?): String =
        if (Regex("(?i)cookie|authorization|password|token|visitor|signature").containsMatchIn(key)) "[redacted]"
        else text(value?.toString().orEmpty())
}
