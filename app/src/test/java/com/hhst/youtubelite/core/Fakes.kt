package com.hhst.youtubelite.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test doubles for the WebView threading seams. Lives in the test source set. */

/** Executing PoToken WebView work off [allowed] fails immediately; no hop. */
class CheckingWebViewMainGate(
    private val allowed: Thread,
) : WebViewMainGate {
    override val isOnMain: Boolean
        get() = Thread.currentThread() === allowed

    override fun <T> run(block: () -> T): T {
        check(isOnMain) {
            "PoToken mint invoked off the allowed dispatcher (${Thread.currentThread().name})"
        }
        return block()
    }

    override fun post(block: () -> Unit) {
        run(block)
    }
}

/** JVM stand-in for the main looper: hops onto [executor] and awaits. */
class HoppingWebViewMainGate(
    private val executor: Executor,
    private val timeoutMs: Long = 5_000L,
) : WebViewMainGate {
    private val allowed = AtomicReference<Thread>()

    override val isOnMain: Boolean
        get() = Thread.currentThread() === allowed.get()

    override fun <T> run(block: () -> T): T {
        if (isOnMain) return block()
        val latch = CountDownLatch(1)
        val box = AtomicReference<Result<T>>()
        executor.execute {
            allowed.compareAndSet(null, Thread.currentThread())
            box.set(runCatching(block))
            latch.countDown()
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "PoToken WebView timed out waiting for the allowed dispatcher"
        }
        return checkNotNull(box.get()).getOrThrow()
    }

    override fun post(block: () -> Unit) {
        if (isOnMain) {
            block()
            return
        }
        executor.execute {
            allowed.compareAndSet(null, Thread.currentThread())
            block()
        }
    }
}

class RecordingWebViewTimerClock : WebViewTimerClock {
    val events = mutableListOf<String>()

    override fun pauseTimers() {
        events += "pause"
    }

    override fun resumeTimers() {
        events += "resume"
    }
}
