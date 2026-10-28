package com.hhst.youtubelite.extractor

/** Retains refused clients briefly; a network error never defers a client. */
class AdaptiveClientOrder(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Refused(val videoId: String, val identity: String, val client: StreamClient)
    private val refused = LinkedHashMap<Refused, Long>()

    /** A refused media response defers this client for this video only; it remains a last resort. */
    @Synchronized
    fun mediaForbidden(videoId: String, identity: String, client: StreamClient) {
        refused.entries.removeAll { it.value <= clock() }
        refused[Refused(videoId, identity, client)] = clock() + 30_000
        while (refused.size > 64) refused.remove(refused.keys.first())
    }

    @Synchronized
    fun refusedFor(videoId: String, identity: String): Set<StreamClient> {
        refused.entries.removeAll { it.value <= clock() }
        return refused.keys.filter { it.videoId == videoId && it.identity == identity }.map { it.client }.toSet()
    }
}
