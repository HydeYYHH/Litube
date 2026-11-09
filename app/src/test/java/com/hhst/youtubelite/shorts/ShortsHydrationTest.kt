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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        assertEquals(item, item.fillFrom(thinItem("other").copy(views = "other views")))
    }

    @Test fun anEmptyHydrationResponseRetriesOnceWithoutBlockingTheSelectedShort() = runTest {
        var calls = 0
        val item = thinItem("s1")
        val repo = object : ShortsRepository {
            override suspend fun shorts() = ShortsPage(listOf(item))
            override suspend fun hydrate(itemId: String): ShortDetail? {
                calls++
                return if (calls == 1) null else ShortDetail(item.copy(views = "2K views", avatar = "avatar"))
            }
        }
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        vm.onPlaybackReady("s1")
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.content is ShortsContent.Ready)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("2K views", readyItems(vm).single().views)
        assertEquals("avatar", readyItems(vm).single().avatar)
    }

    @Test fun emptyHydrationRetriesAreBoundedAndSwipingCancelsTheBackoff() = runTest {
        val reads = mutableListOf<String>()
        val repo = object : ShortsRepository {
            override suspend fun shorts() = feed(listOf("s1", "s2"))
            override suspend fun hydrate(itemId: String): ShortDetail? { reads += itemId; return null }
        }
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        vm.onPlaybackReady("s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s2"))
        vm.onPlaybackReady("s2")
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s2"), reads)
        vm.onAction(ShortsAction.OpenMenu("s2"))
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s2"), reads)
    }

    @Test fun aDuplicateAppendCanFillMissingMetadataWithoutReplacingKnownValues() = runTest {
        val item = thinItem("s1")
        val repo = object : ShortsRepository {
            override suspend fun shorts() = ShortsPage(listOf(item), "next")
            override suspend fun shortsNext(cursor: String) = ShortsPage(listOf(
                item.copy(title = "Another title", views = "3K views", avatar = "avatar"), thinItem("s2")))
        }
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2"), readyItems(vm).map { it.id })
        assertEquals(item.title, readyItems(vm).first().title)
        assertEquals("3K views", readyItems(vm).first().views)
        assertEquals("avatar", readyItems(vm).first().avatar)
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

    @Test fun theSelectedPreparationIsRetainedUntilItsFirstFrameBeforeWarmingTheNext() = runTest {
        val events = mutableListOf<String>()
        val preloader = object : com.hhst.youtubelite.shorts.data.ShortsPreloader {
            override fun select(videoId: String) { events += "select:$videoId" }
            override fun preload(videoId: String?) { events += "preload:$videoId" }
            override fun cancel() { events += "cancel" }
        }
        val vm = ShortsViewModel(HydratingRepository(listOf("s1", "s2", "s3")),
            SavedStateHandle(), ShortsScenario.Content, "s1", preloader = preloader)
        advanceUntilIdle()
        events.clear()
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceUntilIdle()
        assertEquals(listOf("select:s1"), events)
        vm.onPlaybackReady("s1")
        assertEquals(listOf("select:s1", "preload:s2"), events)
        events.clear()
        vm.onAction(ShortsAction.SelectItem("s2"))
        vm.onPlaybackReady("s1")
        advanceUntilIdle()
        assertEquals(listOf("select:s2"), events)
        vm.onPlaybackReady("s2")
        assertEquals(listOf("select:s2", "preload:s3"), events)
    }

    @Test fun onlyTheSelectedItemHydratesAndRevisitsReuseItsDetail() = runTest {
        val repo = HydratingRepository(listOf("s1", "s2", "s3", "s4"))
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceUntilIdle()
        assertEquals(listOf("s2"), repo.started)
        assertEquals("1K views", readyItems(vm).first { it.id == "s2" }.views)
        assertEquals("", readyItems(vm).first { it.id == "s3" }.views)
        assertEquals("", readyItems(vm).first { it.id == "s4" }.views)
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceUntilIdle()
        assertEquals(listOf("s2", "s1"), repo.started)
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceUntilIdle()
        assertEquals(listOf("s2", "s1"), repo.started)
    }

    @Test fun fastSwipesRetainClaimedMetadataAndCancelReadsOutsideTheWindow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = HydratingRepository(listOf("s1", "s2", "s3", "s4", "s5"), gate)
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        advanceUntilIdle()
        vm.onAction(ShortsAction.SelectItem("s1"))
        vm.onPlaybackReady("s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s2"))
        vm.onPlaybackReady("s2")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s4"))
        vm.onPlaybackReady("s4")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s3"), repo.cancelled)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), repo.started)
        assertEquals("1K views", readyItems(vm).first { it.id == "s4" }.views)
        assertEquals("1K views", readyItems(vm).first { it.id == "s5" }.views)
        assertEquals("", readyItems(vm).first { it.id == "s2" }.views)
        assertEquals(setOf("s4", "s5"), vm.uiState.value.details.keys)
    }

    @Test fun detailsWaitForARealFrameAndFastSwipesDoNotReadSkippedItems() = runTest {
        val repo = HydratingRepository(listOf("s1", "s2", "s3"))
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceTimeBy(500)
        vm.onAction(ShortsAction.SelectItem("s2"))
        advanceTimeBy(500)
        vm.onPlaybackReady("s1")
        runCurrent()
        assertTrue(repo.started.isEmpty())
        vm.onPlaybackReady("s2")
        advanceUntilIdle()
        assertEquals(listOf("s2", "s3"), repo.started)
        assertEquals(setOf("s2", "s3"), vm.uiState.value.details.keys)
    }

    @Test fun stalledPlaybackStillGetsDetailsAndItsFirstFrameDoesNotRestartTheRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = HydratingRepository(listOf("s1", "s2"), gate)
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceTimeBy(2_999)
        assertTrue(repo.started.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("s1"), repo.started)
        vm.onPlaybackReady("s1")
        runCurrent()
        assertTrue(repo.cancelled.isEmpty())
        assertEquals(listOf("s1", "s2"), repo.started)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("s1", "s2"), vm.uiState.value.details.keys)
    }

    @Test fun openingTheMenuDoesNotWaitForPlayback() = runTest {
        val repo = HydratingRepository(listOf("s1", "s2"))
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        vm.onAction(ShortsAction.OpenMenu("s1"))
        runCurrent()
        assertEquals(listOf("s1"), repo.started)
        assertEquals(setOf("s1"), vm.uiState.value.details.keys)
    }

    @Test fun returningBeforeCancellationCompletesStillHydratesTheSelectedItem() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = HydratingRepository(listOf("s1", "s2"), gate)
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        vm.onPlaybackReady("s1")
        runCurrent()
        // Both selections happen before the cancelled coroutine gets another main-loop turn.
        vm.onAction(ShortsAction.SelectItem("s2"))
        vm.onAction(ShortsAction.SelectItem("s1"))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s1"), repo.started)
        assertEquals(listOf("s1"), repo.cancelled)
        assertEquals(setOf("s1", "s2"), vm.uiState.value.details.keys)
    }

    @Test fun nextTwoDetailsAreReadyBeforeSelectionAndAreNotFetchedAgain() = runTest {
        val repo = HydratingRepository(listOf("s1", "s2", "s3", "s4"))
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        advanceTimeBy(500)
        assertTrue(repo.started.isEmpty())
        vm.onPlaybackReady("s1")
        runCurrent()
        assertEquals(listOf("s1", "s2", "s3"), repo.started)
        assertEquals(setOf("s1", "s2", "s3"), vm.uiState.value.details.keys)
        vm.onAction(ShortsAction.SelectItem("s2"))
        assertEquals("1K views", readyItems(vm).first { it.id == "s2" }.views)
        vm.onPlaybackReady("s2")
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s3", "s4"), repo.started)
        assertTrue(repo.cancelled.isEmpty())
    }

    @Test fun accountChangeCancelsUpcomingMetadataAndDiscardsOldDetails() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = HydratingRepository(listOf("s1", "s2", "s3"), gate)
        val vm = ShortsViewModel(repo, SavedStateHandle(), ShortsScenario.Content, "s1")
        runCurrent()
        vm.onAction(ShortsAction.SelectItem("s1"))
        vm.onPlaybackReady("s1")
        runCurrent()
        vm.updateSession(Long.MAX_VALUE)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("s1", "s2", "s3"), repo.cancelled.toSet())
        assertTrue(vm.uiState.value.details.isEmpty())
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
