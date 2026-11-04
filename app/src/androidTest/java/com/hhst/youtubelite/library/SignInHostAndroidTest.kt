package com.hhst.youtubelite.library

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Debug-only interactive sign-in host: opens the mobile site in the same isolated WebView data
 * directory the reference sampler uses, so the session persists for later read-only sampling.
 * Gated behind `-e signin 1`.
 */
class SignInHostAndroidTest {
    @Test fun openSignInAndWaitForTheSession() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("signin") == "1")
        WebView.setDataDirectorySuffix("library-reference")
        val timeoutMinutes = (args.getString("timeout") ?: "15").toLong()
        ActivityScenario.launch(ComponentActivity::class.java).use { host ->
            lateinit var web: WebView
            host.onActivity { activity ->
                web = WebView(activity).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.textZoom = 100
                    webViewClient = android.webkit.WebViewClient()
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                activity.setContentView(web)
                web.loadUrl("https://m.youtube.com/?hl=en&persist_app=1")
            }
            // Poll until the account session cookie exists; a human completes the login in the emulator window. No credentials pass through the instrumentation.
            val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000
            var signedIn = false
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(4_000)
                val state = runCatching {
                    org.json.JSONObject(
                        web.evaluateJavascriptReturnJson()
                    )
                }.getOrNull()
                if (state != null && state.optBoolean("signedIn")) {
                    signedIn = true
                    break
                }
            }
            val cookies = CookieManager.getInstance().getCookie("https://m.youtube.com") ?: ""
            val result = org.json.JSONObject()
                .put("signedIn", signedIn)
                .put("hasSessionCookie", cookies.contains("SID=") || cookies.contains("SAPISID="))
            DeviceSignInEvidence.writeJson("sign-in-result.json", result.toString(2))
        }
    }

    private fun WebView.evaluateJavascriptReturnJson(): String {
        var value = "{}"
        val latch = java.util.concurrent.CountDownLatch(1)
        val script = """(function(){return JSON.stringify({signedIn:
            !!document.querySelector('ytm-account-item-renderer, img#avatar-img, button[aria-label="Account"]')
            || document.body.innerText.includes('Sign out')});})()"""
        evaluateJavascript(script) { raw ->
            var text = raw.orEmpty().trim()
            if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
                text = text.substring(1, text.length - 1).replace("\\\"", "\"")
            }
            value = text
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return value
    }
}

private object DeviceSignInEvidence {
    fun writeJson(fileName: String, json: String) {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(ctx.filesDir, "device-evidence").apply { mkdirs() }
        java.io.File(directory, fileName).writeText(json)
    }
}
