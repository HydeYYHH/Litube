package com.hhst.youtubelite.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.diagnostics.*

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
            // First-party watch origins only. This allowlist is narrower than browser/UrlPolicy:
            // account and media-API hosts stay loadable but
            // scriptless, and "*" would additionally inject into third-party
            // iframes (ads, consent frames) where the player hook does not belong.
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                """
                (() => {
                  const h = location.hostname.toLowerCase();
                  if (h === 'accounts.youtube.com' || h === 'consent.youtube.com' ||
                      /^\/signin(?:\/|$)|^\/accounts\/|^\/(?:check_connection|set_setting)$/i.test(location.pathname)) return;
                  ${reporter(asset.substringAfterLast('/').substringBefore('.'))}
                  try { $script } catch(e) { window.__litubeDiagnostic('script_failed', 'exception'); }
                })();
                """.trimIndent(),
                setOf("https://youtube.com", "https://*.youtube.com", "https://youtu.be"),
            )
            AppLog.detail(AppLog.Category.EXTENSION, "script_install", mapOf("asset" to asset, "method" to "document_start"), BrowserDiagnostics.context(webView))
        }
    }

    fun inject(context: Context, webView: WebView) {
        val script = load(context) ?: return
        val captured = BrowserDiagnostics.context(webView)
        main.post {
            if (!UrlPolicy.shouldInject(webView.url)) return@post
            if (captured != BrowserDiagnostics.context(webView)) {
                AppLog.detail(AppLog.Category.EXTENSION, "script_skipped", mapOf("asset" to asset, "reason" to "stale_document"), captured)
                return@post
            }
            val wrapped = "(function(){ ${reporter(asset.substringAfterLast('/').substringBefore('.'))} try{ $script ;return 'diagnostic_ok';}catch(e){return 'diagnostic_failed';}})();"
            webView.evaluateJavascript(wrapped) { raw ->
                if (captured != BrowserDiagnostics.context(webView)) {
                    AppLog.detail(AppLog.Category.EXTENSION, "script_skipped", mapOf("asset" to asset, "reason" to "stale_document"), captured)
                    return@evaluateJavascript
                }
                val ok = raw == "\"diagnostic_ok\""
                if (ok) AppLog.detail(AppLog.Category.EXTENSION, "script_injected", mapOf("asset" to asset, "method" to "evaluate"), captured)
                else AppLog.event(AppLog.Category.EXTENSION, "script_failed", mapOf("asset" to asset, "reason" to "script_exception"), critical = true, context = captured)
            }
        }
    }

    private fun reporter(script: String): String = """
        if (!window.__litubeDiagnostic) {
          const generation = window.Bridge && Bridge.currentDocumentGeneration ? Bridge.currentDocumentGeneration() : -1;
          window.__litubeDiagnostic = function(code, reason, scriptName) {
            try {
              if (window.Bridge && Bridge.diagnosticEvent) Bridge.diagnosticEvent(JSON.stringify({
                code: code, reason: reason, script: scriptName || '$script', generation: generation,
                video_id: new URL(location.href).searchParams.get('v')
              }));
            } catch (_) {}
          };
        }
    """.trimIndent()

    private fun load(context: Context): String? {
        body?.let { return it }
        return try {
            context.assets.open(asset)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .also { body = it }
        } catch (e: Exception) {
            AppLog.event(AppLog.Category.EXTENSION, "script_asset_failed", mapOf("asset" to asset), e)
            null
        }
    }
}
