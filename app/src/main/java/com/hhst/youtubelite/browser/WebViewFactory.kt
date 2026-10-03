package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.content.ActivityNotFoundException
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewFeature
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.downloader.webview.DownloadWebBridge
import com.hhst.youtubelite.extension.ExtensionInjector
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.PlayerCache
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.net.NetTracer
import com.hhst.youtubelite.net.PageScript
import com.hhst.youtubelite.ui.theme.YtRed
import java.io.ByteArrayInputStream

/** Builds a swipe-refresh + mobile YouTube WebView host. */
object WebViewFactory {

    private const val NAV_JS = "script/nav.js"
    private const val WATCH_ID_JS = "script/watch-id.js"
    private const val INNERTUBE_JS = "script/innertube.js"
    private const val PLAYER_HOOK_JS = "script/player-hook.js"
    private const val DISLIKES_JS = "script/display_dislikes.js"
    private const val HIDE_SHORTS_JS = "script/hide_shorts.js"
    private const val SHORTS_ADS_JS = "script/remove_shorts_ads.js"
    private const val CORE_JS = "script/core.js"
    /** Injected page scripts address the bridge as `lite`. */
    internal const val LITE_ALIAS = "lite"

    private val coreScript = PageScript(CORE_JS, "Core")
    private val navScript = PageScript(NAV_JS, "WebViewFactory")
    private val playerHookScript = PageScript(PLAYER_HOOK_JS, "PlayerHook")
    private val dislikesScript = PageScript(DISLIKES_JS, "Dislikes")
    private val hideShortsScript = PageScript(HIDE_SHORTS_JS, "HideShorts")
    private val shortsAdsScript = PageScript(SHORTS_ADS_JS, "ShortsAds")
    private val downloadScript = PageScript(DownloadWebBridge.ASSET, "Download")

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        callbacks: WebViewCallbacks,
        extensionManager: ExtensionManager,
        onOpenExtension: () -> Unit,
        onOpenDownloads: () -> Unit,
        onOpenWith: (String) -> Unit,
        onAbout: () -> Unit,
        onRefresh: (WebView) -> Unit,
        extractor: Extractor,
        playerCache: PlayerCache,
        playerHooks: PlayerHooks,
        onAddToQueue: ((Bridge.QueueItemJson?) -> Unit)? = null,
        onShowMediaItemMenu: ((Bridge.QueueItemJson) -> Unit)? = null,
        onPlaylistPresence: ((Boolean) -> Unit)? = null,
        tabId: Long = 0L,
    ): BrowserHost {
        val appContext = context.applicationContext
        val injector = ExtensionInjector(appContext)
        val bridge = Bridge(
            onOpenTab = callbacks::onOpenTab,
            onOpenExtension = onOpenExtension,
            onOpenDownloads = onOpenDownloads,
            onOpenWith = onOpenWith,
            onAbout = onAbout,
            extensionManager = extensionManager,
            extractor = extractor,
            playerHooks = playerHooks,
            onAddToQueue = onAddToQueue,
            onShowMediaItemMenu = onShowMediaItemMenu,
            onPlaylistPresence = onPlaylistPresence,
            tabId = tabId,
        )
        val netTracer = NetTracer()
        val watchId = PageScript(WATCH_ID_JS, "WatchId")
        val innertube = PageScript(INNERTUBE_JS, "Innertube")
        val swipeRefresh = SwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setColorSchemeColors(YtRed.toArgb())
            setProgressViewOffset(true, 24, 96)
        }
        val downloadBridge = DownloadWebBridge(
            appContext = appContext,
            tabId = tabId,
        )
        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                loadWithOverviewMode = true
                useWideViewPort = true
                loadsImagesAutomatically = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            addJavascriptInterface(bridge, Bridge.NAME)
            addJavascriptInterface(bridge, LITE_ALIAS)
            coreScript.install(appContext, this)
            navScript.install(appContext, this)
            netTracer.install(appContext, this)
            watchId.install(appContext, this)
            innertube.install(appContext, this)
            playerHookScript.install(appContext, this)
            dislikesScript.install(appContext, this)
            hideShortsScript.install(appContext, this)
            shortsAdsScript.install(appContext, this)
            downloadScript.install(appContext, this)
            downloadBridge.attach(this)
            webViewClient = BrowserWebViewClient(
                appContext = appContext,
                callbacks = callbacks,
                injector = injector,
                coreScript = coreScript,
                netTracer = netTracer,
                watchId = watchId,
                innertube = innertube,
                playerHook = playerHookScript,
                dislikes = dislikesScript,
                hideShorts = hideShortsScript,
                shortsAds = shortsAdsScript,
                downloadScript = downloadScript,
                downloadBridge = downloadBridge,
                bridge = bridge,
                playerCache = playerCache,
                swipeRefresh = swipeRefresh,
            )
            webChromeClient = BrowserChromeClient(callbacks)
        }

        swipeRefresh.addView(webView)
        swipeRefresh.setOnRefreshListener { onRefresh(webView) }

        return BrowserHost(swipeRefresh, webView, downloadBridge)
    }

    internal fun injectNavScript(context: Context, webView: WebView) {
        navScript.inject(context, webView)
    }
}

data class BrowserHost(
    val container: SwipeRefreshLayout,
    val webView: WebView,
    val downloadBridge: DownloadWebBridge? = null,
)

private class BrowserWebViewClient(
    private val appContext: Context,
    private val callbacks: WebViewCallbacks,
    private val injector: ExtensionInjector,
    private val coreScript: PageScript,
    private val netTracer: NetTracer,
    private val watchId: PageScript,
    private val innertube: PageScript,
    private val playerHook: PageScript,
    private val dislikes: PageScript,
    private val hideShorts: PageScript,
    private val shortsAds: PageScript,
    private val downloadScript: PageScript,
    private val downloadBridge: DownloadWebBridge,
    private val bridge: Bridge,
    private val playerCache: PlayerCache,
    private val swipeRefresh: SwipeRefreshLayout,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (isPlayerPost(request)) {
            // Prefer the id parsed from the POST body; fall back to the referer,
            // which Chromium stamps after pushState on SPA navigations.
            val videoId = bridge.pendingVideoId
                ?: videoIdFromReferer(request.requestHeaders)
            if (videoId != null) {
                respondPlayer(videoId)?.let { return it }
                if (playerCache.await(videoId, PLAYER_WAIT_MS)) {
                    respondPlayer(videoId)?.let { return it }
                }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    private fun videoIdFromReferer(headers: Map<String, String>?): String? {
        val referer = headers?.entries
            ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
        return VideoId.parse(referer)
    }

    private fun isPlayerPost(request: WebResourceRequest): Boolean =
        request.method.equals("POST", ignoreCase = true) &&
            request.url.toString().contains(PLAYER_PATH, ignoreCase = true)

    private fun respondPlayer(videoId: String): WebResourceResponse? {
        val bytes = playerCache.get(videoId) ?: return null
        return WebResourceResponse(
            "application/json",
            Charsets.UTF_8.name(),
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(bytes),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val url = uri.toString()
        if (uri.scheme.equals("intent", ignoreCase = true)) {
            return openExternal(view.context, uri)
        }
        if (!UrlPolicy.canLoad(url)) {
            return openExternal(view.context, uri)
        }
        val current = view.url
        if (!current.isNullOrBlank()) {
            val nextKind = PageKind.of(url)
            if (nextKind != "unknown" && nextKind != PageKind.of(current)) {
                callbacks.onOpenTab(url)
                return true
            }
        }
        return false
    }

    /** Inject every page script in dependency order. */
    private fun injectAll(view: WebView) {
        coreScript.inject(appContext, view)
        WebViewFactory.injectNavScript(appContext, view)
        netTracer.inject(appContext, view)
        watchId.inject(appContext, view)
        innertube.inject(appContext, view)
        playerHook.inject(appContext, view)
        dislikes.inject(appContext, view)
        hideShorts.inject(appContext, view)
        shortsAds.inject(appContext, view)
        downloadBridge.stamp(view)
        downloadScript.inject(appContext, view)
        injector.inject(view)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        updateRefreshEnabled(url)
        bridge.onDocumentStarted()
        downloadBridge.bumpPage()
        downloadBridge.stamp(view)
        if (!hasDocumentStartScript) injectAll(view)
        callbacks.onPageStarted(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (hasDocumentStartScript) {
            injector.inject(view)
            downloadBridge.stamp(view)
            downloadScript.inject(appContext, view)
        } else {
            injectAll(view)
        }
        callbacks.onPageFinished(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // SPA navigations often skip full reloads; re-run inject for settings.
        updateRefreshEnabled(url)
        injector.inject(view)
        downloadBridge.bumpPage()
        downloadBridge.stamp(view)
        downloadScript.inject(appContext, view)
        callbacks.onHistoryChanged(url)
        callbacks.onNavigationStateChanged(view.canGoBack())
    }

    private fun openExternal(context: Context, uri: Uri): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            externalLinkUnavailable(context)
            true
        } catch (_: SecurityException) {
            // An OEM-banned package.
            externalLinkUnavailable(context)
            true
        }
    }

    private fun externalLinkUnavailable(context: Context) {
        // Surface the dead tap; loading in-page would bypass the allowlist.
        Toast.makeText(
            context,
            R.string.application_not_found,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Pull-to-refresh only on pages without conflicting vertical gestures. */
    private fun updateRefreshEnabled(url: String) {
        swipeRefresh.isEnabled = PageKind.of(url) in REFRESH_KINDS
    }

    private companion object {
        const val PLAYER_PATH = "/youtubei/v1/player"

        /** How long an intercepted /player POST waits for the native
         *  prefetch before falling through to the network; blocking the
         *  request thread longer cost more latency than the duplicate
         *  fetch it saved. */
        const val PLAYER_WAIT_MS = 250L
        val hasDocumentStartScript =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val REFRESH_KINDS = setOf(
            Constants.PAGE_HOME,
            Constants.PAGE_SUBSCRIPTIONS,
            Constants.PAGE_LIBRARY,
            "@",
        )
    }
}

private class BrowserChromeClient(
    private val callbacks: WebViewCallbacks,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgressChanged(newProgress)
        super.onProgressChanged(view, newProgress)
    }

    override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
}
