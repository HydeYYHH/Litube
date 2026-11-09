package com.hhst.youtubelite.player.datasource

import okhttp3.Call
import okhttp3.Response
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounds DNS/connect/TLS/headers without a duration limit on the media body. */
internal object MediaOpenTimeout {
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "media-open-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun execute(call: Call, timeoutMs: Long, onSlowOpen: (() -> Unit)? = null): Response {
        // 0: opening, 1: response owned by caller, 2: opening timed out.
        val state = AtomicInteger()
        val alarm = timer.schedule({
            if (!call.isCanceled() && state.compareAndSet(0, 2)) call.cancel()
        }, timeoutMs, TimeUnit.MILLISECONDS)
        val slow = onSlowOpen?.let { notify -> timer.schedule({
            if (!call.isCanceled() && state.get() == 0) runCatching(notify)
        }, 400, TimeUnit.MILLISECONDS) }
        try {
            val response = try { call.execute() } catch (failure: java.io.IOException) {
                if (state.get() == 2) throw SocketTimeoutException("MEDIA_OPEN_TIMEOUT").apply { initCause(failure) }
                throw failure
            }
            if (!state.compareAndSet(0, 1)) {
                response.close()
                throw SocketTimeoutException("MEDIA_OPEN_TIMEOUT")
            }
            return response
        } finally { alarm.cancel(false); slow?.cancel(false) }
    }
}
