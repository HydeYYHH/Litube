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

    override fun pauseTimers() {
        val host = view ?: return
        main.run { host.pauseTimers() }
    }

    override fun resumeTimers() {
        val host = view ?: return
        main.run { host.resumeTimers() }
    }
}
