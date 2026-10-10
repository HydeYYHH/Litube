package com.hhst.youtubelite.net

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hhst.youtubelite.diagnostics.*

/** Collects WebView network timing from injected JS and live-logs slow hits. */
class NetTracer(
    private val gson: Gson = Gson(),
    private val slowMs: Long = DEFAULT_SLOW_MS,
    private val context: () -> DiagnosticContext? = { null },
) {
    private val script = PageScript(ASSET, TAG)

    val bridge: TraceBridge = TraceBridge()

    fun install(context: Context, webView: WebView) {
        webView.addJavascriptInterface(bridge, JS_NAME)
        script.install(context, webView)
    }

    /** Fallback when document-start is unavailable; safe to call repeatedly. */
    fun inject(context: Context, webView: WebView) {
        script.inject(context, webView)
    }

    private fun push(record: RequestRecord) {
        if (noise(record.url, record.src)) return
        val captured = context()
        if (record.generation != null && record.generation != captured?.documentGeneration) {
            AppLog.detail(AppLog.Category.BROWSER, "network_stale_document", context = captured?.copy(documentGeneration = record.generation))
            return
        }
        val fields = DiagnosticRedaction.resource(record.url) + mapOf("status" to record.status, "duration_ms" to record.ms,
            "method" to record.method.takeIf { it in setOf("GET", "POST", "HEAD", "OPTIONS") }, "source" to record.src.take(24),
            "timing_phase" to if (record.src == "fetch") "response_headers" else "total", "reason" to if (record.err != null) "network_failed" else null)
        if (record.status >= 400 || record.err != null) AppLog.event(AppLog.Category.BROWSER, "request_failed", fields, critical = true, context = captured)
        else if (record.src == "fetch" && record.ms >= slowMs) AppLog.event(AppLog.Category.BROWSER, "request_slow_headers", fields, context = captured, level = DiagnosticLevel.WARN)
        else AppLog.detail(AppLog.Category.BROWSER, "request_success", fields, captured)
    }

    private fun format(h: RequestRecord): String {
        val tag = if (h.ms >= slowMs) "SLOW" else "ok"
        val err = h.err?.let { " err=$it" } ?: ""
        return "$tag ${h.method} ${h.ms}ms #${h.status} ${h.src} ${h.url}$err"
    }

    private fun noise(url: String, src: String): Boolean {
        val u = url.lowercase()
        if (u.isEmpty()) return true
        if (URL_NOISE.any { u.contains(it) }) return true
        if (RESOURCE_NOISE.any { src.startsWith(it) }) return true
        if (src.startsWith("res:") && IMAGE_REGEX.containsMatchIn(u)) return true
        return false
    }

    inner class TraceBridge {
        @JavascriptInterface
        fun onRequestLogged(json: String?) {
            if (json.isNullOrBlank() || json.length > 2048) return
            val record = runCatching { gson.fromJson(json, RequestRecord::class.java) }.getOrNull() ?: return
            push(record)
        }
    }

    data class RequestRecord(
        @SerializedName("src") val src: String = "",
        @SerializedName("method") val method: String = "GET",
        @SerializedName("url") val url: String = "",
        @SerializedName("status") val status: Int = 0,
        @SerializedName("ms") val ms: Long = 0,
        @SerializedName("err") val err: String? = null,
        val generation: Long? = null,
    )

    companion object {
        const val TAG = "NetTracer"
        const val JS_NAME = "NetTrace"
        const val ASSET = "script/net-tracer.js"
        private const val DEFAULT_SLOW_MS = 3000L

        private val URL_NOISE = listOf(
            "doubleclick", "googleads", "/pagead/", "pcs/activeview",
            "pagead/interaction", "pagead/adview", "play.google.com/log",
            "/s/search/audio/", "generate_204", "/api/stats/",
            "youtubei/v1/log_event", "googlevideo.com", "c.youtube.com",
            "videoplayback", "accounts.google.com", "servicelogin",
            "/js/th/", "/s/_/ytmweb/_/js/",
        )
        private val RESOURCE_NOISE = listOf("res:beacon", "res:img", "res:iframe", "res:script")
        private val IMAGE_REGEX = Regex("""\.(jpg|jpeg|png|webp|gif|svg)(\?|$)""", RegexOption.IGNORE_CASE)
    }
}
