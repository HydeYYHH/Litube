package com.hhst.youtubelite.downloader.webview

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.downloader.ui.DownloadUiMapper
import com.hhst.youtubelite.extractor.Promise
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicLong

/**
 * WebView ↔ download UI. Prefers WebMessageListener; the compatibility
 * path may only open the native confirm sheet.
 */
class DownloadWebBridge(
    private val appContext: Context,
    private val tabId: Long,
    listenerAvailable: Boolean? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val pageGeneration = AtomicLong(0L)
    private var useListener = listenerAvailable
        ?: WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    @Volatile
    private var reply: JavaScriptReplyProxy? = null

    @Volatile
    private var attached: WebView? = null

    private var statusJob: Job? = null

    fun pageGeneration(): Long = pageGeneration.get()

    fun bumpPage() {
        pageGeneration.incrementAndGet()
    }

    fun attach(webView: WebView) {
        attached = webView
        if (useListener) {
            val added = runCatching {
                WebViewCompat.addWebMessageListener(
                    webView,
                    OBJECT_NAME,
                    ALLOWED_ORIGINS,
                    listener,
                )
            }.isSuccess
            if (!added) {
                useListener = false
                webView.addJavascriptInterface(Fallback(), FALLBACK_NAME)
            }
        } else {
            webView.addJavascriptInterface(Fallback(), FALLBACK_NAME)
        }
    }

    fun detach(webView: WebView) {
        statusJob?.cancel()
        statusJob = null
        reply = null
        if (attached === webView) attached = null
        if (useListener && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME) }
        } else {
            runCatching { webView.removeJavascriptInterface(FALLBACK_NAME) }
        }
    }

    fun stamp(webView: WebView) {
        val js = "window.__downloadPage={tabId:$tabId,pageGeneration:${pageGeneration.get()}};"
        webView.evaluateJavascript(js, null)
    }

    fun collectLoadedSnapshot(webView: WebView, onResult: (BatchSnapshot?) -> Unit) {
        val collect = """
            (function(){
              try {
                var n = window.__download;
                if (!n || !n.collect) return null;
                var snap = n.collect();
                snap.type = 'openBatch';
                return JSON.stringify(snap);
              } catch (e) { return null; }
            })()
        """.trimIndent()
        webView.evaluateJavascript(collect) { raw ->
            val json = DownloadWebGuard.unwrapJsString(raw) ?: return@evaluateJavascript onResult(null)
            onResult(DownloadWebGuard.snapshotFromCollect(json))
        }
    }

    private val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
        reply = replyProxy
        handle(
            raw = message.data ?: return@WebMessageListener,
            origin = sourceOrigin.toString(),
            isMainFrame = isMainFrame,
            allowBatch = true,
            replyProxy = replyProxy,
        )
    }

    private inner class Fallback {
        @JavascriptInterface
        fun post(json: String?) {
            if (json.isNullOrBlank()) return
            main.post {
                handle(
                    raw = json,
                    origin = attached?.url,
                    isMainFrame = true,
                    allowBatch = false,
                    replyProxy = null,
                )
            }
        }
    }

    private fun handle(
        raw: String,
        origin: String?,
        isMainFrame: Boolean,
        allowBatch: Boolean,
        replyProxy: JavaScriptReplyProxy?,
    ) {
        val decision = DownloadWebGuard.decide(
            origin = originHost(origin),
            isMainFrame = isMainFrame,
            currentTabId = tabId,
            currentPageGeneration = pageGeneration.get(),
            rawJson = raw,
            allowBatch = allowBatch,
        )
        when (decision) {
            is DownloadWebDecision.OpenSingle -> DownloadUi.showSingleConfirm(
                appContext,
                decision.videoId,
                decision.title,
                decision.author,
                decision.thumbnailUrl,
            )
            is DownloadWebDecision.OpenBatch -> DownloadUi.showBatchConfirm(appContext, decision.snapshot)
            is DownloadWebDecision.OpenManager -> DownloadUi.openManager(appContext)
            is DownloadWebDecision.Status -> watch(decision.videoId, replyProxy)
            is DownloadWebDecision.Error -> reply(DownloadWebStatus.error(decision.code, decision.message), replyProxy)
            DownloadWebDecision.Ignore -> Unit
        }
    }

    private fun watch(videoId: String, replyProxy: JavaScriptReplyProxy?) {
        val coordinator = coordinator() ?: return
        statusJob?.cancel()
        statusJob = Promise.DEFAULT_SCOPE.launch {
            runCatching {
                val tasks = coordinator.observeVideo(videoId).first()
                val json = DownloadWebStatus.json(DownloadUiMapper.video(videoId, tasks))
                main.post { reply(json, replyProxy) }
            }.onFailure { Log.w(TAG, "status failed", it) }
            coordinator.observeVideo(videoId).collect { tasks ->
                val json = DownloadWebStatus.json(DownloadUiMapper.video(videoId, tasks))
                main.post { reply(json, replyProxy ?: this@DownloadWebBridge.reply) }
            }
        }
    }

    private fun reply(json: String, replyProxy: JavaScriptReplyProxy?) {
        if (DownloadWebStatus.containsPath(json)) {
            Log.w(TAG, "refusing status payload that contains a path")
            return
        }
        if (replyProxy != null && useListener) {
            runCatching { replyProxy.postMessage(json) }
            return
        }
        val webView = attached ?: return
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('downloadStatus',{detail:$json}));",
            null,
        )
    }

    private fun originHost(origin: String?): String? {
        if (origin.isNullOrBlank()) return null
        val uri = Uri.parse(origin)
        val host = uri.host ?: return origin
        val scheme = uri.scheme ?: "https"
        return "$scheme://$host"
    }

    private fun coordinator(): DownloadCoordinator? =
        GlobalContext.getOrNull()?.get()

    companion object {
        private const val TAG = "DownloadWebBridge"
        const val OBJECT_NAME = "Download"
        const val FALLBACK_NAME = "DownloadFallback"
        const val ASSET = "script/download.js"
        val ALLOWED_ORIGINS: Set<String> = setOf(
            "https://youtube.com",
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://music.youtube.com",
            "https://*.youtube.com",
            "https://youtu.be",
            "https://www.youtu.be",
        )
    }
}
