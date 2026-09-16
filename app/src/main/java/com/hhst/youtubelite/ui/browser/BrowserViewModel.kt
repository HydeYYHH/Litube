package com.hhst.youtubelite.ui.browser

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.Tab
import com.hhst.youtubelite.browser.TabController
import com.hhst.youtubelite.browser.TabState
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.browser.WebViewCallbacks
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Multi-tab browser state and routing. */
class BrowserViewModel(
    private val extensionManager: ExtensionManager,
) : ViewModel() {

    private val tabs = TabController()
    private val canGoBackByTab = mutableMapOf<Long, Boolean>()

    /** Player surface is showing; wired by the screen. */
    var isPlayerShowing: () -> Boolean = { false }

    /** Watch tab got suspended; the player should enter the mini-player. */
    var onWatchSuspended: (() -> Unit)? = null

    /** A watch/shorts tab became active; the player should leave the mini-player. */
    var onWatchOpened: ((url: String?) -> Unit)? = null

    /** Shorts tab left (Back / bottom-nav); the player re-docks as mini. */
    var onShortsClosed: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(BrowserUiState.from(tabs.state()))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _loadRequests = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 16)
    /** Loads for an already-created host: `(tabId, url)`. */
    val loadRequests: SharedFlow<Pair<Long, String>> = _loadRequests.asSharedFlow()

    /** Active-tab history back; assign from the visible WebView after switches. */
    var canGoBack: Boolean
        get() = _uiState.value.canGoBack
        set(value) {
            canGoBackByTab[_uiState.value.activeId] = value
            _uiState.update { it.copy(canGoBack = value) }
        }

    fun openTab(url: String) {
        if (!UrlPolicy.isAllowedUrl(url)) return
        val kind = PageKind.of(url)
        if (kind == "unknown") return

        val previousActive = _uiState.value.activeId
        val previousKind = _uiState.value.tabs.find { it.id == previousActive }?.kind
        // Hosts stay alive across suspension, so a revived suspended tab needs
        // its explicit load request emitted just like any other existing tab.
        val existingIds = _uiState.value.tabs.mapTo(mutableSetOf()) { it.id }
            .apply { _uiState.value.suspendedWatchId?.let { add(it) } }
        val suspendWatch = shouldSuspendWatch(targetKind = kind)
        val (state, load) = tabs.openTab(url, suspendWatch)
        // Enter mini before publishing: the screen's URL effect must observe
        // the final player state, not the intermediate non-watch one.
        if (suspendWatch) onWatchSuspended?.invoke()
        when {
            // watch -> shorts keeps the watch tab on the stack (Back returns
            // to it). Playback switches to the Shorts; leaving docks mini.
            previousKind == Constants.PAGE_WATCH && kind == Constants.PAGE_SHORTS -> Unit
            PageKind.isPlayerSurface(kind) -> onWatchOpened?.invoke(url)
            // Leaving shorts for a non-video page: dock the current media as mini
            // instead of letting the URL effect resume/expand playback.
            previousKind == Constants.PAGE_SHORTS -> onShortsClosed?.invoke()
        }
        val switched = state.activeId != previousActive
        publish(state, resetLoading = switched, forward = if (switched) true else null)
        // New tabs load on host create; existing tabs (incl. just-revived
        // suspended ones) need an explicit load.
        if (load != null && load.first in existingIds) {
            _loadRequests.tryEmit(load)
        }
    }

    /** System Back: WebView history -> pop tab -> leave app. */
    fun onBack(webCanGoBack: Boolean): BackResult {
        // Back on a watch page suspends it to the mini-player and returns to
        // the previous page instead of walking the WebView history or popping
        // the tab.
        if (shouldSuspendWatch()) {
            val state = tabs.suspendActiveWatch() ?: return BackResult.Finish
            onWatchSuspended?.invoke()
            publish(state, resetLoading = true, forward = false)
            return BackResult.Handled
        }
        if (webCanGoBack) return BackResult.GoWebBack
        if (tabs.canPop()) {
            val poppedKind = _uiState.value.tabs.find { it.id == _uiState.value.activeId }?.kind
            val state = tabs.pop() ?: return BackResult.Finish
            when {
                // Back out of shorts: dock current playback as mini; the tab
                // underneath takes over.
                poppedKind == Constants.PAGE_SHORTS -> onShortsClosed?.invoke()
                // pop() never routes through openTab, so re-show the player here.
                PageKind.isPlayerSurface(state.active?.kind) ->
                    onWatchOpened?.invoke(state.active?.url)
            }
            publish(state, resetLoading = true, forward = false)
            return BackResult.Handled
        }
        return BackResult.Finish
    }

    /**
     * Mini-player restore: revive the suspended watch tab. Same video: pure
     * re-show, never reload (a string-different URL with the same id — extra
     * params like `&pp=` — must not refresh the page). Different video (queue
     * advanced while suspended): navigate the revived tab to it.
     */
    fun restoreWatchTab(playerUrl: String) {
        val state = tabs.restoreSuspendedWatch()
        if (state == null) {
            openTab(playerUrl)
            return
        }
        onWatchOpened?.invoke(playerUrl)
        publish(state, resetLoading = true, forward = true)
        val tabUrl = state.active?.url
        if (tabUrl != null && VideoId.parse(tabUrl) != VideoId.parse(playerUrl)) {
            _loadRequests.tryEmit(state.activeId to playerUrl)
        }
    }

    /** Mini-player close: drop the suspended watch tab so Back skips it. */
    fun dropSuspendedWatch() {
        if (uiState.value.suspendedWatchId == null) return
        publish(tabs.dropSuspendedWatch(), resetLoading = false)
    }

    /** True when leaving the active watch tab for [targetKind] should suspend it. */
    private fun shouldSuspendWatch(targetKind: String? = null): Boolean {
        val active = _uiState.value.tabs.find { it.id == _uiState.value.activeId } ?: return false
        if (active.kind != Constants.PAGE_WATCH) return false
        // Player surfaces (watch/shorts) never suspend the watch tab: shorts
        // keeps the tab on the stack so Back returns to it.
        if (targetKind != null && PageKind.isPlayerSurface(targetKind)) return false
        if (!extensionManager.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)) return false
        return isPlayerShowing()
    }

    fun onRefreshStarted(tabId: Long) {
        if (_uiState.value.activeId != tabId) return
        _uiState.update {
            it.copy(isRefreshing = true, isLoading = true, progress = 0.06f)
        }
    }

    fun callbacksFor(tabId: Long): WebViewCallbacks = object : WebViewCallbacks {
        override fun onPageStarted(url: String) {
            tabs.updateUrl(tabId, url)
            if (_uiState.value.activeId != tabId) return
            _uiState.update {
                it.copy(url = url, isLoading = true, progress = 0.06f)
            }
        }

        override fun onPageFinished(url: String) {
            tabs.updateUrl(tabId, url)
            if (_uiState.value.activeId != tabId) return
            _uiState.update {
                it.copy(
                    url = url,
                    isLoading = false,
                    progress = 1f,
                    isRefreshing = false,
                )
            }
        }

        override fun onProgressChanged(progress: Int) {
            if (_uiState.value.activeId != tabId) return
            val normalized = progress.coerceIn(0, 100) / 100f
            if (normalized >= 1f) {
                onPageFinished(_uiState.value.url)
                return
            }
            _uiState.update {
                it.copy(isLoading = true, progress = maxOf(it.progress, normalized))
            }
        }

        override fun onNavigationStateChanged(canGoBack: Boolean) {
            canGoBackByTab[tabId] = canGoBack
            if (_uiState.value.activeId != tabId) return
            _uiState.update { it.copy(canGoBack = canGoBack) }
        }

        override fun onHistoryChanged(url: String) {
            tabs.updateUrl(tabId, url)
            if (_uiState.value.activeId != tabId) return
            _uiState.update { it.copy(url = url) }
        }

        override fun onOpenTab(url: String) {
            openTab(url)
        }
    }

    private fun publish(
        state: TabState,
        resetLoading: Boolean,
        forward: Boolean? = null,
    ) {
        val liveIds = state.tabs.mapTo(mutableSetOf()) { it.id }
        // The suspended watch tab stays alive behind the mini-player; its
        // cached history flag must survive the retainAll below.
        state.suspendedWatchId?.let(liveIds::add)
        canGoBackByTab.keys.retainAll(liveIds)
        val active = state.active
        _uiState.update {
            it.copy(
                tabs = state.tabs,
                activeId = state.activeId,
                url = active?.url ?: it.url,
                isLoading = if (resetLoading) false else it.isLoading,
                progress = if (resetLoading) 0f else it.progress,
                isRefreshing = if (resetLoading) false else it.isRefreshing,
                canGoBack = canGoBackByTab[state.activeId] == true,
                canPop = state.tabs.size > 1,
                suspendedWatchId = state.suspendedWatchId,
                forward = forward ?: it.forward,
            )
        }
    }
}

/** Outcome of [BrowserViewModel.onBack] for the host to apply. */
enum class BackResult {
    /** Tab popped, or its watch playback was suspended instead; no further action needed. */
    Handled,
    /** WebView still has history; host should call [WebView.goBack]. */
    GoWebBack,
    /** Nothing left to go back to; host should leave the app. */
    Finish,
}

data class BrowserUiState(
    val tabs: List<Tab>,
    val activeId: Long,
    val url: String,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val isRefreshing: Boolean = false,
    val canGoBack: Boolean = false,
    val canPop: Boolean = false,
    /** Suspended watch tab behind the mini-player; keep its host alive. */
    val suspendedWatchId: Long? = null,
    /** Slide direction for the next tab transition. */
    val forward: Boolean = true,
) {
    companion object {
        fun from(state: TabState): BrowserUiState {
            val active = state.active
            return BrowserUiState(
                tabs = state.tabs,
                activeId = state.activeId,
                url = active?.url.orEmpty(),
                canPop = state.tabs.size > 1,
                suspendedWatchId = state.suspendedWatchId,
            )
        }
    }
}
