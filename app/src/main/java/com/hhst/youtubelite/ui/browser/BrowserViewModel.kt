package com.hhst.youtubelite.ui.browser

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.Tab
import com.hhst.youtubelite.browser.TabController
import com.hhst.youtubelite.browser.TabState
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.browser.WebViewCallbacks
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.diagnostics.AppLog
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
    private val loginSources = mutableMapOf<Long, Tab>()

    /** Player surface is showing; wired by the screen. */
    var isPlayerShowing: () -> Boolean = { false }

    /** Watch tab got suspended; the player should enter the mini-player. */
    var onWatchSuspended: (() -> Unit)? = null

    /** A watch tab became active; show its native surface. */
    var onWatchOpened: ((url: String?) -> Unit)? = null

    /** Shorts tab left; release the webpage playback ownership. */
    var onShortsClosed: (() -> Unit)? = null
    var onShortsOpened: (() -> Unit)? = null
    var onTabSwitched: (() -> Unit)? = null
    private var shortsSource: Tab? = null
    @Volatile private var inheritShorts = false
    private var inheritedShortsId: Long? = null

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
        if (UrlPolicy.isLoginUrl(url)) {
            val id = _uiState.value.activeId
            rememberLoginSource(id, _uiState.value.url)
            tabs.updateUrl(id, url)
            publish(tabs.state(), resetLoading = true)
            _loadRequests.tryEmit(id to url)
            return
        }
        val kind = PageKind.of(url)
        if (kind == "unknown") return

        if (kind == Constants.PAGE_SHORTS) {
            if (!PageKind.isShorts(_uiState.value.url)) {
                shortsSource = tabs.state().active
                onShortsOpened?.invoke()
            }
            if (inheritShorts) {
                val source = shortsSource ?: return
                inheritedShortsId = source.id
                val state = tabs.inheritShorts(source, url)
                publish(state, resetLoading = true)
                _loadRequests.tryEmit(source.id to url)
                return
            }
        } else if (PageKind.isShorts(_uiState.value.url)) {
            restoreInheritedSource()
            onShortsClosed?.invoke()
            shortsSource = null
        }

        val previousActive = _uiState.value.activeId
        // Hosts stay alive across suspension, so a revived suspended tab needs
        // its explicit load request emitted just like any other existing tab.
        val existingIds = _uiState.value.tabs.mapTo(mutableSetOf()) { it.id }
            .apply { _uiState.value.suspendedWatchId?.let { add(it) } }
        val suspendWatch = shouldSuspendWatch(targetKind = kind)
        val (state, load) = tabs.openTab(url, suspendWatch)
        // Enter mini before publishing: the screen's URL effect must observe
        // the final player state, not the intermediate non-watch one.
        if (suspendWatch) onWatchSuspended?.invoke()
        if (PageKind.isPlayerSurface(kind)) onWatchOpened?.invoke(url)
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
        if (UrlPolicy.isLoginUrl(_uiState.value.url)) {
            if (webCanGoBack) return BackResult.GoWebBack
            val source = loginSources.remove(_uiState.value.activeId)
            if (source != null) {
                tabs.updateUrl(source.id, source.url)
                publish(tabs.state(), resetLoading = true, forward = false)
                _loadRequests.tryEmit(source.id to source.url)
                if (PageKind.isPlayerSurface(source.kind)) onWatchOpened?.invoke(source.url)
                return BackResult.Handled
            }
        }
        if (shouldSuspendWatch()) {
            val state = tabs.suspendActiveWatch() ?: return BackResult.Finish
            onWatchSuspended?.invoke()
            publish(state, resetLoading = true, forward = false)
            return BackResult.Handled
        }
        if (PageKind.isShorts(_uiState.value.url)) {
            if (!restoreInheritedSource()) {
                val state = tabs.pop() ?: return BackResult.Finish
                publish(state, resetLoading = true, forward = false)
                if (PageKind.isPlayerSurface(PageKind.of(state.active?.url)))
                    onWatchOpened?.invoke(state.active?.url)
            }
            onShortsClosed?.invoke()
            shortsSource = null
            return BackResult.Handled
        }
        if (webCanGoBack) return BackResult.GoWebBack
        if (tabs.canPop()) {
            val state = tabs.pop() ?: return BackResult.Finish
            when {
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
        if (active.kind != Constants.PAGE_WATCH || UrlPolicy.isLoginUrl(_uiState.value.url)) return false
        if (targetKind == Constants.PAGE_SHORTS || PageKind.isPlayerSurface(targetKind)) return false
        if (!extensionManager.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)) return false
        return isPlayerShowing()
    }

    fun onRefreshStarted(tabId: Long) {
        if (_uiState.value.activeId != tabId) return
        _uiState.update {
            it.copy(isRefreshing = true, isLoading = true, progress = 0.06f)
        }
    }

    private fun restoreInheritedSource(): Boolean {
        val source = shortsSource ?: return false
        if (inheritedShortsId != source.id) return false
        inheritedShortsId = null
        publish(tabs.restoreShortsSource(source), resetLoading = true, forward = false)
        _loadRequests.tryEmit(source.id to source.url)
        if (PageKind.isPlayerSurface(PageKind.of(source.url))) onWatchOpened?.invoke(source.url)
        return true
    }

    private fun rememberLoginSource(tabId: Long, url: String) {
        if (!UrlPolicy.shouldInject(url)) return
        val tab = tabs.state().tabs.find { it.id == tabId } ?: return
        loginSources.putIfAbsent(tabId, tab.copy(url = url))
    }

    fun callbacksFor(tabId: Long): WebViewCallbacks = object : WebViewCallbacks {
        override fun onLoginStarted(sourceUrl: String) { rememberLoginSource(tabId, sourceUrl) }
        override fun onLoginFinished() { loginSources.remove(tabId) }
        override fun shouldInheritShorts(): Boolean = inheritShorts
        override fun isActiveTab(): Boolean = _uiState.value.activeId == tabId
        override fun isActiveDocument(): Boolean = (_uiState.value.activeId == tabId || _uiState.value.suspendedWatchId == tabId) &&
            !PageKind.isShorts(_uiState.value.url) && !UrlPolicy.isLoginUrl(_uiState.value.url)
        override fun onShortsAutoplayBlocked(url: String, reason: String) {
            if (inheritShorts || _uiState.value.activeId != tabId || !PageKind.isShorts(url)) return
            val source = shortsSource ?: return
            inheritShorts = true
            inheritedShortsId = source.id
            AppLog.event(
                AppLog.Category.PLAYER, "shorts_audio_fallback",
                mapOf("reason" to reason, "tab" to tabId), critical = true)
            publish(tabs.inheritShorts(source, url), resetLoading = true)
            _loadRequests.tryEmit(source.id to url)
        }
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
        loginSources.keys.retainAll(liveIds)
        val active = state.active
        if (state.activeId != _uiState.value.activeId) onTabSwitched?.invoke()
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
