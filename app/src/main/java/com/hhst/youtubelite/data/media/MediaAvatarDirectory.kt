package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannelAvatar
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.core.JsonCache
import com.liskovsoft.youtubeapi.videoinfo.V2.MenuEndpoint
import com.liskovsoft.youtubeapi.videoinfo.V2.loadMenuChannelId
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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
    private val disk: JsonCache? = null,
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
    private val videoChannels = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }
    private val hydrate by lazy { scope.async {
        val saved = withContext(Dispatchers.IO) { runCatching { disk?.get(DISK_KEY, String::class.java) }.getOrNull() }
        synchronized(cache) {
            if (generation == 0L) saved?.lineSequence()?.take(MAX_ENTRIES * 2)?.forEach { row ->
                val parts = row.split('\t')
                if (parts.size == 4) {
                    val expiry = parts[3].toLongOrNull()
                    if (expiry != null && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                        val target = if (parts[0] == "A") cache else if (parts[0] == "V") videoChannels else null
                        target?.putIfAbsent(parts[1], Entry(parts[2], expiry))
                    }
                }
            }
        }
    } }
    private data class Key(val channelId: String, val generation: Long)
    private val requests = ArtworkRequests<Key, String?>(scope, LANES, MAX_IN_FLIGHT) { key ->
        resolve(key.channelId, key.generation)
    }
    private data class ChannelKey(val videoId: String, val generation: Long, val endpoint: MenuEndpoint)
    private val channelRequests = ArtworkRequests<ChannelKey, String?>(scope, LANES, MAX_IN_FLIGHT) { key ->
        try {
            client.readBackgroundIdempotent { context, http -> loadMenuChannelId(key.endpoint, context, http).requireValue() }
                .also { id -> synchronized(cache) {
                    if (generation == key.generation) { videoChannels[key.videoId] = Entry(id, clock() + SUCCESS_TTL); persist() }
                } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun channel(videoId: String, endpoint: MenuEndpoint): String? {
        if (disk != null) hydrate.await()
        val key = synchronized(cache) {
            videoChannels[videoId]?.let { return it.avatar }
            ChannelKey(videoId, generation, endpoint)
        }
        return channelRequests.get(key).takeIf { synchronized(cache) { generation == key.generation } }
    }

    suspend fun avatar(channelId: String): String? {
        val id = channelId.trim()
        if (!id.startsWith(CHANNEL_PREFIX)) return null
        if (disk != null) hydrate.await()
        val key = synchronized(cache) {
            cache[id]?.let { if (it.expiresAt > clock()) return it.avatar }
            Key(id, generation)
        }
        val avatar = requests.get(key)
        return synchronized(cache) { avatar.takeIf { generation == key.generation } }
    }

    /** Subscription tabs already carry public channel artwork; reuse it without another request. */
    fun seed(avatars: Map<String, String>) = synchronized(cache) {
        avatars.forEach { (id, url) ->
            if (id.startsWith(CHANNEL_PREFIX) && url.isNotBlank()) {
                cache[id] = Entry(url, clock() + SUCCESS_TTL)
                misses.remove(id)
            }
        }
        persist()
    }

    fun cached(channelId: String): String? = synchronized(cache) { cache[channelId]?.avatar }

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
        val started = System.nanoTime()
        return try {
            val thumbnail = client.readBackgroundIdempotent { context, http ->
                loadChannelAvatar(key, context, http = http).requireValue()
            }?.takeIf(String::isNotBlank)
            // A sparse channel response can temporarily omit its avatar too.
            store(key, thumbnail, failed = thumbnail == null, epoch)
            AppLog.detail(AppLog.Category.GALLERY, "avatar.resolve", mapOf(
                "outcome" to if (thumbnail == null) "MISSING" else "SUCCESS",
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000))
            thumbnail ?: synchronized(cache) { cache[key]?.avatar }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            store(key, null, failed = true, epoch)
            AppLog.event(AppLog.Category.GALLERY, "avatar.resolve", mapOf(
                "outcome" to "FAILURE",
                "reason" to ((error as? MediaException)?.failure?.code ?: error.javaClass.simpleName),
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000), critical = false)
            synchronized(cache) { cache[key]?.avatar }
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
                cache[key] = Entry(cache[key]?.avatar, now + ttl)
            } else {
                misses.remove(key)
                cache[key] = Entry(avatar, now + SUCCESS_TTL)
                persist()
            }
        }
    }

    /** One bounded public-artwork snapshot; no credentials, menus or account data. Caller holds cache. */
    private fun persist() {
        val storage = disk ?: return
        val rows = cache.entries.filter { it.value.avatar != null }.map { "A\t${it.key}\t${it.value.avatar}\t${it.value.expiresAt}" } +
            videoChannels.entries.map { "V\t${it.key}\t${it.value.avatar}\t${it.value.expiresAt}" }
        runCatching { storage.put(DISK_KEY, rows.joinToString("\n"), DISK_TTL) }
    }

    fun clear(clearDisk: Boolean = true) {
        synchronized(cache) { generation++; cache.clear(); misses.clear(); videoChannels.clear(); if (clearDisk) disk?.invalidate(DISK_KEY) }
        requests.clear()
        channelRequests.clear()
    }

    fun dispose() {
        clear(clearDisk = false)
        scope.cancel()
    }

    companion object {
        private const val CHANNEL_PREFIX = "UC"
        private const val LANES = 3
        private const val MAX_IN_FLIGHT = 64
        private const val MAX_ENTRIES = 256
        private const val SUCCESS_TTL = 7 * 24 * 60 * 60 * 1000L
        private const val DISK_TTL = 30 * 24 * 60 * 60 * 1000L
        private const val DISK_KEY = "artwork:channel-directory-v1"
        // Transient failures should recover during browsing, rather than hiding an
        // author's avatar for five minutes after one lost connection.
        private const val BACKOFF_STEP = 5 * 1000L
        private const val MAX_BACKOFF = 60 * 1000L
    }
}
