package com.hhst.youtubelite.account

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.hhst.youtubelite.data.media.MediaSession

/** The credential page is owned by Google; content browsing stays in the native hosts. */
class SignInActivity : ComponentActivity() {
    var currentUrl: String? = null
        private set

    private var webView: WebView? = null
    private val timers by lazy { org.koin.java.KoinJavaComponent.getKoin().get<com.hhst.youtubelite.core.WebViewTimerOccupancy>() }
    private var timerHandle: com.hhst.youtubelite.core.WebViewTimerHandle? = null

    override fun onStart() {
        super.onStart()
        webView?.let(timers::attach)
        timerHandle = timers.acquire(com.hhst.youtubelite.core.WebViewTimerOwner.BROWSER)
    }

    override fun onStop() {
        timerHandle?.release()
        timerHandle = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUrl = SignInFlow.SIGN_IN_URL
        enableEdgeToEdge(SystemBarStyle.light(Color.WHITE, Color.WHITE), SystemBarStyle.light(Color.WHITE, Color.WHITE))
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = com.hhst.youtubelite.core.BrowserUserAgent.from(settings.userAgentString)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !SignInNavigation.allowed(request.url.toString())
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    val uri = android.net.Uri.parse(url)
                    if (uri.host in setOf("www.youtube.com", "m.youtube.com") && uri.path in setOf("/", "/feed/you") && MediaSession.signedIn()) {
                        CookieManager.getInstance().flush()
                        finish()
                    }
                }
            }
            if (savedInstanceState == null || restoreState(savedInstanceState) == null) loadUrl(SignInFlow.SIGN_IN_URL)
        }
        webView = view
        setContent {
            BackHandler { if (view.canGoBack()) view.goBack() else finish() }
            DisposableEffect(view) { onDispose { webView = null; view.stopLoading(); view.destroy() } }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }

}
