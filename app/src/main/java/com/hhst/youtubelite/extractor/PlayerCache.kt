package com.hhst.youtubelite.extractor

import android.os.SystemClock
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Raw WEB-client /player responses keyed by video id, served to the WebView to
 * avoid duplicate requests. The TTL mirrors [CacheTtl.STREAM_MS] — both count
 * the same googlevideo URL freshness — and [Extractor.invalidateStream] drops
 * entries here alongside the Cache layer. In-flight fetches are tracked so
 * callers can wait for a pending response.
 */
class PlayerCache {
    private val entries = LruCache<String, Entry>(MAX)
    private val inFlight = ConcurrentHashMap<String, CountDownLatch>()

    private class Entry(val bytes: ByteArray, val createdAt: Long)

    fun get(videoId: String): ByteArray? {
        val entry = entries[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - entry.createdAt > TTL_MS) {
            entries.remove(videoId)
            return null
        }
        return entry.bytes
    }

    fun put(videoId: String, bytes: ByteArray) {
        if (!acceptsBody(bytes.size)) return
        entries.put(videoId, Entry(bytes, SystemClock.elapsedRealtime()))
    }

    /** True when [size] fits the in-memory /player body cap. */
    internal fun acceptsBody(size: Int): Boolean = size in 1..MAX_ENTRY_BYTES

    /** Drops any cached raw /player response for [videoId]. */
    fun invalidate(videoId: String) {
        entries.remove(videoId)
    }

    /** Runs [block] while the fetch of [videoId] is marked in-flight. */
    fun <T> withInFlight(videoId: String, block: () -> T): T {
        val latch = CountDownLatch(1)
        inFlight[videoId] = latch
        try {
            return block()
        } finally {
            inFlight.remove(videoId, latch)
            latch.countDown()
        }
    }

    /** Blocks until the fetch for [videoId] completes or [timeoutMs] elapses. */
    fun await(videoId: String, timeoutMs: Long): Boolean {
        val latch = inFlight[videoId] ?: return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private companion object {
        /** Same freshness window as the native stream cache — single source. */
        val TTL_MS: Long = CacheTtl.STREAM_MS
        const val MAX = 16
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    }
}
