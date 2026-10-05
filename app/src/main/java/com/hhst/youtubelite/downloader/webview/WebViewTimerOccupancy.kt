package com.hhst.youtubelite.downloader.webview

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-global [android.webkit.WebView.pauseTimers] / [android.webkit.WebView.resumeTimers]
 * are shared by every WebView. Occupancy is who currently needs timers running
 * (browser foreground, PoToken mint). Count 0 pauses; count > 0 resumes.
 */
interface WebViewTimerClock {
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
    val owner: WebViewTimerOwner,
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
        val shouldResume: Boolean
        synchronized(lock) {
            counts[owner] = (counts[owner] ?: 0) + 1
            shouldResume = paused
            if (paused) paused = false
        }
        // Clock hops to the main looper; never wait for that while holding [lock]
        // or Browser ON_RESUME on main deadlocks against PoToken on IO.
        if (shouldResume) clock.resumeTimers()
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
        val shouldPause: Boolean
        synchronized(lock) {
            val next = (counts[owner] ?: 0) - 1
            if (next <= 0) counts.remove(owner) else counts[owner] = next
            shouldPause = counts.values.sum() == 0 && !paused
            if (shouldPause) paused = true
        }
        if (shouldPause) clock.pauseTimers()
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

class RecordingWebViewTimerClock : WebViewTimerClock {
    val events = mutableListOf<String>()

    override fun pauseTimers() {
        events += "pause"
    }

    override fun resumeTimers() {
        events += "resume"
    }
}
