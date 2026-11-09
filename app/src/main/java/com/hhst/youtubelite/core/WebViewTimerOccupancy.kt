package com.hhst.youtubelite.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * [android.webkit.WebView.pauseTimers]/[resumeTimers] are process-global and shared
 * by every WebView; occupancy tracks who needs timers running. Count 0 pauses, > 0 resumes.
 */
interface WebViewTimerClock {
    /** Timer changes must not wait for another thread. */
    fun pauseTimers()
    fun resumeTimers()
    fun attach(host: Any) {}
}

enum class WebViewTimerOwner {
    BROWSER,
    POTOKEN,
    EJS,
}

class WebViewTimerHandle internal constructor(
    private val occupancy: WebViewTimerOccupancy,
    private val owner: WebViewTimerOwner,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) occupancy.release(owner)
    }
}

class WebViewTimerOccupancy(
    private val clock: WebViewTimerClock,
) {
    private val lock = Any()
    private val counts = mutableMapOf<WebViewTimerOwner, Int>()
    private var paused = false

    val isPaused: Boolean
        get() = synchronized(lock) { paused }

    val totalHolders: Int
        get() = synchronized(lock) { counts.values.sum() }

    fun attach(host: Any) = clock.attach(host)

    fun acquire(owner: WebViewTimerOwner): WebViewTimerHandle {
        synchronized(lock) {
            counts[owner] = (counts[owner] ?: 0) + 1
            if (paused) {
                paused = false
                clock.resumeTimers()
            }
        }
        return WebViewTimerHandle(this, owner)
    }

    inline fun <T> withOwner(owner: WebViewTimerOwner, block: () -> T): T {
        val handle = acquire(owner)
        try {
            return block()
        } finally {
            handle.release()
        }
    }

    internal fun release(owner: WebViewTimerOwner) {
        synchronized(lock) {
            val next = (counts[owner] ?: 0) - 1
            if (next <= 0) counts.remove(owner) else counts[owner] = next
            if (counts.isEmpty() && !paused) {
                paused = true
                clock.pauseTimers()
            }
        }
    }

    companion object {
        val NOOP = WebViewTimerOccupancy(
            object : WebViewTimerClock {
                override fun pauseTimers() = Unit
                override fun resumeTimers() = Unit
            },
        )
    }
}
