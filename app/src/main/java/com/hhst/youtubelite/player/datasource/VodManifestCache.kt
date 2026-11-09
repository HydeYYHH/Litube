@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import android.os.SystemClock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.hhst.youtubelite.diagnostics.AppLog

/** Immutable VOD manifests only. Live playlists always bypass this cache. */
internal class VodManifestCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val bytes: ByteArray, val until: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val lock = Any()
    private data class Pending(val result: CompletableFuture<ByteArray?>, val deadline: Long)
    private data class Lookup(val bytes: ByteArray?, val waitedMs: Long = 0)
    private val pending = mutableMapOf<String, Pending>()

    private fun cachedOrJoining(key: String, whole: Boolean): Lookup {
        val (hit, preparation) = synchronized(lock) {
            entries.entries.removeAll { it.value.until <= now() }
            entries[key]?.bytes to pending[key]
        }
        if (hit != null || !whole || preparation == null) return Lookup(hit)
        val began = SystemClock.elapsedRealtime()
        val remaining = minOf(1_000L, preparation.deadline - began)
        if (remaining <= 0) return Lookup(null)
        val bytes = try { preparation.result.get(remaining, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { null }
        catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("MANIFEST_JOIN_CANCELLED").apply { initCause(cancelled) }
        }
        catch (_: java.util.concurrent.ExecutionException) { null }
        AppLog.detail(AppLog.Category.PLAYER, "hls_manifest_join", mapOf(
            "duration_ms" to SystemClock.elapsedRealtime() - began, "reused" to (bytes != null)))
        return Lookup(bytes, SystemClock.elapsedRealtime() - began)
    }

    fun factory(scope: String, expires: Long, current: () -> Boolean,
                upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private var source: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            private var capture: ByteArrayOutputStream? = null
            private var key = ""
            private var complete = false
            private var preparation: Pending? = null
            private fun finishPreparation(bytes: ByteArray?) {
                val owned = preparation ?: return
                preparation = null
                synchronized(lock) { if (pending[key] === owned) pending.remove(key) }
                owned.result.complete(bytes)
            }
            override fun addTransferListener(listener: TransferListener) { listeners += listener }
            override fun open(dataSpec: DataSpec): Long {
                if (!current()) throw IOException("MEDIA_SESSION_CHANGED")
                if (expires <= now()) throw IOException("MEDIA_URL_EXPIRED")
                key = "$scope:${dataSpec.uri}"
                val whole = dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()
                val lookup = cachedOrJoining(key, whole)
                val hit = lookup.bytes
                if (!current()) throw IOException("MEDIA_SESSION_CHANGED")
                if (expires <= now()) throw IOException("MEDIA_URL_EXPIRED")
                val deadline = (dataSpec.customData as? PrefetchDeadline)?.elapsedRealtimeMs
                if (hit == null && whole && deadline != null && deadline > SystemClock.elapsedRealtime()) synchronized(lock) {
                    pending.entries.removeAll { it.value.deadline <= SystemClock.elapsedRealtime() }
                    if (key !in pending && pending.size < 4) {
                        preparation = Pending(CompletableFuture(), deadline).also { pending[key] = it }
                    }
                }
                val delegate = if (hit == null) upstream.createDataSource() else ByteArrayDataSource(hit)
                source = delegate
                listeners.forEach(delegate::addTransferListener)
                if (hit == null && whole) {
                    capture = ByteArrayOutputStream()
                }
                // Joining is part of this open, not extra budget before it; speculative callers carry their own deadline.
                val upstreamSpec = if (hit == null && deadline == null && lookup.waitedMs > 0)
                    dataSpec.buildUpon().setCustomData(ManifestJoinWait(lookup.waitedMs, dataSpec.customData)).build()
                else dataSpec
                return try { delegate.open(upstreamSpec) } catch (failure: Exception) {
                    finishPreparation(null)
                    throw failure
                }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!current()) throw IOException("MEDIA_SESSION_CHANGED")
                val count = requireNotNull(source).read(buffer, offset, length)
                capture?.let { bytes ->
                    if (count > 0) {
                        if (bytes.size() + count <= MAX_DOCUMENT_BYTES) bytes.write(buffer, offset, count)
                        else capture = null
                    } else if (count == C.RESULT_END_OF_INPUT && !complete) {
                        complete = true
                        val document = bytes.toByteArray().takeIf { current() && it.isNotEmpty() }
                        if (document != null) synchronized(lock) {
                            entries[key] = Entry(document, minOf(now() + 120_000, expires - 30_000))
                            var total = entries.values.sumOf { it.bytes.size }
                            while (total > MAX_CACHE_BYTES) total -= entries.remove(entries.keys.first())!!.bytes.size
                        }
                        finishPreparation(document)
                        capture = null
                    }
                }
                return count
            }
            override fun getUri(): Uri? = source?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
            override fun close() { try { source?.close() } finally {
                finishPreparation(null)
                source = null; capture = null; complete = false
            } }
        }
    }
    companion object {
        // A normal VOD playlist can exceed 256 KiB; share the document bound with speculative reads.
        const val MAX_DOCUMENT_BYTES = 1024 * 1024
        private const val MAX_CACHE_BYTES = 1024 * 1024
    }
}
