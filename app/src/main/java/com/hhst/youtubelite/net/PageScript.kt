package com.hhst.youtubelite.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Loads an asset script once and injects it into a WebView. */
internal class PageScript(
    private val asset: String,
    private val tag: String,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var body: String? = null

    fun install(context: Context, webView: WebView) {
        val script = load(context) ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // YouTube origins only — deliberately tighter than browser/UrlPolicy:
            // accounts.google.com and youtube.googleapis.com stay loadable but
            // scriptless, and "*" would additionally inject into third-party
            // iframes (ads, consent frames) where the player hook does not belong.
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                script,
                setOf("https://youtube.com", "https://*.youtube.com", "https://youtu.be"),
            )
        }
    }

    fun inject(context: Context, webView: WebView) {
        val script = load(context) ?: return
        main.post { webView.evaluateJavascript(script, null) }
    }

    private fun load(context: Context): String? {
        body?.let { return it }
        return try {
            context.assets.open(asset)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .also { body = it }
        } catch (e: Exception) {
            Log.e(tag, "load $asset failed", e)
            null
        }
    }
}
