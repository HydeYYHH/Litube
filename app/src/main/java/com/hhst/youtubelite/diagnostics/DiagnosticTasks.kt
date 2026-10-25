package com.hhst.youtubelite.diagnostics

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class DiagnosticTasks {
    private val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "diagnostic-snapshot").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun <T> call(timeoutMs: Long, task: () -> T): T? {
        if (timeoutMs <= 0 || Thread.currentThread().isInterrupted) return null
        val future = try { executor.submit<T> { task() } } catch (_: java.util.concurrent.RejectedExecutionException) { return null }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            future.cancel(true)
            executor.purge()
        }
    }
}
