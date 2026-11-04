package com.hhst.youtubelite.core

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hhst.youtubelite.R
import org.koin.android.ext.android.inject

/** Isolated traffic verification without page injection. */
@Suppress("OVERRIDE_DEPRECATION")
class TrafficVerificationActivity : ComponentActivity() {
    private var web: WebView? = null
    private val timers: WebViewTimerOccupancy by inject()
    private var timerHandle: WebViewTimerHandle? = null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val challenge = intent.getStringExtra(EXTRA_CHALLENGE) ?: return finish()
        if (!UrlPolicy.isTrafficChallenge(challenge)) return finish()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = if (dark) 0xFF0F0F0F.toInt() else Color.WHITE
        val foreground = if (dark) 0xFFF1F1F1.toInt() else 0xFF0F0F0F.toInt()
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(background)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        val title = TextView(this).apply {
            setText(R.string.verification_title)
            setTextColor(foreground)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            setText(R.string.verification_close)
            setTextColor(foreground)
            textSize = 14f
            minimumHeight = (48 * density).toInt()
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setOnClickListener { finish() }
        }
        bar.addView(title)
        bar.addView(close)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                private var failedUrl: String? = null
                private fun allowed(url: String) = UrlPolicy.isTrafficChallenge(url) || UrlPolicy.isAllowedUrl(url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return !allowed(request.url.toString())
                }

                @Suppress("OverridingDeprecatedMember")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = !allowed(url)

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    failedUrl = null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failedUrl = request.url.toString()
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) failedUrl = request.url.toString()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url != failedUrl && url == view.url && UrlPolicy.isVerificationReturn(url)) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_CHALLENGE, challenge))
                        finish()
                    }
                }
            }
        }
        this.web = web
        timers.attach(web)
        timerHandle = timers.acquire(WebViewTimerOwner.BROWSER)
        if (savedInstanceState?.getBundle(WEB_STATE)?.let(web::restoreState) == null) web.loadUrl(challenge)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        androidx.core.view.WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.let { view -> outState.putBundle(WEB_STATE, Bundle().also { view.saveState(it) }) }
    }

    override fun onDestroy() {
        timerHandle?.release()
        timerHandle = null
        web?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHALLENGE = "traffic_verification_challenge"
        private const val WEB_STATE = "verification_web_state"
    }
}
