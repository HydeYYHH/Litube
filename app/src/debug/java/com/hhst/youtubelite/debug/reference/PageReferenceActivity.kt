package com.hhst.youtubelite.debug.reference

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.hhst.youtubelite.core.BrowserUserAgent

/** Shared-cookie, read-only page host for reference collection. */
class PageReferenceActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = BrowserUserAgent.from(settings.userAgentString)
            webViewClient = WebViewClient()
            val url = intent.dataString
            val uri = android.net.Uri.parse(url.orEmpty())
            require(uri.scheme == "https" && uri.host == "m.youtube.com" && uri.userInfo == null && uri.port in setOf(-1, 443))
            loadUrl(requireNotNull(url))
        }
        setContent {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
