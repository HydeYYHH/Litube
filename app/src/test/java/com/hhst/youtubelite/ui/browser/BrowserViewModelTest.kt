package com.hhst.youtubelite.ui.browser

import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewModelTest {

    private fun vm(miniEnabled: Boolean = false, playerShowing: Boolean = false): BrowserViewModel {
        val store = object : PrefStore {
            private val values = mutableMapOf<String, Boolean>()
            private var version = 0L
            override fun contains(key: String): Boolean = key in values
            override fun getBool(key: String, default: Boolean): Boolean = values[key] ?: default
            override fun putBool(key: String, value: Boolean) {
                values[key] = value
            }
            override fun getLong(key: String, default: Long): Long = version
            override fun putLong(key: String, value: Long) {
                version = value
            }
        }
        val manager = ExtensionManager(store).apply {
            setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, miniEnabled)
        }
        return BrowserViewModel(manager).apply {
            isPlayerShowing = { playerShowing }
        }
    }

    @Test fun loginBackRestoresTheSourceWhenTheProviderClearsWebHistory() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        val source = "https://m.youtube.com/watch?v=abc"
        vm.openTab(source)
        val id = vm.uiState.value.activeId
        val callbacks = vm.callbacksFor(id)
        callbacks.onLoginStarted(source)
        callbacks.onPageStarted("https://accounts.google.com/ServiceLogin")
        assertFalse(callbacks.isActiveDocument())
        assertEquals(BackResult.Handled, vm.onBack(false))
        assertEquals(source, vm.uiState.value.url)
        assertEquals(id, vm.uiState.value.activeId)
        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertNull(vm.uiState.value.suspendedWatchId)
    }

    @Test fun loginFromWatchRetainsItsTabAndUsesWebHistoryBeforeMinimizing() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=abc")
        val id = vm.uiState.value.activeId
        vm.openTab("https://accounts.google.com/ServiceLogin")
        assertEquals(id, vm.uiState.value.activeId)
        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertNull(vm.uiState.value.suspendedWatchId)
        assertEquals(BackResult.GoWebBack, vm.onBack(true))
        vm.callbacksFor(id).onPageFinished("https://m.youtube.com/watch?v=abc")
        assertEquals(id, vm.uiState.value.activeId)
    }

    @Test fun shortsFailureReusesSourceAndBackRestoresIt() {
        val vm = vm()
        val source = "https://m.youtube.com/results?search_query=test"
        vm.openTab(source)
        val sourceId = vm.uiState.value.activeId
        vm.openTab("https://m.youtube.com/shorts/aaaaaaaaaaa")
        val failedId = vm.uiState.value.activeId
        assertTrue(sourceId != failedId)
        vm.callbacksFor(failedId).onShortsAutoplayBlocked(vm.uiState.value.url, "muted_after_ready")
        assertEquals(sourceId, vm.uiState.value.activeId)
        assertFalse(vm.uiState.value.tabs.any { it.id == failedId })
        assertTrue(vm.callbacksFor(sourceId).shouldInheritShorts())
        assertEquals(BackResult.Handled, vm.onBack(true))
        assertEquals(source, vm.uiState.value.url)
        vm.openTab("https://m.youtube.com/shorts/bbbbbbbbbbb")
        assertEquals(sourceId, vm.uiState.value.activeId)
    }

    @Test
    fun openTab_rejectsDisallowedUrl() {
        val vm = vm()
        vm.openTab("https://evil.example/phish")

        assertEquals(1, vm.uiState.value.tabs.size)
        assertEquals(Constants.PAGE_HOME, activeKind(vm))
    }

    @Test
    fun openTab_opensWatchForward() {
        val vm = vm()
        vm.openTab("https://m.youtube.com/watch?v=1")

        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertTrue(vm.uiState.value.forward)
        assertTrue(vm.uiState.value.canPop)
    }

    @Test
    fun openTab_reusesNavAndUpdatesUrl() {
        val vm = vm()
        vm.openTab("https://m.youtube.com/shorts/a")
        val id = vm.uiState.value.activeId

        vm.openTab("https://m.youtube.com/shorts/b")

        assertEquals(id, vm.uiState.value.activeId)
        assertEquals("https://m.youtube.com/shorts/b", vm.uiState.value.url)
    }

    @Test
    fun onBack_webBackBeforePopBeforeFinish() {
        val vm = vm()
        vm.openTab("https://m.youtube.com/watch?v=1")
        assertTrue(vm.uiState.value.canPop)

        // WebView still has history -> consume it first, don't drop the tab.
        assertEquals(BackResult.GoWebBack, vm.onBack(webCanGoBack = true))
        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertTrue(vm.uiState.value.canPop)

        // WebView history drained -> now drop the watch tab back to home.
        assertEquals(BackResult.Handled, vm.onBack(webCanGoBack = false))
        assertEquals(Constants.PAGE_HOME, activeKind(vm))
        assertFalse(vm.uiState.value.canPop)

        // Only home remains, no web history -> leave the app.
        assertEquals(BackResult.Finish, vm.onBack(webCanGoBack = false))
    }

    // -- mini-player suspension --

    @Test
    fun onBack_onWatch_withMini_suspendsInsteadOfPopping() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")
        var suspended = 0
        vm.onWatchSuspended = { suspended++ }

        assertEquals(BackResult.Handled, vm.onBack(webCanGoBack = false))

        assertEquals(Constants.PAGE_HOME, activeKind(vm))
        assertNotNull(vm.uiState.value.suspendedWatchId)
        assertEquals(1, suspended)
    }

    @Test
    fun openTab_awayFromWatch_withMini_suspendsWatch() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")

        vm.openTab("https://m.youtube.com/@channel")

        assertEquals("@", activeKind(vm))
        assertNotNull(vm.uiState.value.suspendedWatchId)
    }

    @Test
    fun openTab_awayFromWatch_withoutMini_keepsWatchOnStack() {
        val vm = vm(miniEnabled = false, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")

        vm.openTab("https://m.youtube.com/@channel")

        assertNotNull(vm.uiState.value.tabs.find { it.kind == Constants.PAGE_WATCH })
        assertNull(vm.uiState.value.suspendedWatchId)
    }

    @Test
    fun openTab_shorts_signalsWebPlayback() {
        val vm = vm()
        var opened = 0
        vm.onShortsOpened = { opened++ }

        vm.openTab("https://m.youtube.com/shorts/aaaaaaaaaaa")

        assertEquals(Constants.PAGE_SHORTS, activeKind(vm))
        assertEquals(1, opened)
        assertNull(vm.uiState.value.suspendedWatchId)
    }

    @Test
    fun openTab_awayFromShorts_doesNotSuspend() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/shorts/aaaaaaaaaaa")

        vm.openTab("https://m.youtube.com/")

        assertEquals(Constants.PAGE_HOME, activeKind(vm))
        assertNull(vm.uiState.value.suspendedWatchId)
    }

    @Test
    fun restoreWatchTab_revivesSuspendedTabAndSignalsReturn() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")
        vm.onBack(webCanGoBack = false)
        var returned = 0
        vm.onWatchOpened = { _ -> returned++ }

        vm.restoreWatchTab("https://m.youtube.com/watch?v=1")

        assertEquals(Constants.PAGE_WATCH, activeKind(vm))
        assertNull(vm.uiState.value.suspendedWatchId)
        assertEquals(1, returned)
    }

    @Test
    fun restoreWatchTab_sameVideoDifferentParams_doesNotReload() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")
        vm.onBack(webCanGoBack = false)
        val loads = mutableListOf<Pair<Long, String>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.loadRequests.toList(loads) }

        // Player URL carries extra params the page gained after playback began.
        vm.restoreWatchTab("https://m.youtube.com/watch?v=1&pp=ygU")

        assertTrue(loads.isEmpty())
        assertEquals("https://m.youtube.com/watch?v=1", vm.uiState.value.url)
        job.cancel()
    }

    @Test
    fun restoreWatchTab_differentVideo_navigatesTab() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=aaaaaaaaaaa")
        vm.onBack(webCanGoBack = false)
        val loads = mutableListOf<Pair<Long, String>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.loadRequests.toList(loads) }

        // Queue advanced while suspended: the page still shows the old video.
        vm.restoreWatchTab("https://m.youtube.com/watch?v=bbbbbbbbbbb")

        assertEquals(1, loads.size)
        assertEquals(
            vm.uiState.value.activeId to "https://m.youtube.com/watch?v=bbbbbbbbbbb",
            loads.single(),
        )
        job.cancel()
    }

    @Test
    fun openWatch_whileSuspended_emitsLoadForRevivedTab() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=aaaaaaaaaaa")
        vm.onBack(webCanGoBack = false)
        val loads = mutableListOf<Pair<Long, String>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { vm.loadRequests.toList(loads) }

        // Mini-player active: tapping a new video revives the suspended tab,
        // whose host already exists — the load must be explicitly requested.
        vm.openTab("https://m.youtube.com/watch?v=bbbbbbbbbbb")

        assertEquals(1, loads.size)
        assertEquals(
            vm.uiState.value.activeId to "https://m.youtube.com/watch?v=bbbbbbbbbbb",
            loads.single(),
        )
        job.cancel()
    }

    @Test
    fun dropSuspendedWatch_forgetsTab() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")
        vm.onBack(webCanGoBack = false)

        vm.dropSuspendedWatch()

        assertNull(vm.uiState.value.suspendedWatchId)
        assertEquals(Constants.PAGE_HOME, activeKind(vm))
    }

    // -- watch <-> shorts --

    @Test
    fun openTab_watchToShorts_keepsWatchOnStackWithoutSuspension() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")
        var opened = 0
        vm.onWatchOpened = { _ -> opened++ }

        vm.openTab("https://m.youtube.com/shorts/aaaaaaaaaaa")

        // Watch tab stays on the stack so Back returns to it; playback
        // already switched to the Shorts (not mini-suspended).
        assertEquals(Constants.PAGE_SHORTS, activeKind(vm))
        assertNotNull(vm.uiState.value.tabs.find { it.kind == Constants.PAGE_WATCH })
        assertNull(vm.uiState.value.suspendedWatchId)
        assertEquals(0, opened)
    }

    @Test
    fun onBack_poppingShorts_signalsShortsClosed() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/shorts/aaaaaaaaaaa")
        var closed = 0
        vm.onShortsClosed = { closed++ }

        assertEquals(BackResult.Handled, vm.onBack(webCanGoBack = false))

        assertEquals(1, closed)
        assertEquals(Constants.PAGE_HOME, activeKind(vm))
    }

    @Test
    fun openTab_awayFromShorts_signalsShortsClosed() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/shorts/aaaaaaaaaaa")
        var closed = 0
        vm.onShortsClosed = { closed++ }

        vm.openTab("https://m.youtube.com/feed/subscriptions")

        assertEquals(1, closed)
        assertEquals(Constants.PAGE_SUBSCRIPTIONS, activeKind(vm))
    }

    private fun activeKind(vm: BrowserViewModel): String? {
        val state = vm.uiState.value
        return state.tabs.find { it.id == state.activeId }?.kind
    }
}
