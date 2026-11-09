package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.SubscriptionsBrowse
import kotlinx.coroutines.CompletableDeferred

/** One-response handoff between the independent feed and directory initial reads. */
internal class SharedSubscriptionsBrowse(private val now: () -> Long) {
    enum class Consumer { Feed, Directory }
    private data class Key(val account: String?, val token: String?, val behalf: String?, val generation: Long,
        val visitor: String?, val language: String, val region: String)
    private class Pending(val key: Key, val consumer: Consumer) {
        val result = CompletableDeferred<SubscriptionsBrowse?>()
        var completedAt: Long? = null
    }
    class Ticket internal constructor(val owner: Boolean, val context: RequestContext,
        val result: CompletableDeferred<SubscriptionsBrowse?>)
    private val lock = Any()
    private var pending: Pending? = null

    fun acquire(consumer: Consumer, context: RequestContext): Ticket {
        val key = Key(context.accountId, context.accessToken, context.onBehalfOfUser, context.generation,
            context.visitor, context.language, context.region)
        val owner: Boolean
        val request: Pending
        synchronized(lock) {
            val existing = pending?.takeIf {
                it.key == key && it.consumer != consumer && (it.completedAt?.let { end -> now() - end in 0L..1_000L } != false)
            }
            owner = existing == null
            request = existing ?: Pending(key, consumer)
            // Each response can serve each consumer only once; a refresh of either
            // consumer always reads again once its original pair has been served.
            pending = if (owner) request else null
        }
        return Ticket(owner, context, request.result)
    }

    fun complete(ticket: Ticket, value: SubscriptionsBrowse) {
        synchronized(lock) { pending?.takeIf { it.result === ticket.result }?.completedAt = now() }
        ticket.result.complete(value)
    }

    fun failed(ticket: Ticket) {
        synchronized(lock) { if (pending?.result === ticket.result) pending = null }
        ticket.result.complete(null)
    }
}
