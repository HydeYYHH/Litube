package com.hhst.youtubelite.shorts.data

import com.hhst.youtubelite.shorts.domain.ShortsPage
import kotlinx.coroutines.*

/** One identity-scoped, consumable initial page; no stream URLs or viewing side effects. */
class ShortsEntryPreparation(
    private val load: suspend () -> ShortsPage,
    private val identity: () -> Long,
    private val enabled: () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Pending(val generation: Long, val started: Long, val result: Deferred<ShortsPage>, var claimed: Boolean = false)
    private val lock = Any()
    private var pending: Pending? = null

    private fun usable(work: Pending): Boolean = work.generation == identity() &&
        now() - work.started < 60_000 && !work.result.isCancelled

    private fun existing(): Pending? = pending?.takeIf(::usable).also { found ->
        if (found == null) { pending?.result?.cancel(); pending = null }
    }

    fun cancelUnclaimed() = synchronized(lock) {
        if (pending?.claimed != true) { pending?.result?.cancel(); pending = null }
    }

    /** The foreground may join this work even when navigation cancels the browser's await. */
    suspend fun prepare(): ShortsPage? {
        val work = synchronized(lock) {
            if (!enabled()) { pending?.result?.cancel(); pending = null; return null }
            existing() ?: Pending(identity(), now(), scope.async(start = CoroutineStart.LAZY) { load() })
                .also { pending = it; it.result.start() }
        }
        val page = work.result.await()
        return page.takeIf { work.generation == identity() && enabled() }
    }

    /** Consumes only prepared work; a direct cold entry keeps its normal cancellable read. */
    suspend fun read(): ShortsPage {
        val work = synchronized(lock) {
            if (!enabled()) { pending?.result?.cancel(); pending = null; null }
            else existing()?.also { it.claimed = true }
        } ?: return load()
        try {
            val page = work.result.await()
            if (work.generation != identity() || !enabled()) throw CancellationException("Shorts identity changed")
            return page
        } finally {
            synchronized(lock) { if (pending === work) pending = null }
        }
    }
}
