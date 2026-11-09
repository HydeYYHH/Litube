package com.hhst.youtubelite.downloader.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import kotlin.coroutines.coroutineContext

/** Admission lasts through response.close(), including body reads outside OkHttp's callback. */
internal class DownloadConnectionLimiter(total: Int, perHost: Int) {
    private class Waiter(val call: Call) {
        val ready = CompletableDeferred<Unit>()
        var admitted = false
    }

    private val lock = Any()
    private var totalLimit = total.coerceAtLeast(1)
    private var hostLimit = perHost.coerceAtLeast(1)
    private var active = 0
    private val hosts = mutableMapOf<String, Int>()
    private val waiting = mutableListOf<Waiter>()

    fun update(total: Int, perHost: Int) = synchronized(lock) {
        totalLimit = total.coerceAtLeast(1)
        hostLimit = perHost.coerceAtLeast(1)
        admit()
    }

    fun cancelWaiting(call: Call) = synchronized(lock) {
        waiting.firstOrNull { it.call === call }?.let {
            waiting.remove(it)
            it.ready.cancel()
        }
    }

    suspend fun <T> withConnection(call: Call, block: suspend () -> T): T {
        val waiter = Waiter(call)
        synchronized(lock) {
            if (call.isCanceled()) waiter.ready.cancel()
            else { waiting.add(waiter); admit() }
        }
        try {
            waiter.ready.await()
            coroutineContext.ensureActive()
            return block()
        } finally {
            synchronized(lock) {
                waiting.remove(waiter)
                if (waiter.admitted) {
                    active--
                    val host = call.request().url.host
                    val remaining = hosts.getValue(host) - 1
                    if (remaining == 0) hosts.remove(host) else hosts[host] = remaining
                }
                admit()
            }
        }
    }

    // Called under lock; skip saturated hosts so one host cannot block another.
    private fun admit() {
        while (active < totalLimit) {
            val next = waiting.firstOrNull { (hosts[it.call.request().url.host] ?: 0) < hostLimit } ?: break
            waiting.remove(next)
            val host = next.call.request().url.host
            active++
            hosts[host] = (hosts[host] ?: 0) + 1
            next.admitted = true
            next.ready.complete(Unit)
        }
    }
}
