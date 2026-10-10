package com.hhst.youtubelite.diagnostics

import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock

/** Segment locks protect active files; a shared lock serializes quota and cleanup. */
class DiagnosticFiles(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val NORMAL_BYTES = 16L * 1024 * 1024
        const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000
        const val SEGMENT_BYTES = 512 * 1024
        private val localLock = ReentrantLock()
    }
    private data class Segment(val file: File, val handle: RandomAccessFile, val lock: FileLock, val at: Long)
    private val segments = mutableMapOf<String, Segment>()
    private val gson = Gson()
    private var nextSegment = 0L
    @Volatile var snapshotTruncatedBytes = 0L; private set
    fun files(): List<File> = if (directory.exists()) directory.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList() else emptyList()
    fun snapshot(deadline: () -> Boolean): Pair<Map<String, String>, Int> = locked(deadline) {
        val result = linkedMapOf<String, String>(); var skipped = 0
        snapshotTruncatedBytes = 0
        files().sortedBy { it.path }.forEach { file ->
            if (deadline()) { skipped++; return@forEach }
            runCatching {
                val bytes = ByteArray(file.length().coerceAtMost(2L * 1024 * 1024).toInt())
                if (file.length() > bytes.size) {
                    snapshotTruncatedBytes += file.length() - bytes.size
                    skipped++
                }
                RandomAccessFile(file, "r").use { it.readFully(bytes) }
                result[file.relativeTo(directory).invariantSeparatorsPath] = bytes.toString(Charsets.UTF_8).substringBeforeLast('\n', "")
            }.onFailure { skipped++ }
        }
        result to skipped
    } ?: (emptyMap<String, String>() to 1)
    private fun critical(file: File) = file.name.contains("critical") || file.name.startsWith("incidents-")
    private fun <T> locked(work: () -> T): T = requireNotNull(locked({ false }, work))
    private fun <T> locked(deadline: () -> Boolean, work: () -> T): T? {
        while (!localLock.tryLock()) { if (deadline()) return null; Thread.sleep(5) }
        try {
            if (deadline()) return null
            check(directory.exists() || directory.mkdirs()) { "DIAGNOSTIC_DIRECTORY_FAILED" }
            return RandomAccessFile(File(directory, "quota.lock"), "rw").use { handle ->
                var quota: FileLock? = null
                while (quota == null) {
                    if (deadline()) return@use null
                    quota = runCatching { handle.channel.tryLock() }.getOrNull()
                    if (quota == null) Thread.sleep(5)
                }
                quota.use { work() }
            }
        } finally { localLock.unlock() }
    }
    fun prune(maxBytes: Long = MAX_BYTES, retentionMs: Long = RETENTION_MS) = locked { pruneLocked(maxBytes, retentionMs) }
    private fun deleteClosed(file: File): Boolean = runCatching {
        if (!file.exists()) return@runCatching false
        val closed = RandomAccessFile(file, "rw").use { handle ->
            val lock = handle.channel.tryLock() ?: return@use false
            lock.use { handle.setLength(0) }
            true
        }
        closed && file.delete()
    }.getOrDefault(false)
    private fun pruneLocked(maxBytes: Long, retentionMs: Long, incoming: Long = 0, ordinaryIncoming: Long = 0) {
        files().filter { it.lastModified() < clock() - retentionMs }.forEach(::deleteClosed)
        val remaining = files().sortedWith(compareBy<File> { critical(it) }.thenBy { it.lastModified() })
        var total = remaining.sumOf { it.length() } + incoming
        var ordinary = remaining.filterNot(::critical).sumOf { it.length() } + ordinaryIncoming
        for (file in remaining) {
            if (total <= maxBytes && ordinary <= minOf(NORMAL_BYTES, maxBytes)) break
            if (ordinary > minOf(NORMAL_BYTES, maxBytes) && total <= maxBytes && critical(file)) continue
            val length = file.length()
            if (deleteClosed(file)) { total -= length; if (!critical(file)) ordinary -= length }
        }
    }
    /** Returns dropped rows if protected active segments leave no room. */
    fun append(records: List<DiagnosticEvent>, priority: Boolean, deadline: () -> Boolean = { false }): Int = locked(deadline) {
        var dropped = 0
        val initial = files()
        var totalBytes = initial.sumOf { it.length() }
        var ordinaryBytes = initial.filterNot(::critical).sumOf { it.length() }
        records.forEach { record ->
            if (deadline()) { dropped++; return@forEach }
            val line = (gson.toJson(record) + "\n").toByteArray(Charsets.UTF_8)
            val key = "${record.pid}-${record.session}-${if (priority) "incidents" else "events"}"
            var segment = segments[key]
            if (segment != null && (segment.file.length() + line.size > SEGMENT_BYTES || clock() - segment.at >= 3_600_000)) {
                release(key); segment = null
            }
            if (totalBytes + line.size > MAX_BYTES || (!priority && ordinaryBytes + line.size > NORMAL_BYTES)) {
                release(key); segment = null
                pruneLocked(MAX_BYTES, RETENTION_MS, line.size.toLong(), if (priority) 0 else line.size.toLong())
                val remaining = files()
                totalBytes = remaining.sumOf { it.length() }
                ordinaryBytes = remaining.filterNot(::critical).sumOf { it.length() }
            }
            if (totalBytes + line.size > MAX_BYTES || (!priority && ordinaryBytes + line.size > NORMAL_BYTES)) {
                dropped++; return@forEach
            }
            if (segment == null) {
                val dir = File(directory, "v2/${record.pid}-${record.session}").apply { check(exists() || mkdirs()) }
                val file = File(dir, "${if (priority) "incidents" else "events"}-${clock()}-${nextSegment++}.jsonl")
                val handle = RandomAccessFile(file, "rw")
                segment = Segment(file, handle, handle.channel.lock(), clock())
                segments[key] = segment
            }
            val active = requireNotNull(segment)
            active.handle.channel.position(active.handle.length())
            val bytes = ByteBuffer.wrap(line)
            while (bytes.hasRemaining()) active.handle.channel.write(bytes)
            totalBytes += line.size
            if (!priority) ordinaryBytes += line.size
            active.file.setLastModified(active.at)
        }
        dropped
    } ?: records.size
    private fun release(key: String) { segments.remove(key)?.let { it.lock.release(); it.handle.close() } }
    override fun close() { localLock.lock(); try { segments.keys.toList().forEach(::release) } finally { localLock.unlock() } }
}

object DiagnosticRedaction {
    @Volatile private var salt = UUID.randomUUID().toString()
    private val errorIds = WeakHashMap<Throwable, String>()
    /** One private installation salt allows identifiers to correlate across auxiliary processes. */
    fun initialize(directory: File) = synchronized(errorIds) {
        runCatching {
            directory.mkdirs()
            RandomAccessFile(File(directory, "identity.lock"), "rw").use { handle ->
                val deadline = System.nanoTime() + 300_000_000
                var lock: FileLock? = null
                while (lock == null && System.nanoTime() < deadline) {
                    lock = runCatching { handle.channel.tryLock() }.getOrNull()
                    if (lock == null) Thread.sleep(5)
                }
                checkNotNull(lock) { "DIAGNOSTIC_IDENTITY_LOCK_TIMEOUT" }.use {
                val file = File(directory, "identity.salt")
                if (!file.exists()) file.writeText(UUID.randomUUID().toString())
                file.readText().takeIf { it.matches(Regex("[A-Za-z0-9-]{36}")) }?.let { salt = it }
            } }
        }
        Unit
    }
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val headers = Regex("(?im)(authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+")
    private val credentials = Regex("(?i)(cookie|authorization|password|token|visitor_data|signature|sig|key)\\s*[:=]\\s*[^\\s,;]+")
    private val urls = Regex("https?://[^\\s<>\"]+")
    private val paths = Regex("(?:/(?:storage|data|sdcard)/[^\\s:]+|[A-Za-z]:[\\\\/][^\\s\"]+)")
    private val addresses = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6 = Regex("(?i)(?<![A-Za-z0-9])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.]{0,39}(?:%[A-Za-z0-9_.-]+)?")
    private val secrets = Regex("(?i)cookie|authorization|password|token|visitor|signature|response_body|script_body")
    private val safeSecretMetadata = setOf("token_present", "token_status", "token_age_ms", "signature_timestamp")
    private val identities = Regex("(?i)^(device_name|device_id|host|address|ip|file_path|path|uri|url|link_url)$")
    fun identity(raw: String): String = MessageDigest.getInstance("SHA-256").digest((salt + raw).toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    fun text(raw: String): String = raw.take(32_768)
        .replace(headers) { "${it.groupValues[1]}=[redacted]" }.replace(bearer, "Bearer [redacted]")
        .replace(urls) { "[resource:${identity(it.value)}]" }
        .replace(credentials) { "${it.groupValues[1]}=[redacted]" }.replace(paths, "[local path]")
        .replace(addresses) { "[address:${identity(it.value)}]" }
        .replace(ipv6) { "[address:${identity(it.value)}]" }
    fun fields(raw: Map<String, Any?>): Map<String, Any?> = raw.entries.take(64).associate { (key, item) -> key.take(80) to value(key, item, 0) }
    fun field(key: String, value: Any?): String = value(key, value, 0)?.toString().orEmpty()
    private fun value(key: String, raw: Any?, depth: Int): Any? {
        if (key in setOf("error", "message", "error_message") && raw is String) return safeMessage(raw) ?: "[message omitted]"
        if (secrets.containsMatchIn(key) && key !in safeSecretMetadata) return "[redacted]"
        if (raw == null) return "unknown"
        if (raw is Boolean) return raw
        if (raw is Number) return if (raw.toDouble().isFinite()) raw else "unknown"
        if (depth >= 4) return "[depth limit]"
        return when (raw) {
            is Map<*, *> -> raw.entries.take(64).associate { it.key.toString().take(80) to value(it.key.toString(), it.value, depth + 1) }
            is Iterable<*> -> raw.take(32).map { value(key, it, depth + 1) }
            is Array<*> -> raw.take(32).map { value(key, it, depth + 1) }
            is String -> if (identities.matches(key)) {
                if (raw.matches(Regex("\\[identity:[a-f0-9]{16}\\]"))) raw else "[identity:${identity(raw)}]"
            } else text(raw).take(1024)
            is Enum<*> -> raw.name
            else -> "[unsupported value]"
        }
    }
    fun resource(raw: String): Map<String, Any?> = runCatching {
        val uri = URI(raw); val host = uri.host.orEmpty().lowercase(); val path = uri.path.orEmpty().lowercase()
        val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val kind = when {
            path.contains("init") -> "initialization"
            path.endsWith(".m3u8") || host.startsWith("manifest.") -> "playlist"
            path.endsWith(".mpd") -> "manifest"
            path.contains("timedtext") -> "subtitle"
            path.contains("videoplayback") || path.endsWith(".ts") || path.endsWith(".m4s") -> "media"
            host.endsWith("ytimg.com") || host.endsWith("ggpht.com") || host.endsWith("googleusercontent.com") -> "image"
            path.contains("youtubei") -> "api"
            else -> "other"
        }
        mapOf("resource_id" to identity(raw), "resource_kind" to kind,
            "host_family" to when { host.endsWith("googlevideo.com") -> "googlevideo"; host.endsWith("youtube.com") -> "youtube"; host.endsWith("ytimg.com") -> "ytimg"; else -> "other" },
            "itag" to (query["itag"]?.toIntOrNull() ?: Regex("/itag/(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull()),
            "sq" to (query["sq"]?.toLongOrNull() ?: Regex("/sq/(\\d+)").find(path)?.groupValues?.get(1)?.toLongOrNull()))
    }.getOrElse { mapOf("resource_id" to identity(raw), "resource_kind" to "unknown") }
    internal fun safeMessage(raw: String?): String? = raw?.takeIf {
        it.matches(Regex("(?:MEDIA|JS|EXTRACTION|SESSION|PAGE|TOKEN|RECOVERY|DIAGNOSTIC|DOWNLOAD)_[A-Z0-9_]{1,100}")) ||
            it.matches(Regex("(?i)(timeout|socket closed|canceled|cancelled|unexpected end of stream|connection reset|broken pipe|no space left on device|permission denied|ENOSPC|EACCES|stream was reset: (CANCEL|REFUSED_STREAM|INTERNAL_ERROR))"))
    }
    fun structuredFailure(error: Throwable): DiagnosticFailure {
        val seen = mutableSetOf<Throwable>(); val causes = mutableListOf<DiagnosticCause>(); var current: Throwable? = error
        while (current != null && seen.size < 8 && seen.add(current)) {
            val message = safeMessage(current.message)
            causes += DiagnosticCause(current.javaClass.name, message?.takeIf { it.contains('_') }, message,
                current.stackTrace.take(48).map { text(it.toString()) })
            current = current.cause
        }
        val root = seen.lastOrNull() ?: error
        val id = synchronized(errorIds) { errorIds.getOrPut(root) { UUID.randomUUID().toString() } }
        return DiagnosticFailure(id, causes)
    }
    fun failure(error: Throwable): String = structuredFailure(error).causes.joinToString("\nCaused by: ") { cause ->
        cause.type + (cause.message?.let { ": $it" } ?: "") + cause.frames.joinToString("\n", "\n") { "  at $it" }
    }
}
