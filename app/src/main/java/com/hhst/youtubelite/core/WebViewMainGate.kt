package com.hhst.youtubelite.core

/**
 * WebView create / [android.webkit.WebView.evaluateJavascript] / loadUrl /
 * pauseTimers must run on one thread (the main looper in production).
 * Download and extract coroutines sit on IO, post, and await.
 */
interface WebViewMainGate {
    val isOnMain: Boolean
    fun <T> run(block: () -> T): T
    fun post(block: () -> Unit)
}
