package com.hhst.youtubelite.cast

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No waiting socket queue: a slow peer cannot create unbounded proxy threads or queued clients. */
internal class BoundedProxyRunner(maxConnections: Int = MAX_CONNECTIONS) : NanoHTTPD.AsyncRunner {
    private val lock = Any()
    private val clients = mutableSetOf<NanoHTTPD.ClientHandler>()
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { job -> Thread(job, "cast-proxy-${sequence.incrementAndGet()}").apply { isDaemon = true } })

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) = synchronized(lock) {
        clients.add(clientHandler)
        try { executor.execute(clientHandler) }
        catch (_: RejectedExecutionException) {
            clients.remove(clientHandler)
            clientHandler.close()
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        synchronized(lock) { clients.remove(clientHandler) }
    }

    override fun closeAll() {
        val current = synchronized(lock) { clients.toList() }
        current.forEach { it.close() }
        // Keep the runner reusable for NanoHTTPD restart; its zero-core pool expires idle threads.
    }

    companion object { const val MAX_CONNECTIONS = 8 }
}
