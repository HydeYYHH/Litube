package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants

data class Tab(
    val id: Long,
    val kind: String,
    val url: String,
)

data class TabState(
    val tabs: List<Tab>,
    val activeId: Long,
    /** Suspended watch tab (mini-player); kept out of [tabs] but its host stays alive. */
    val suspendedWatchId: Long? = null,
) {
    val active: Tab? get() = tabs.find { it.id == activeId }
}

/**
 * Pure tab routing (no Android views).
 *
 * - [PageKind.NAV] kinds reuse one tab each; watch reuses one stack tab; others push.
 * - **Home is the root** (kept while size > 0); it is usually at the front of [tabs],
 *   not the end.
 * - **Active is always [tabs]`.last()`** (stack top). After [pop], active becomes the
 *   new last entry; when only one tab remains it is home and [canPop] is false.
 * - Leaving the active watch tab can **suspend** it: the tab
 *   leaves the stack so Back lands on the previous page, but it is remembered so
 *   the mini-player can restore it (and its WebView) without a reload.
 */
class TabController(
    homeUrl: String = Constants.HOME_URL,
) {
    private var nextId = 1L
    private val tabs = mutableListOf<Tab>()
    private var activeId: Long
    private var suspendedWatch: Tab? = null
    private var inheritedShortsId: Long? = null

    init {
        val home = Tab(id = nextId++, kind = Constants.PAGE_HOME, url = homeUrl)
        tabs += home
        activeId = home.id
    }

    fun state(): TabState =
        TabState(tabs = tabs.toList(), activeId = activeId, suspendedWatchId = suspendedWatch?.id)

    fun canPop(): Boolean = tabs.size > 1

    /**
     * Routes [url] into an existing or new tab.
     *
     * @param suspendWatch remove the active watch tab from the stack first
     *   (watch → other kind while the player shows).
     * @return state and optional `(tabId, url)` the host should load.
     */
    fun openTab(url: String, suspendWatch: Boolean = false): Pair<TabState, Pair<Long, String>?> {
        val kind = PageKind.of(url)
        val active = tabs.find { it.id == activeId }

        if (kind == Constants.PAGE_SHORTS) {
            val shorts = tabs.find { it.kind == Constants.PAGE_SHORTS }
            if (shorts == null) return create(kind, url)
            moveToEnd(shorts.id)
            activeId = shorts.id
            return loadOrShow(shorts, url)
        }

        if (suspendWatch && active?.kind == Constants.PAGE_WATCH && tabs.size > 1) {
            suspendActiveWatch()
        }

        if (active != null && active.kind == kind && kind in PageKind.NAV) {
            return loadOrShow(active, url)
        }

        if (kind == Constants.PAGE_WATCH) {
            val watch = tabs.find { it.kind == Constants.PAGE_WATCH } ?: reviveSuspended()
            if (watch != null) {
                moveToEnd(watch.id)
                activeId = watch.id
                return loadOrShow(watch, url)
            }
        }

        return if (kind in PageKind.NAV) openNav(url, kind) else openStack(url, kind)
    }

    /** Reuse a source document after a separate Shorts host fails audio startup. */
    fun inheritShorts(source: Tab, url: String): TabState {
        tabs.removeAll { it.kind == Constants.PAGE_SHORTS && it.id != source.id }
        inheritedShortsId = source.id
        replace(source.id, source.copy(url = url))
        moveToEnd(source.id)
        activeId = source.id
        return state()
    }

    fun restoreShortsSource(source: Tab): TabState {
        inheritedShortsId = null
        replace(source.id, source)
        activeId = source.id
        moveToEnd(source.id)
        return state()
    }

    fun updateUrl(tabId: Long, url: String) {
        suspendedWatch?.takeIf { it.id == tabId }?.let {
            suspendedWatch = it.copy(url = url)
            return
        }
        val tab = tabs.find { it.id == tabId } ?: return
        if (tab.url == url) return
        // Re-derive kind so unintercepted cross-kind navigation (POST submit,
        // redirect chain) cannot leave routing identity stuck on the old kind.
        // Home is the exception: its kind is its identity (homeTab()/openNav
        // keep it alive), so it stays home wherever its WebView wanders.
        val kind = if (tab.kind == Constants.PAGE_HOME || tab.id == inheritedShortsId || UrlPolicy.isLoginUrl(url)) tab.kind else PageKind.of(url)
        // A redirect can re-derive a kind another tab already owns; two
        // same-kind tabs break routing (openNav/openTab pick the first). Keep
        // this tab's identity sticky in that case — it sheds the stray URL on
        // the next routing pass. The suspended watch tab counts as owning
        // kind=watch: re-deriving a live watch tab while the mini-player
        // holds one would leave a dangling suspendedWatch — its host stays
        // alive forever and restoreSuspendedWatch would re-insert a second
        // watch tab.
        val duplicated = kind != tab.kind &&
            (tabs.any { it.id != tabId && it.kind == kind } ||
                (kind == Constants.PAGE_WATCH && suspendedWatch != null))
        replace(tabId, tab.copy(url = url, kind = if (duplicated) tab.kind else kind))
    }

    /**
     * Drops the active (stack-top) tab.
     *
     * @return null when only home is left ([canPop] is false).
     */
    fun pop(): TabState? {
        if (!canPop()) return null
        val dropIndex = tabs.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: tabs.lastIndex
        tabs.removeAt(dropIndex)
        activeId = tabs.last().id
        return state()
    }

    /**
     * Suspends the active watch tab: removed from the stack (Back lands on the
     * previous page) but remembered for mini-player restore.
     *
     * @return null when the active tab is not a watch tab or nothing is under it.
     */
    fun suspendActiveWatch(): TabState? {
        val active = tabs.find { it.id == activeId } ?: return null
        if (active.kind != Constants.PAGE_WATCH || tabs.size <= 1) return null
        suspendedWatch = active
        tabs.removeAt(tabs.indexOfFirst { it.id == active.id })
        activeId = tabs.last().id
        return state()
    }

    /**
     * Re-inserts the suspended watch tab on top as-is — no reload. The host
     * WebView survived the suspension, so the page picks up where it left off.
     */
    fun restoreSuspendedWatch(): TabState? {
        val watch = suspendedWatch ?: return null
        suspendedWatch = null
        tabs += watch
        activeId = watch.id
        return state()
    }

    /** Discards the suspended watch tab (mini-player close); its host may die. */
    fun dropSuspendedWatch(): TabState {
        suspendedWatch = null
        return state()
    }

    private fun reviveSuspended(): Tab? =
        suspendedWatch?.also {
            suspendedWatch = null
            tabs += it
        }

    private fun openNav(url: String, kind: String): Pair<TabState, Pair<Long, String>?> {
        val home = homeTab()
        val existing = if (kind == Constants.PAGE_HOME) home else tabs.find { it.kind == kind }
        val keepIds = buildSet {
            add(home.id)
            existing?.let { add(it.id) }

        }
        tabs.removeAll { it.id !in keepIds }

        if (existing != null) {
            moveToEnd(existing.id)
            activeId = existing.id
            return loadOrShow(existing, url)
        }
        return create(kind, url)
    }

    private fun openStack(url: String, kind: String): Pair<TabState, Pair<Long, String>?> {
        homeTab()
        return create(kind, url)
    }

    private fun loadOrShow(tab: Tab, url: String): Pair<TabState, Pair<Long, String>?> {
        if (tab.url == url) return state() to null
        replace(tab.id, tab.copy(url = url))
        return state() to (tab.id to url)
    }

    private fun create(kind: String, url: String): Pair<TabState, Pair<Long, String>?> {
        val created = Tab(id = nextId++, kind = kind, url = url)
        tabs += created
        activeId = created.id
        return state() to (created.id to url)
    }

    private fun homeTab(): Tab {
        tabs.find { it.kind == Constants.PAGE_HOME }?.let { return it }
        return Tab(id = nextId++, kind = Constants.PAGE_HOME, url = Constants.HOME_URL)
            .also { tabs.add(0, it) }
    }

    private fun moveToEnd(id: Long) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0 || i == tabs.lastIndex) return
        tabs.add(tabs.removeAt(i))
    }

    private fun replace(id: Long, tab: Tab) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) tabs[i] = tab
    }
}
