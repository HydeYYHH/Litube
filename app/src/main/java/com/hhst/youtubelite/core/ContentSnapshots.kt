package com.hhst.youtubelite.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * Bounded on-disk snapshots of account content for instant paint: atomic
 * writes, versioned row-capped payloads, answered only while version, query
 * and age still match.
 *
 * Snapshots carry rows only — never cursors or tokens: a caller paints the
 * rows and the live load establishes pagination, so an expired continuation
 * can never surface as live. Rows are keyed by account: private content stays
 * scoped to the account that produced it.
 */
class ContentSnapshots(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private class Envelope(
        val version: Int,
        val capturedAt: Long,
        val query: String,
        val rows: JsonElement?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when nothing usable is stored; an unusable file is removed rather than retried. */
    fun <T : Any> read(kind: String, account: String, query: String, rowsClass: Class<T>): List<T>? =
        synchronized(lock) {
            val file = file(kind, account, query)
            val atomic = SnapshotFile(file)
            val envelope = runCatching {
                atomic.openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    json.decodeFromString(Envelope.serializer(), output.toString(Charsets.UTF_8.name()))
                }
            }.getOrNull()
            val now = clock()
            val usable = envelope != null && envelope.version == VERSION && envelope.query == query &&
                envelope.rows != null && envelope.capturedAt <= now && envelope.capturedAt >= now - MAX_AGE_MS
            if (!usable) {
                atomic.delete()
                return@synchronized null
            }
            @Suppress("UNCHECKED_CAST")
            val rows = runCatching {
                json.decodeFromJsonElement(rowSerializer(rowsClass), envelope.rows) as List<T>
            }.getOrNull()?.takeIf { it.size <= MAX_ROWS }
            if (rows == null) atomic.delete()
            rows
        }

    fun <T : Any> write(kind: String, account: String, query: String, rows: List<T>, rowsClass: Class<T>) =
        synchronized(lock) {
            val dir = File(directory, kind)
            if (!dir.mkdirs() && !dir.isDirectory) return@synchronized
            val file = file(kind, account, query)
            val atomic = SnapshotFile(file)
            if (rows.isEmpty()) {
                atomic.delete()
                return@synchronized
            }
            runCatching {
                val bytes = json.encodeToString(Envelope.serializer(),
                    Envelope(VERSION, clock(), query, json.encodeToJsonElement(rowSerializer(rowsClass), rows.take(MAX_ROWS))))
                    .toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) {
                    atomic.delete()
                    return@synchronized
                }
                atomic.write(bytes)
                dir.listFiles()?.filter { it.extension == "json" && it != file }
                    ?.sortedByDescending(File::lastModified)?.drop(FILE_CAP_PER_KIND - 1)
                    ?.forEach { SnapshotFile(it).delete() }
            }
            Unit
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> rowSerializer(rowsClass: Class<T>): KSerializer<List<T>> =
        ListSerializer(serializer(rowsClass)) as KSerializer<List<T>>

    fun clear(kind: String? = null, account: String? = null) = synchronized(lock) {
        val roots = if (kind == null) directory.listFiles()?.filter { it.isDirectory }.orEmpty()
        else listOf(File(directory, kind))
        roots.forEach { root ->
            root.listFiles()?.forEach { file ->
                if (account == null || file.name.startsWith(accountPrefix(account))) file.delete()
            }
        }
    }

    private fun file(kind: String, account: String, query: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(query.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(File(directory, kind), accountPrefix(account) + digest + ".json")
    }

    private fun accountPrefix(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
        return value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32) + "-" + digest + "-"
    }

    private class SnapshotFile(private val base: File) {
        private val backup = File(base.path + ".bak")
        private val pending = File(base.path + ".new")

        fun openRead(): java.io.InputStream {
            recover()
            return base.inputStream()
        }

        private fun recover() {
            if (backup.exists()) {
                base.delete()
                if (!backup.renameTo(base)) throw IOException("snapshot_restore_failed")
            }
            pending.delete()
        }

        fun write(bytes: ByteArray) {
            recover()
            try {
                pending.outputStream().use { it.write(bytes); it.fd.sync() }
                if (base.exists() && !base.renameTo(backup)) throw IOException("snapshot_backup_failed")
                if (!pending.renameTo(base)) {
                    backup.renameTo(base)
                    throw IOException("snapshot_commit_failed")
                }
                backup.delete()
            } finally {
                pending.delete()
            }
        }

        fun delete() { base.delete(); backup.delete(); pending.delete() }
    }

    companion object {
        private val lock = Any()
        /** Bump when a snapshot's row shape changes; old payloads are discarded, not misread. */
        private const val VERSION = 1
        private const val MAX_ROWS = 120
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val FILE_CAP_PER_KIND = 48
        private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(72)
    }
}
