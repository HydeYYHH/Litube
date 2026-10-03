package com.hhst.youtubelite.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.Promise
import com.hhst.youtubelite.extractor.VideoId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/** Player operations callable from injected page scripts. */
interface PlayerHooks {
    // The three hooks below are invoked on the MAIN thread (Bridge posts them).
    fun playVideo(url: String, origin: PageOrigin = PageOrigin.HOST)
    fun hidePlayer(origin: PageOrigin = PageOrigin.HOST)
    /**
     * True when [url] matches the loaded video and the seek was applied.
     *
     * Threading exception: this hook is invoked synchronously on the WebView's
     * JavaBridge thread because the JS caller needs the return value to decide
     * whether to swallow the click. Implementations must be thread-safe and
     * hop to main for any player access (PlayerViewModel reads only StateFlows
     * here and posts the engine seek).
     */
    fun seekLoadedVideo(url: String, positionMs: Long): Boolean
    /**
     * In-page player box (CSS px ≈ dp) so the native overlay can match it.
     */
    fun setPlayerLayout(topDp: Int, heightDp: Int, origin: PageOrigin = PageOrigin.HOST)
}

/**
 * Identifies the WebView document that issued a player callback.
 * [HOST] is browser-owned (tab URL effects, tests) and is always accepted.
 */
data class PageOrigin(val tabId: Long, val documentGeneration: Long) {
    val isHost: Boolean get() = tabId < 0L

    companion object {
        val HOST = PageOrigin(-1L, Long.MAX_VALUE)
    }
}

/** WebView-level operations the player needs (evaluated in the watch page). */
interface WatchPage {
    /** Runs JS in the watch WebView; null when no watch host exists. */
    fun evaluate(js: String, onResult: (String?) -> Unit)
    /** True when the watch WebView can go back. */
    fun canGoBack(): Boolean
    /** WebView back inside the watch tab (queue "previous" fallback). */
    fun goBack()
}

/** WebView JS bridge (`Bridge` / `lite`). */
class Bridge(
    private val onOpenTab: (url: String) -> Unit,
    private val onOpenExtension: () -> Unit,
    private val onOpenDownloads: () -> Unit = {},
    private val onOpenWith: (String) -> Unit = {},
    private val onAbout: () -> Unit = {},
    private val extensionManager: ExtensionManager,
    private val extractor: Extractor? = null,
    private val playerHooks: PlayerHooks? = null,
    /** Page "Add to queue" payload; null = payload unusable (host toasts). */
    private val onAddToQueue: ((QueueItemJson?) -> Unit)? = null,
    /** Long-press media card → native menu (queue + share). */
    private val onShowMediaItemMenu: ((QueueItemJson) -> Unit)? = null,
    /** Page playlist-presence push (player-hook.js sync). */
    private val onPlaylistPresence: ((Boolean) -> Unit)? = null,
    /** Owning browser tab; stamped onto every player callback. */
    private val tabId: Long = 0L,
    private val gson: Gson = Gson(),
) {
    /** Minimal shape the page's add-to-queue payload must have. */
    data class QueueItemJson(
        val videoId: String? = null,
        val url: String? = null,
        val title: String? = null,
        val author: String? = null,
        val thumbnailUrl: String? = null,
    )
    private val main = Handler(Looper.getMainLooper())
    private val lastVideo = AtomicReference<String?>(null)
    private val pendingVideo = AtomicReference<String?>(null)
    private val documentGeneration = AtomicLong(0L)

    /** Full document load (onPageStarted): stale callbacks from the previous document drop. */
    fun onDocumentStarted() {
        documentGeneration.incrementAndGet()
    }

    fun pageOrigin(): PageOrigin = PageOrigin(tabId, documentGeneration.get())

    @JavascriptInterface
    fun openTab(url: String?) {
        if (url.isNullOrBlank()) return
        if (!UrlPolicy.isAllowedUrl(url)) return
        if (PageKind.of(url) == "unknown") return
        main.post { onOpenTab(url) }
    }

    @JavascriptInterface
    fun extension() {
        main.post(onOpenExtension)
    }

    @JavascriptInterface
    fun download() {
        main.post(onOpenDownloads)
    }

    @JavascriptInterface
    fun openWith(url: String?) {
        if (url.isNullOrBlank()) return
        main.post { onOpenWith(url) }
    }

    @JavascriptInterface
    fun about() {
        main.post(onAbout)
    }

    @JavascriptInterface
    fun getPreferences(): String = gson.toJson(extensionManager.allPreferences())

    // -- queue (media-item menu in player-hook.js) --

    @JavascriptInterface
    fun addToQueue(itemJson: String?) {
        if (itemJson.isNullOrBlank()) return
        val item = parseQueueItem(itemJson)
        main.post { onAddToQueue?.invoke(item) }
    }

    @JavascriptInterface
    fun reportQueueAddFailed() {
        main.post { onAddToQueue?.invoke(null) }
    }

    /**
     * Long-press on a media card: native menu (add to queue / share).
     * A long-press can also land on non-media chrome; an unusable payload is
     * a mis-aimed press, not a failed queue add, so it is ignored silently —
     * only the explicit [addToQueue] path reports "unavailable".
     */
    @JavascriptInterface
    fun showMediaItemMenu(itemJson: String?) {
        val item = parseQueueItem(itemJson) ?: return
        main.post { onShowMediaItemMenu?.invoke(item) }
    }

    /** Watch URL + a title (falls back to the video id) is enough to queue. */
    private fun parseQueueItem(itemJson: String?): QueueItemJson? {
        if (itemJson.isNullOrBlank()) return null
        val item = runCatching {
            gson.fromJson(itemJson, QueueItemJson::class.java)
        }.getOrNull() ?: return null
        // Same host gate as [play]: VideoId alone accepts any host
        // (evil.com/watch?v=...), and this interface is exposed to every
        // page the WebView may load.
        val id = mediaIdOf(item.url) ?: return null
        val title = item.title?.takeIf { it.isNotBlank() }
            ?: item.videoId?.takeIf { it.isNotBlank() }
            ?: id
        return item.copy(title = title)
    }

    // -- player hooks (player-hook.js) --

    /**
     * Two-gate media-URL check: host allowlist + parseable video id.
     * VideoId alone accepts any host (evil.com/watch?v=...), and this
     * interface is exposed to every page the WebView may load.
     */
    private fun mediaIdOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!UrlPolicy.isAllowedUrl(url)) return null
        return VideoId.parse(url)
    }

    @JavascriptInterface
    fun play(url: String?) {
        if (url == null || mediaIdOf(url) == null) return
        val origin = pageOrigin()
        main.post { playerHooks?.playVideo(url, origin) }
    }

    @JavascriptInterface
    fun hidePlayer() {
        val origin = pageOrigin()
        main.post { playerHooks?.hidePlayer(origin) }
    }

    @JavascriptInterface
    fun setPlayerLayout(topDp: Int, heightDp: Int) {
        val origin = pageOrigin()
        main.post { playerHooks?.setPlayerLayout(topDp, heightDp, origin) }
    }

    /**
     * Runs on the JavaBridge thread (the JS side needs the synchronous
     * return); see [PlayerHooks.seekLoadedVideo] for the threading contract.
     */
    @JavascriptInterface
    fun seekLoadedVideo(url: String?, positionMs: Long): Boolean {
        if (url.isNullOrBlank()) return false
        return playerHooks?.seekLoadedVideo(url, positionMs) == true
    }

    /** Page push (player-hook.js): the watch URL's playlist presence. */
    @JavascriptInterface
    fun setPageHasPlaylist(has: Boolean) {
        main.post { onPlaylistPresence?.invoke(has) }
    }

    /**
     * Video id switch from watch-id.js.
     * Starts Innertube extract to warm the cache for the player.
     */
    @JavascriptInterface
    fun onVideoChanged(id: String?) {
        val videoId = VideoId.parse(id) ?: return
        val prev = lastVideo.getAndSet(videoId)
        if (prev == videoId) return
        val ex = extractor ?: return
        // Stay off the WebView binder path for heavy startup work.
        Promise.DEFAULT_SCOPE.launch {
            try {
                ex.extract(videoId)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch fail $videoId", e)
            }
        }
    }

    /**
     * Reports the video id of a /player POST the page is about to send, so the
     * request interceptor can serve the shared response. Null clears the match.
     */
    @JavascriptInterface
    fun onPlayerRequest(id: String?) {
        pendingVideo.set(VideoId.parse(id))
    }

    /** Video id of the pending page /player request, if known. */
    val pendingVideoId: String?
        get() = pendingVideo.get()

    companion object {
        const val NAME = "Bridge"
        private const val TAG = "JsBridge"
    }
}
