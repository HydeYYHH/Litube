package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchPage
import com.liskovsoft.youtubeapi.videoinfo.V2.ledgerScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * Coalesces equivalent core Watch reads across the page, resume and chapter
 * consumers. Concurrent callers with the same ledger identity, video, language
 * and region share one in-flight network response; completed responses are
 * reused only by non-fresh callers within a short window. Different identities
 * never share anything, and fresh account-progress reads never reuse a
 * completed response.
 *
 * Blocking by design: the consumers already run blocking core reads on bounded
 * workers, and an in-flight owner always holds its own worker before any
 * joiner blocks, so waiting joiners cannot starve the read they await.
 */
class SharedWatchReads(
    private val maxAgeMs: Long = 15_000L,
    private val maxEntries: Int = 8,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Key(val identity: String, val videoId: String)

    /** A settled response available to non-fresh readers. */
    private data class Cached(val result: ReadResult<WatchPage>, val completedAt: Long)

    private val lock = Any()
    private val cache = LinkedHashMap<Key, Cached>(maxEntries, 0.75f, true)
    private val inFlight = HashMap<Key, CompletableDeferred<ReadResult<WatchPage>>>()

    init {
        require(maxEntries > 0)
        require(maxAgeMs >= 0)
    }

    /**
     * Runs or joins one Watch read. The first caller executes [load] on its own
     * transport and permit; joiners await the same response. A joiner whose
     * owner failed becomes the new owner and reads again.
     */
    fun read(
        videoId: String,
        context: RequestContext,
        http: HttpClient,
        fresh: Boolean,
        load: (String, RequestContext, HttpClient) -> ReadResult<WatchPage>,
    ): ReadResult<WatchPage> {
        val key = Key(context.ledgerScope() + "\u0000" + context.language + "/" + context.region, videoId)
        while (true) {
            val deferred: CompletableDeferred<ReadResult<WatchPage>>
            val owner: Boolean
            synchronized(lock) {
                if (!fresh) {
                    cache[key]?.takeIf { clock() - it.completedAt < maxAgeMs }?.let { return it.result }
                }
                // Any identical in-flight read is this instant's data either way,
                // so a fresh caller joins it too; only completed results respect
                // the freshness rule above.
                val existing = inFlight[key]
                owner = existing == null
                deferred = existing ?: CompletableDeferred<ReadResult<WatchPage>>()
                    .also { inFlight[key] = it }
            }
            if (!owner) {
                try {
                    return runBlocking { deferred.await() }
                } catch (_: CancellationException) {
                    // The owner died before publishing: retry as a new owner.
                    continue
                }
            }
            val result = try {
                load(videoId, context, http)
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                    deferred.completeExceptionally(error)
                }
                throw error
            }
            if (!result.ok) {
                // Failed responses are not shareable state: every joiner either
                // retries as a new owner or surfaces the same failure.
                val error = result.error ?: com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.PARSE, "missing_response")
                synchronized(lock) {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                    deferred.completeExceptionally(MediaException(error))
                }
                throw MediaException(error)
            }
            synchronized(lock) {
                if (inFlight[key] === deferred) {
                    inFlight.remove(key)
                    cache[key] = Cached(result, clock())
                    while (cache.size > maxEntries) cache.remove(cache.keys.first())
                }
                deferred.complete(result)
            }
            return result
        }
    }

    /** Drops every shared entry; explicit refreshes call this. */
    fun clear() = synchronized(lock) {
        cache.clear()
        inFlight.clear()
    }
}
