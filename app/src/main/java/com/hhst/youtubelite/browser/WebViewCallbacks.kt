package com.hhst.youtubelite.browser

/** WebView events to the UI layer. */
interface WebViewCallbacks {
    fun shouldInheritShorts(): Boolean = false
    fun isActiveTab(): Boolean = true
    fun isActiveDocument(): Boolean = true
    fun onShortsAutoplayBlocked(url: String, reason: String) {}
    fun onLoginStarted(sourceUrl: String) {}
    fun onLoginFinished() {}
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onProgressChanged(progress: Int)
    fun onNavigationStateChanged(canGoBack: Boolean)
    fun onHistoryChanged(url: String)
    fun onOpenTab(url: String)
}
