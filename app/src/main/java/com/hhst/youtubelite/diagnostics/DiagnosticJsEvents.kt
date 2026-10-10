package com.hhst.youtubelite.diagnostics

import android.webkit.WebView
import com.google.gson.JsonParser
import java.util.WeakHashMap

object DiagnosticJsEvents {
    data class Report(val code: String, val failed: Boolean, val videoId: String?, val fields: Map<String, Any?>)
    private val codes = setOf("script_ready", "script_failed", "selector_missing", "bridge_failed", "spa_navigation", "shorts_state", "caption_failed", "queue_failed")
    private val reasons = setOf("exception", "missing_bridge", "selector_missing", "stale_document", "unsupported", "timeout")
    fun parse(json: String?, generation: Long): Report? = runCatching {
        if (json == null || json.length > 2048) return@runCatching null
        val value = JsonParser.parseString(json).asJsonObject
        val code = value.get("code")?.asString ?: return@runCatching null
        if (code !in codes || value.get("generation")?.asLong != generation) return@runCatching null
        val videoId = value.get("video_id")?.asString?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        val fields = linkedMapOf<String, Any?>()
        value.get("script")?.asString?.takeIf { Regex("[a-z_-]{1,40}").matches(it) }?.let { fields["script"] = it }
        value.get("reason")?.asString?.takeIf { it in reasons }?.let { fields["reason"] = it }
        value.get("count")?.asInt?.takeIf { it in 0..1000 }?.let { fields["count"] = it }
        Report(code, code.endsWith("failed") || code == "selector_missing", videoId, fields)
    }.getOrNull()
}

object BrowserDiagnostics {
    private val documents = WeakHashMap<WebView, () -> DiagnosticContext>()
    @Synchronized fun register(view: WebView, context: () -> DiagnosticContext) { documents[view] = context }
    @Synchronized fun context(view: WebView): DiagnosticContext? = documents[view]?.invoke()
}
