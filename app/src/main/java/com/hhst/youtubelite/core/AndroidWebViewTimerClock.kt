package com.hhst.youtubelite.core

import android.webkit.WebView
import java.util.concurrent.atomic.AtomicLong

class AndroidWebViewTimerClock(
    private val main: WebViewMainGate = AndroidWebViewMainGate(),
) : WebViewTimerClock {
    @Volatile
    private var view = java.lang.ref.WeakReference<WebView>(null)
    private val revision = AtomicLong()

    override fun attach(host: Any) {
        view = java.lang.ref.WeakReference(host as? WebView)
    }

    override fun pauseTimers() = update(paused = true)

    override fun resumeTimers() = update(paused = false)

    private fun update(paused: Boolean) {
        val requested = revision.incrementAndGet()
        main.post {
            if (requested != revision.get()) return@post
            val host = view.get() ?: return@post
            if (paused) host.pauseTimers() else host.resumeTimers()
        }
    }
}
