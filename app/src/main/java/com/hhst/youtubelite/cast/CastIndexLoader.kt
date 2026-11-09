package com.hhst.youtubelite.cast

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Coordinates index reads without reserving a worker while awaiting its children. */
internal class CastIndexLoader<T>(
    private val workers: ListeningExecutorService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val initialTimeoutMs: Long = 15_000,
    private val upgradeTimeoutMs: Long = 15_000,
) {
    private var job: Job? = null

    @Synchronized fun cancel() { job?.cancel(); job = null }

    @Synchronized fun load(video: () -> T?, audio: () -> T?, ready: (T?, T?, Boolean) -> Unit) {
        job?.cancel()
        job = scope.launch {
            val videoRead = workers.submit(Callable(video))
            val audioRead = workers.submit(Callable(audio))
            val completed = Futures.whenAllComplete(videoRead, audioRead)
                .call(Callable { Unit }, MoreExecutors.directExecutor())
            try {
                val first = withTimeoutOrNull(initialTimeoutMs) { completed.awaitCompletion(); true } == true
                coroutineContext.ensureActive()
                ready(videoRead.available(), audioRead.available(), false)
                if (!first && withTimeoutOrNull(upgradeTimeoutMs) { completed.awaitCompletion(); true } == true) {
                    coroutineContext.ensureActive()
                    ready(videoRead.available(), audioRead.available(), true)
                }
            } finally {
                videoRead.cancel(true)
                audioRead.cancel(true)
                completed.cancel(false)
            }
        }
    }

    private fun ListenableFuture<T?>.available(): T? = if (isDone) runCatching { Futures.getDone(this) }.getOrNull() else null

    private suspend fun ListenableFuture<*>.awaitCompletion() = suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) {
                try { Futures.getDone(this); continuation.resume(Unit) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }
        }, MoreExecutors.directExecutor())
    }
}
