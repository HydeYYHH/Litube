package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TabControllerTest {

    @Test
    fun suspendedWatch_tracksPlaylistNavigationWithoutReloadOnRestore() {
        val tabs = TabController()
        val watchId = tabs.openTab("https://m.youtube.com/watch?v=aaaaaaaaaaa").first.activeId
        tabs.suspendActiveWatch()
        val nextUrl = "https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest"
        tabs.updateUrl(watchId, nextUrl)

        val restored = tabs.restoreSuspendedWatch()!!
        assertEquals(watchId, restored.activeId)
        assertEquals(nextUrl, restored.active?.url)
        assertNull(tabs.openTab(nextUrl).second)
    }

    @Test
    fun openNav_keepsHomeAndTargetOnly() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/shorts")
        val state = tabs.openTab("https://m.youtube.com/feed/subscriptions").first

        assertEquals(
            setOf(Constants.PAGE_HOME, Constants.PAGE_SUBSCRIPTIONS),
            state.tabs.map { it.kind }.toSet(),
        )
        assertEquals(Constants.PAGE_SUBSCRIPTIONS, state.active?.kind)
    }

    @Test
    fun openWatch_reusesTab_popReturnsPrevious() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/@A")
        tabs.openTab("https://m.youtube.com/watch?v=a")
        tabs.openTab("https://m.youtube.com/@B")
        tabs.openTab("https://m.youtube.com/@C")
        val (state, load) = tabs.openTab("https://m.youtube.com/watch?v=b")

        assertEquals(1, state.tabs.count { it.kind == Constants.PAGE_WATCH })
        assertEquals(state.activeId to "https://m.youtube.com/watch?v=b", load)
        assertEquals("https://m.youtube.com/@C", tabs.pop()?.active?.url)
        assertEquals("https://m.youtube.com/@B", tabs.pop()?.active?.url)
    }

    @Test
    fun pop_onHomeOnly_isNoOp() {
        val tabs = TabController()
        assertFalse(tabs.canPop())
        assertNull(tabs.pop())
    }

    @Test
    fun suspendActiveWatch_removesTabAndRemembersIt() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        val state = tabs.suspendActiveWatch()

        assertNotNull(state)
        assertNull(state!!.tabs.find { it.kind == Constants.PAGE_WATCH })
        assertEquals(Constants.PAGE_HOME, state.active?.kind)
        assertNotNull(state.suspendedWatchId)
    }

    @Test
    fun suspendActiveWatch_onNonWatch_isNoOp() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/@A")
        assertNull(tabs.suspendActiveWatch())
    }

    @Test
    fun openWatch_revivesSuspendedTab() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        tabs.suspendActiveWatch()
        val (state, load) = tabs.openTab("https://m.youtube.com/watch?v=b")

        assertEquals(1, state.tabs.count { it.kind == Constants.PAGE_WATCH })
        assertNull(state.suspendedWatchId)
        assertEquals("https://m.youtube.com/watch?v=b", state.active?.url)
        assertNotNull(load)
    }

    @Test
    fun restoreSuspendedWatch_returnsTabToTopWithoutReload() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        val suspendedUrl = tabs.state().active!!.url
        tabs.suspendActiveWatch()
        val state = tabs.restoreSuspendedWatch()

        assertNotNull(state)
        assertEquals(suspendedUrl, state!!.active?.url)
        assertNull(state.suspendedWatchId)
        // Pure re-show: the tab keeps its id and url (no reload path).
        assertEquals(suspendedUrl, tabs.state().active?.url)
    }

    @Test
    fun dropSuspendedWatch_forgetsTab() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        tabs.suspendActiveWatch()
        val state = tabs.dropSuspendedWatch()

        assertNull(state.suspendedWatchId)
        // Reopening creates a fresh tab (home + new watch) instead of reviving
        // the dropped one.
        val reopened = tabs.openTab("https://m.youtube.com/watch?v=b").first
        assertEquals(
            listOf(Constants.PAGE_HOME, Constants.PAGE_WATCH),
            reopened.tabs.map { it.kind },
        )
    }

    @Test
    fun openTab_withSuspendFlag_suspendsWatchFirst() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        val (state, load) = tabs.openTab("https://m.youtube.com/@B", suspendWatch = true)

        assertNull(state.tabs.find { it.kind == Constants.PAGE_WATCH })
        assertNotNull(state.suspendedWatchId)
        assertEquals("https://m.youtube.com/@B", state.active?.url)
        assertNotNull(load)
    }

    @Test
    fun updateUrl_nonHome_rederivesKind() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/@A")
        val channel = tabs.state().active!!
        tabs.updateUrl(channel.id, "https://m.youtube.com/watch?v=a")

        assertEquals(Constants.PAGE_WATCH, tabs.state().active?.kind)
    }

    @Test
    fun updateUrl_home_keepsHomeIdentity() {
        val tabs = TabController()
        val home = tabs.state().tabs.first { it.kind == Constants.PAGE_HOME }
        // Unintercepted navigation (POST/redirect) landing on a watch URL must
        // not strip home of its routing identity.
        tabs.updateUrl(home.id, "https://m.youtube.com/watch?v=a")

        val updated = tabs.state().tabs.first { it.id == home.id }
        assertEquals(Constants.PAGE_HOME, updated.kind)
        assertEquals("https://m.youtube.com/watch?v=a", updated.url)
    }

    @Test
    fun updateUrl_doesNotDuplicateExistingKind() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        val watchId = tabs.state().active!!.id
        tabs.openTab("https://m.youtube.com/@A")
        val channel = tabs.state().active!!

        // A redirect landing on a watch URL must not turn the channel tab
        // into a second watch-kind tab: routing picks tabs by kind.
        tabs.updateUrl(channel.id, "https://m.youtube.com/watch?v=b")

        val updated = tabs.state().tabs.first { it.id == channel.id }
        assertEquals("@", updated.kind)
        assertEquals(
            Constants.PAGE_WATCH,
            tabs.state().tabs.first { it.id == watchId }.kind,
        )
    }

    @Test
    fun updateUrl_rederivesKindWhenKindIsFree() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/@A")
        val channel = tabs.state().active!!

        // No watch tab alive: the redirect re-derivation still applies.
        tabs.updateUrl(channel.id, "https://m.youtube.com/watch?v=a")

        assertEquals(Constants.PAGE_WATCH, tabs.state().active?.kind)
    }

    @Test
    fun updateUrl_suspendedWatchBlocksWatchRederivation() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=a")
        tabs.suspendActiveWatch() // mini-player now holds the watch tab
        tabs.openTab("https://m.youtube.com/@A")
        val channel = tabs.state().active!!

        // No LIVE watch tab exists, but the suspended one owns kind=watch:
        // re-deriving the channel tab would dangle the suspended tab (host
        // leak + a second watch tab on restore).
        tabs.updateUrl(channel.id, "https://m.youtube.com/watch?v=b")

        val updated = tabs.state().tabs.first { it.id == channel.id }
        assertEquals("@", updated.kind)
        // The suspended tab still revives cleanly into a single-watch state.
        val (state, _) = tabs.openTab("https://m.youtube.com/watch?v=c")
        assertEquals(1, state.tabs.count { it.kind == Constants.PAGE_WATCH })
        assertNull(state.suspendedWatchId)
    }
}
