package com.hhst.youtubelite.player.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** On the owning scope's thread; only the latest request can publish, loads stay serial. */
internal class LatestArtworkRequest<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (K) -> V?,
    private val publish: (K, V) -> Unit,
    private val discard: (V) -> Unit,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val gate = Mutex()
    private var key: K? = null
    private var job: Job? = null

    fun submit(next: K) {
        if (key == next && job?.isActive == true) return
        cancel()
        key = next
        job = scope.launch {
            gate.withLock {
                var loaded: V? = null
                try {
                    // Assign before the dispatch back: cancellation during that handoff must
                    // also dispose a newly created bitmap that never reached the owner.
                    withContext(loadDispatcher) { loaded = load(next) }
                    coroutineContext.ensureActive()
                    loaded?.let { publish(next, it) }
                    loaded = null
                } finally { loaded?.let(discard) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        key = null
    }
}
