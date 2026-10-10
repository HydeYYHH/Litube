package com.hhst.youtubelite.extension

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.hhst.youtubelite.diagnostics.*

/** Evaluates `script/extension.js` and logs [InjectReport]. */
class ExtensionInjector(private val context: Context) {

    @Volatile
    private var script: String? = null

    fun inject(webView: WebView, onReport: ((InjectReport) -> Unit)? = null) {
        val captured = BrowserDiagnostics.context(webView)
        val body = loadScript()
        if (body == null) {
            val report = InjectReport(
                ok = false,
                reason = "asset_missing",
                failures = listOf(
                    InjectFailure(element = ASSET, reason = "failed to load asset"),
                ),
            )
            AppLog.event(AppLog.Category.EXTENSION, "injection_failed", mapOf("reason" to "asset_missing"), critical = true, context = captured)
            onReport?.invoke(report)
            return
        }
        val wrapped = """
            (function(){
              try {
                var report = ($body);
                return JSON.stringify(report || {ok:false, reason:'null_return'});
              } catch (e) {
                return JSON.stringify({
                  ok: false,
                  reason: 'script_throw',
                  failures: [{element: 'script', reason: String(e && e.message || e)}]
                });
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(wrapped) { raw ->
            val report = InjectReport.parse(raw)
            if (captured != BrowserDiagnostics.context(webView)) {
                AppLog.detail(AppLog.Category.EXTENSION, "injection_skipped", mapOf("reason" to "stale_document"), captured)
                onReport?.invoke(report)
                return@evaluateJavascript
            }
            if (!report.ok || report.hasFailures) {
                AppLog.event(AppLog.Category.EXTENSION, "injection_failed", mapOf("reason" to report.reason, "failure_count" to report.failures.size), critical = true, context = captured)
            } else {
                AppLog.detail(AppLog.Category.EXTENSION, "injection_success", context = captured)
            }
            onReport?.invoke(report)
        }
    }

    private fun loadScript(): String? {
        script?.let { return it }
        return try {
            context.assets.open(ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .also { script = it }
        } catch (e: Exception) {
            AppLog.event(AppLog.Category.EXTENSION, "injection_asset_failed", mapOf("asset" to ASSET), e)
            null
        }
    }

    companion object {
        private const val TAG = "ExtensionInjector"
        const val ASSET = "script/extension.js"
    }
}
