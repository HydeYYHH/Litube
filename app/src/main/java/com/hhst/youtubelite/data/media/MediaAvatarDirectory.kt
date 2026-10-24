package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Author avatars filled in once per channel identity and shared by every screen that
 * renders one. Responses that already carry the avatar never reach here; a missing one
 * costs a single coalesced channel read, capped in parallel and cached with expiries,
 * so a missing thumbnail never turns into one unbounded request per card.
 */
class MediaAvatarDirectory(private val client: MediaClient, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(val avatar: String?, val expiresAt: Long)

    /** LRU-bounded so a long session cannot grow the directory without limit. */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }
    private val misses = HashMap<String, Int>()
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()
    private val lanes = Semaphore(LANES)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Resolved avatar or null while unknown; callers keep their placeholder meanwhile. */
    suspend fun avatar(channelId: String): String? {
        val key = channelId.trim()
        // Only real channel identities can resolve; anything else stays a placeholder.
        if (!key.startsWith(CHANNEL_PREFIX)) return null
        synchronized(cache) { cache[key] }?.let { entry ->
            if (entry.expiresAt > clock()) return entry.avatar
        }
        val flight = inFlight[key]
            ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { resolve(key) }
        // Coalesced: every caller awaiting the same channel shares one request; await starts it.
        val winner = inFlight.putIfAbsent(key, flight) ?: flight
        return try {
            winner.await()
        } finally {
            if (winner.isCompleted) inFlight.remove(key, winner)
        }
    }

    private suspend fun resolve(key: String): String? = lanes.withPermit {
        try {
            val thumbnail = client.read { context, http ->
                loadChannel(key, context, http = http).requireValue().thumbnail
            }?.takeIf(String::isNotBlank)
            store(key, thumbnail, failed = false)
            thumbnail
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            store(key, null, failed = true)
            null
        }
    }

    private fun store(key: String, avatar: String?, failed: Boolean) {
        val now = clock()
        synchronized(cache) {
            val count = if (failed) (misses[key] ?: 0) + 1 else 0
            misses[key] = count
            val ttl = if (!failed) SUCCESS_TTL
            else minOf(BACKOFF_STEP shl (count - 1).coerceAtMost(4), MAX_BACKOFF)
            cache[key] = Entry(avatar, now + ttl)
        }
    }

    fun dispose() {
        scope.cancel()
        inFlight.clear()
        synchronized(cache) { cache.clear() }
    }

    companion object {
        private const val CHANNEL_PREFIX = "UC"
        private const val LANES = 3
        private const val MAX_ENTRIES = 256
        private const val SUCCESS_TTL = 30 * 60 * 1000L
        private const val BACKOFF_STEP = 5 * 60 * 1000L
        private const val MAX_BACKOFF = 60 * 60 * 1000L
    }
}
