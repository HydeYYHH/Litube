package com.hhst.youtubelite.extension

import android.content.Context
import android.util.Log
import android.webkit.WebView

/** Evaluates `script/extension.js` and logs [InjectReport]. */
class ExtensionInjector(private val context: Context) {

    @Volatile
    private var script: String? = null

    fun inject(webView: WebView, onReport: ((InjectReport) -> Unit)? = null) {
        val body = loadScript()
        if (body == null) {
            val report = InjectReport(
                ok = false,
                reason = "asset_missing",
                failures = listOf(
                    InjectFailure(element = ASSET, reason = "failed to load asset"),
                ),
            )
            Log.e(TAG, report.summary())
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
            if (!report.ok || report.hasFailures) {
                Log.w(TAG, report.summary())
            } else {
                Log.d(TAG, report.summary())
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
            Log.e(TAG, "Failed to load $ASSET", e)
            null
        }
    }

    companion object {
        private const val TAG = "ExtensionInjector"
        const val ASSET = "script/extension.js"
        const val BUTTON_ID = "extensionButton"
        const val ICON_VIEW_BOX = "0 -960 960 960"
    }
}
