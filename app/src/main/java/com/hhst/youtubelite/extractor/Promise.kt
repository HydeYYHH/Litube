package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine-backed async fill of a mutable [value].
 *
 * [work] runs on [scope]. Call [get] only for branches you need.
 *
 * Pass autostart `false` when the caller must finish registering this
 * promise (in-flight tables, [whenDone] listeners) before [work] can settle
 * and mutate those same structures.
 */
class Promise<T>(
    val value: T,
    private val scope: CoroutineScope = DEFAULT_SCOPE,
    autostart: Boolean = true,
    private val work: (Promise<T>) -> Unit,
) {
    private val startedAtNs = System.nanoTime()
    private val deferred = CompletableDeferred<T>()
    private val failure = AtomicReference<Throwable?>(null)
    private val doneListeners = CopyOnWriteArrayList<() -> Unit>()
    private val doneLock = Any()
    private val started = AtomicBoolean(false)

    @Volatile
    var done: Boolean = false
        private set

    /** Milliseconds from construction to settlement; `-1` until done. */
    @Volatile
    var elapsedMs: Long = -1L
        private set

    val error: Throwable?
        get() = failure.get()

    val success: Boolean
        get() = done && failure.get() == null

    init {
        if (autostart) start()
    }

    /** Launches [work] once. Safe to call more than once. */
    internal fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                work(this@Promise)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                val listeners: List<() -> Unit>
                synchronized(doneLock) {
                    done = true
                    listeners = doneListeners.toList()
                    doneListeners.clear()
                }
                // Awaiters observe settled flags before receiving the completion signal.
                failure.get()?.let(deferred::completeExceptionally) ?: deferred.complete(value)
                listeners.forEach { listener -> runCatching { listener() } }
            }
        }
    }

    /** Blocks until complete; rethrows if [work] failed. */
    fun get(): T = runBlocking {
        start()
        deferred.await()
    }

    /** Suspends until complete; rethrows if [work] failed. */
    suspend fun await(): T { start(); return deferred.await() }

    /** Like [get], with timeout. */
    fun get(timeout: Long, unit: TimeUnit): T = runBlocking {
        start()
        withTimeout(unit.toMillis(timeout).milliseconds) {
            deferred.await()
        }
    }

    /** Mutates [value] in place. */
    inline fun set(block: T.() -> Unit) {
        value.block()
    }

    /** Runs [listener] once after settlement (success or failure). */
    fun whenDone(listener: () -> Unit) {
        val runNow: Boolean
        synchronized(doneLock) {
            if (done) {
                runNow = true
            } else {
                doneListeners.add(listener)
                runNow = false
            }
        }
        if (runNow) listener()
    }

    companion object {
        internal val DEFAULT_SCOPE: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
