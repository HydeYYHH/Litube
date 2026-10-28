package com.hhst.youtubelite.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ArtworkRequests<K, V>(
    private val scope: CoroutineScope,
    parallelism: Int = 2,
    capacity: Int = 32,
    private val load: suspend (K) -> V,
) {
    private class Request<V>(val result: Deferred<V>, var readers: Int = 1)
    private val lock = Any()
    private val requests = mutableMapOf<K, Request<V>>()
    private val admission = Semaphore(capacity)
    private val workers = Semaphore(parallelism)

    suspend fun get(key: K): V {
        var request = synchronized(lock) { requests[key]?.also { it.readers++ } }
        if (request == null) {
            admission.acquire()
            request = synchronized(lock) {
                val existing = requests[key]
                if (existing != null) {
                    admission.release()
                    existing.also { it.readers++ }
                } else {
                    val created = Request(scope.async(start = CoroutineStart.LAZY) {
                        workers.withPermit { load(key) }
                    })
                    requests[key] = created
                    created.result.invokeOnCompletion {
                        synchronized(lock) { if (requests[key] === created) requests.remove(key) }
                        admission.release()
                    }
                    created
                }
            }
        }
        val acquired = requireNotNull(request)
        try {
            return acquired.result.await()
        } finally {
            val abandoned = synchronized(lock) {
                acquired.readers--
                if (acquired.readers == 0 && !acquired.result.isCompleted) {
                    if (requests[key] === acquired) requests.remove(key)
                    true
                } else false
            }
            if (abandoned) acquired.result.cancel()
        }
    }

    fun clear() {
        val pending = synchronized(lock) { requests.values.toList().also { requests.clear() } }
        pending.forEach { it.result.cancel() }
    }
}
