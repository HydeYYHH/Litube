package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.hhst.youtubelite.core.ArtworkRequests
import java.util.concurrent.atomic.AtomicInteger

/** Coalesces author artwork reads with bounded concurrency and expiring results. */
class MediaAvatarDirectory(
    private val client: MediaClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry(val avatar: String?, val expiresAt: Long)

    /** LRU-bounded so a long session cannot grow the directory without limit. */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            val evict = size > MAX_ENTRIES
            // Backoff counters die with their channel; both maps stay equally bounded.
            if (evict) misses.remove(eldest.key)
            return evict
        }
    }
    private val misses = HashMap<String, Int>()
    private var generation = 0L
    private data class Key(val channelId: String, val generation: Long)
    private val requests = ArtworkRequests<Key, String?>(scope, LANES, MAX_IN_FLIGHT) { key ->
        resolve(key.channelId, key.generation)
    }

    suspend fun avatar(channelId: String): String? {
        val id = channelId.trim()
        if (!id.startsWith(CHANNEL_PREFIX)) return null
        val key = synchronized(cache) {
            cache[id]?.let { if (it.expiresAt > clock()) return it.avatar }
            Key(id, generation)
        }
        val avatar = requests.get(key)
        return synchronized(cache) { avatar.takeIf { generation == key.generation } }
    }

    suspend fun resolveAll(channelIds: Collection<String>, onResolved: (String, String) -> Unit) = coroutineScope {
        val ids = channelIds.distinct()
        val next = AtomicInteger()
        repeat(minOf(LANES, ids.size)) {
            launch {
                while (true) {
                    val id = ids.getOrNull(next.getAndIncrement()) ?: break
                    avatar(id)?.let { onResolved(id, it) }
                }
            }
        }
    }

    private suspend fun resolve(key: String, epoch: Long): String? {
        synchronized(cache) { if (generation != epoch) return null }
        return try {
            val thumbnail = client.read { context, http ->
                loadChannel(key, context, http = http).requireValue().thumbnail
            }?.takeIf(String::isNotBlank)
            store(key, thumbnail, failed = false, epoch)
            thumbnail
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            store(key, null, failed = true, epoch)
            null
        }
    }

    private fun store(key: String, avatar: String?, failed: Boolean, epoch: Long) {
        val now = clock()
        synchronized(cache) {
            if (generation != epoch) return
            if (failed) {
                val count = (misses[key] ?: 0) + 1
                misses[key] = count
                val ttl = minOf(BACKOFF_STEP shl (count - 1).coerceAtMost(4), MAX_BACKOFF)
                cache[key] = Entry(null, now + ttl)
            } else {
                misses.remove(key)
                val ttl = if (avatar == null) MISSING_TTL else SUCCESS_TTL
                cache[key] = Entry(avatar, now + ttl)
            }
        }
    }

    fun clear() {
        synchronized(cache) { generation++; cache.clear(); misses.clear() }
        requests.clear()
    }

    fun dispose() {
        clear()
        scope.cancel()
    }

    companion object {
        private const val CHANNEL_PREFIX = "UC"
        private const val LANES = 3
        private const val MAX_IN_FLIGHT = 64
        private const val MAX_ENTRIES = 256
        private const val SUCCESS_TTL = 30 * 60 * 1000L
        /** Real-missing: the response is clean but carries no avatar URL. */
        private const val MISSING_TTL = 10 * 60 * 1000L
        private const val BACKOFF_STEP = 5 * 60 * 1000L
        private const val MAX_BACKOFF = 60 * 60 * 1000L
    }
}
