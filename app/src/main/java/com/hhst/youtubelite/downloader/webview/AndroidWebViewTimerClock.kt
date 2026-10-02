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

    // Fire-and-forget on purpose: pause/resume are process-global state flips
    // whose ordering is already decided by the occupancy lock, and a blocking
    // hop here used to throw the 8s main-thread timeout straight through the
    // mint's finally block (App-startup jams) — killing a mint that had just
    // succeeded.
    override fun pauseTimers() {
        val host = view ?: return
        main.post { host.pauseTimers() }
    }

    override fun resumeTimers() {
        val host = view ?: return
        main.post { host.resumeTimers() }
    }
}
