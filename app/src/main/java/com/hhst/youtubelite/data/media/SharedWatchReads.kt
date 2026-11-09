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
 * Coalesces equivalent Watch reads: identical keys share one in-flight response;
 * completed responses serve only non-fresh callers within a short window; different
 * identities never share and fresh reads never reuse a completed response.
 *
 * Blocking by design: consumers already run blocking reads on bounded workers and an
 * owner always holds a worker before joiners block, so joiners cannot starve the
 * read they await.
 */
class SharedWatchReads(
    private val maxAgeMs: Long = 15_000L,
    private val maxEntries: Int = 8,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Key(val identity: String, val videoId: String, val language: String, val region: String,
        val oauth: Boolean, val visitor: String?, val visitorCookie: String?, val webIdentity: String?)

    private data class Cached(val result: ReadResult<WatchPage>, val completedAt: Long)

    private val lock = Any()
    private val cache = LinkedHashMap<Key, Cached>(maxEntries, 0.75f, true)
    private val inFlight = HashMap<Key, CompletableDeferred<ReadResult<WatchPage>>>()

    init {
        require(maxEntries > 0)
        require(maxAgeMs >= 0)
    }

    /**
     * Runs or joins one Watch read: the first caller loads on its own transport and
     * permit; a joiner whose owner failed becomes the new owner.
     */
    fun read(
        videoId: String,
        context: RequestContext,
        http: HttpClient,
        fresh: Boolean,
        load: (String, RequestContext, HttpClient) -> ReadResult<WatchPage>,
    ): ReadResult<WatchPage> {
        // OAuth rotates without changing the account's capabilities. WEB cookies and
        // visitor identity do change those capabilities, even within one generation.
        val web = context.webSession?.takeIf { context.accessToken == null && it.signedIn }
        val webIdentity = web?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.cookies.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } + ":" + it.accountIndex
        }
        val key = Key(context.ledgerScope(), videoId, context.language, context.region, context.accessToken != null,
            context.visitor, context.visitorCookie?.takeIf { !context.signedIn }, webIdentity)
        while (true) {
            val deferred: CompletableDeferred<ReadResult<WatchPage>>
            val owner: Boolean
            synchronized(lock) {
                if (!fresh) {
                    cache[key]?.takeIf { clock() - it.completedAt < maxAgeMs }?.let { return it.result }
                }
                // An identical in-flight read is this instant's data either way, so a
                // fresh caller joins it too; only completed results respect freshness.
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
                // Failures aren't shareable state: joiners either retry as new owners or surface the same failure.
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

    fun clear() = synchronized(lock) {
        cache.clear()
        inFlight.clear()
    }
}
