package com.hhst.youtubelite.downloader.webview

import android.webkit.WebView

class AndroidWebViewTimerClock(
    private val main: WebViewMainGate = AndroidWebViewMainGate(),
) : WebViewTimerClock {
    @Volatile
    private var view: WebView? = null

    override fun attach(host: Any) {
        view = host as? WebView
    }

    // Fire-and-forget: a blocking hop here deadlocked App-startup mints
    // against the gate's main-thread timeout. Ordering is the occupancy
    // lock's job.
    override fun pauseTimers() {
        val host = view ?: return
        main.post { host.pauseTimers() }
    }

    override fun resumeTimers() {
        val host = view ?: return
        main.post { host.resumeTimers() }
    }
}
