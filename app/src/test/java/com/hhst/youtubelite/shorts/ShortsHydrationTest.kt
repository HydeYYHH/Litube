package com.hhst.youtubelite.shorts

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.shorts.data.ShortsRepository
import com.hhst.youtubelite.shorts.data.ShortsScenario
import com.hhst.youtubelite.shorts.domain.ShortDetail
import com.hhst.youtubelite.shorts.domain.ShortItem
import com.hhst.youtubelite.shorts.domain.fillFrom
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.domain.ShortsPage
import com.hhst.youtubelite.shorts.domain.ShortsAction
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hydration request-count coverage: a settled item costs at most one watch read,
 * the adjacent prefetch is bounded to one, and fast swipes cancel in-flight reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShortsHydrationTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun thinItem(id: String) = ShortItem(id, "Title $id", "Author $id", "ch-$id", "art", "", "", "")

    private fun feed(ids: List<String>) = ShortsPage(ids.map { thinItem(it) })

    @Test fun hydrationKeepsChannelArtworkSeparateFromTheVideoCover() {
        val item = thinItem("s1")
        val filled = item.fillFrom(item.copy(artwork = "new-cover", avatar = "channel-avatar"))
        assertEquals("art", filled.artwork)
        assertEquals("channel-avatar", filled.avatar)
        assertEquals("feed-avatar", filled.copy(avatar = "feed-avatar").fillFrom(item).avatar)
    }

    private class HydratingRepository(
        ids: List<String>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ShortsRepository {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        private val page = ShortsPage(ids.map { ShortItem(it, "Title $it", "Author $it", "ch-$it", "art", "", "", "") })
        override suspend fun shorts() = page
        override suspend fun hydrate(itemId: String): ShortDetail? {
            started += itemId
            try {
                gate?.await()
            } catch (cancelled: CancellationException) {
                this.cancelled += itemId
                throw cancelled
            }
            val item = page.items.first { it.id == itemId }
            return ShortDetail(item.copy(views = "1K views", likes = "100", comments = "12"), null)
        }
    }

    private fun readyItems(vm: ShortsViewModel) = (vm.uiState.value.content as ShortsContent.Ready).page.items

    @Test fun settledItemHydratesOnceAndPrefetchStaysBounded() = runTest {
        val repo = HydratingRepository(listOf("s1", "s2", "s3", "s4"))
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceUntilIdle()
        // Current item plus the one adjacent prefetch — no more.
        assertEquals(listOf("s2", "s3"), repo.started)
        assertEquals("1K views", readyItems(vm).first { it.id == "s2" }.views)
        assertEquals("1K views", readyItems(vm).first { it.id == "s3" }.views)
        assertEquals("", readyItems(vm).first { it.id == "s4" }.views)
        // Re-settling a hydrated item fires nothing.
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceUntilIdle()
        assertEquals(listOf("s2", "s3", "s1"), repo.started)
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceUntilIdle()
        assertEquals(listOf("s2", "s3", "s1"), repo.started)
    }

    @Test fun fastSwipesCancelInFlightHydration() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = HydratingRepository(listOf("s1", "s2", "s3", "s4", "s5"), gate)
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s1"))
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s2"))
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s4"))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        // The swiped-past reads were cancelled; only the settled item and its
        // neighbour completed and merged.
        assertEquals(listOf("s1", "s2"), repo.cancelled)
        assertEquals(listOf("s1", "s2", "s4", "s5"), repo.started)
        assertEquals("1K views", readyItems(vm).first { it.id == "s4" }.views)
        assertEquals("1K views", readyItems(vm).first { it.id == "s5" }.views)
        assertEquals("", readyItems(vm).first { it.id == "s2" }.views)
        assertEquals(setOf("s4", "s5"), vm.uiState.value.details.keys)
    }

    @Test fun hydrationNeverBlocksTheFeed() = runTest {
        val repo = HydratingRepository(listOf("s1", "s2"), CompletableDeferred())
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s1"))
        runCurrent()
        assertEquals("s1", vm.uiState.value.currentItemId)
        assertTrue(vm.uiState.value.content is ShortsContent.Ready)
    }

    @Test fun revisitingAnEvictedDetailReloadsTrackingWithoutReloadingTheFeed() = runTest {
        val repo = HydratingRepository((1..32).map { "s$it" })
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        for (id in (1..30).map { "s$it" }) {
            vm.onAction(ShortsAction.SelectItem(id))
            advanceUntilIdle()
        }
        assertTrue("s1" !in vm.uiState.value.details)
        assertEquals("1K views", readyItems(vm).first().views)
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceUntilIdle()
        assertTrue("s1" in vm.uiState.value.details)
        assertEquals(2, repo.started.count { it == "s1" })
        assertTrue(vm.uiState.value.details.size <= 24)
        assertEquals(32, readyItems(vm).size)
    }
}
