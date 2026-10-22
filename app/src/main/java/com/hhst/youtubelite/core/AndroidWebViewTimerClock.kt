package com.hhst.youtubelite.core

import android.webkit.WebView

class AndroidWebViewTimerClock(
    private val main: WebViewMainGate = AndroidWebViewMainGate(),
) : WebViewTimerClock {
    @Volatile
    private var view = java.lang.ref.WeakReference<WebView>(null)

    override fun attach(host: Any) {
        view = java.lang.ref.WeakReference(host as? WebView)
    }

    // Fire-and-forget: a blocking hop here deadlocked App-startup mints
    // against the gate's main-thread timeout. Ordering is the occupancy
    // lock's job.
    override fun pauseTimers() {
        val host = view.get() ?: return
        main.post { host.pauseTimers() }
    }

    override fun resumeTimers() {
        val host = view.get() ?: return
        main.post { host.resumeTimers() }
    }
}
