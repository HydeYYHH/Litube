package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.RelatedPage
import com.hhst.youtubelite.watch.domain.WatchPage
import com.hhst.youtubelite.watch.domain.WatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded, expiring reads shared within one content identity. */
class CachedWatchRepository(
    private val delegate: WatchRepository,
    private val identity: () -> Long,
    private val maxEntries: Int = 8,
    private val maxAgeMs: Long = 5 * 60_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) : WatchRepository {
    private data class Key(val identity: Long, val videoId: String)
    private data class Entry(val page: WatchPage, val createdAt: Long)
    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > maxEntries
    }
    private val inFlight = mutableMapOf<Key, CompletableDeferred<WatchPage>>()

    init {
        require(maxEntries > 0)
        require(maxAgeMs >= 0)
    }

    override suspend fun watch(videoId: String): WatchPage = read(videoId, fresh = false)
    override suspend fun watchFresh(videoId: String): WatchPage = read(videoId, fresh = true)
    override suspend fun menu(video: com.hhst.youtubelite.watch.domain.WatchRelated) = delegate.menu(video)
    override suspend fun feedback(video: com.hhst.youtubelite.watch.domain.WatchRelated, action: com.hhst.youtubelite.watch.domain.WatchVideoMenuAction) {
        delegate.feedback(video, action)
        clearMemory()
    }

    fun clearMemory() = synchronized(lock) {
        cache.clear()
        inFlight.clear()
    }

    private suspend fun read(videoId: String, fresh: Boolean): WatchPage {
        val key = Key(identity(), videoId)
        while (true) {
            val deferred: CompletableDeferred<WatchPage>
            val owner: Boolean
            synchronized(lock) {
                if (!fresh) {
                    cache[key]?.takeIf { clock() - it.createdAt < maxAgeMs }?.let { return it.page }
                }
                val existing = inFlight[key].takeUnless { fresh }
                owner = existing == null
                deferred = existing ?: CompletableDeferred<WatchPage>().also { inFlight[key] = it }
            }
            if (!owner) {
                try {
                    return deferred.await().also { checkIdentity(key) }
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    checkIdentity(key)
                    continue
                }
            }
            try {
                val page = if (fresh) delegate.watchFresh(videoId) else delegate.watch(videoId)
                currentCoroutineContext().ensureActive()
                checkIdentity(key)
                synchronized(lock) {
                    checkIdentity(key)
                    // Only the latest read may publish into the cache.
                    if (inFlight[key] === deferred) {
                        cache[key] = Entry(page, clock())
                        inFlight.remove(key)
                    }
                    deferred.complete(page)
                }
                return page
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                    deferred.completeExceptionally(error)
                }
                throw error
            }
        }
    }

    private fun checkIdentity(key: Key) {
        if (identity() != key.identity) throw CancellationException("Content identity changed")
    }

    override suspend fun next(videoId: String, cursor: String): RelatedPage = delegate.next(videoId, cursor)
}
