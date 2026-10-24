package com.hhst.youtubelite.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * Bounded on-disk snapshots of account content for instant paint, one file per
 * (kind, account, query). Writes are atomic (temp file + rename), payloads are versioned
 * and row-capped, and a read only answers when version, query and age still match.
 *
 * Snapshots carry rows only — never cursors or tokens. A caller paints the rows and lets
 * the live load establish pagination state, so an expired continuation can never surface
 * as live. Rows are keyed by account: private content stays scoped to the account that
 * produced it.
 */
class ContentSnapshots(
    private val directory: File,
    private val gson: Gson = Gson(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Envelope(val version: Int, val capturedAt: Long, val query: String, val rows: JsonElement?)

    /** Null when nothing usable is stored; an unusable file is removed rather than retried. */
    fun <T> read(kind: String, account: String, query: String, rowsType: Type): List<T>? {
        val file = file(kind, account, query)
        if (!file.exists()) return null
        val envelope = runCatching { gson.fromJson(file.readText(), Envelope::class.java) }.getOrNull()
        val usable = envelope != null && envelope.version == VERSION && envelope.query == query &&
            envelope.rows != null && clock() - envelope.capturedAt <= MAX_AGE_MS
        if (!usable) {
            file.delete()
            return null
        }
        val rows = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson<List<T>>(envelope.rows, rowsType) as? List<T>
        }.getOrNull()
        if (rows == null) file.delete()
        return rows
    }

    fun write(kind: String, account: String, query: String, rows: List<Any>) {
        if (rows.isEmpty()) return
        val dir = File(directory, kind)
        if (!dir.mkdirs() && !dir.isDirectory) return
        // Keep the snapshot set bounded: oldest captures go when a kind outgrows its share.
        dir.listFiles()?.sortedBy(File::lastModified)?.dropLast(FILE_CAP_PER_KIND - 1)?.forEach(File::delete)
        val file = file(kind, account, query)
        val tmp = File(dir, file.name + ".tmp")
        runCatching {
            tmp.writeText(gson.toJson(Envelope(VERSION, clock(), query, gson.toJsonTree(rows.take(MAX_ROWS)))))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure {
            tmp.delete()
        }
    }

    fun clear(kind: String? = null, account: String? = null) {
        val roots = if (kind == null) directory.listFiles()?.filter { it.isDirectory }.orEmpty()
        else listOf(File(directory, kind))
        roots.forEach { root ->
            root.listFiles()?.forEach { file ->
                if (account == null || file.nameWithoutExtension.startsWith(sanitize(account) + "-")) file.delete()
            }
        }
    }

    private fun file(kind: String, account: String, query: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(query.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return File(File(directory, kind), sanitize(account) + "-" + digest + ".json")
    }

    private fun sanitize(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)

    companion object {
        /** Bump when a snapshot's row shape changes; old payloads are discarded, not misread. */
        private const val VERSION = 1
        private const val MAX_ROWS = 120
        private const val FILE_CAP_PER_KIND = 48
        private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(72)
    }
}
