package com.hhst.youtubelite.explore

import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorePagingBudgetTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun video(id: String) = FeedItem.Video(id, id, "Author", "1K views", "art", "1:00", "channel")

    @Test fun emptyAndDuplicatePagesPauseAfterThreeAndManualGrowthRestoresAutomaticPaging() = runTest {
        for (duplicate in listOf(false, true)) {
            var calls = 0
            val existing = video("existing")
            val repository = object : ExploreRepository {
                override suspend fun explore(category: ExploreCategory) = ExplorePage(category, listOf(existing), nextKey = "k1")
                override suspend fun exploreNext(category: ExploreCategory, key: String): ExplorePage {
                    calls++
                    val rows = if (calls >= 4) listOf(video("new$calls")) else if (duplicate) listOf(existing.copy(metadata = "$calls views")) else emptyList()
                    return ExplorePage(category, rows, nextKey = "k${calls + 1}")
                }
            }
            val vm = ExploreViewModel(repository, ExploreScenario.Content)
            advanceUntilIdle()
            repeat(20) { vm.onAction(ExploreAction.AutomaticLoadMore); advanceUntilIdle() }
            var ready = vm.uiState.value.content as ExploreContent.Ready
            assertEquals(3, calls)
            assertTrue(ready.automaticPagingPaused)
            assertEquals("k4", ready.page.nextKey)
            assertEquals(AppendState.Idle, ready.append)
            assertEquals(listOf("existing"), ready.page.videos.map { it.id })
            vm.onAction(ExploreAction.LoadMore)
            advanceUntilIdle()
            ready = vm.uiState.value.content as ExploreContent.Ready
            assertEquals(4, calls)
            assertFalse(ready.automaticPagingPaused)
            assertEquals(listOf("existing", "new4"), ready.page.videos.map { it.id })
            vm.onAction(ExploreAction.AutomaticLoadMore)
            advanceUntilIdle()
            assertEquals(5, calls)
        }
    }

    @Test fun emptyAndDuplicateShelvesHaveIndependentBudgetsAndManualGrowthRestartsThem() = runTest {
        for (duplicate in listOf(false, true)) {
            val calls = intArrayOf(0, 0)
            val existing = video("existing")
            val repository = object : ExploreRepository {
                override suspend fun explore(category: ExploreCategory) = ExplorePage(category, listOf(existing), sections =
                    (0..1).map { ExploreSection("Shelf $it", listOf(existing), nextKey = "$it-k1") })
                override suspend fun exploreShelf(category: ExploreCategory, key: String): ExploreSection {
                    val index = key.substringBefore('-').toInt()
                    val count = ++calls[index]
                    val rows = if (count >= 4) listOf(video("new$index-$count")) else if (duplicate) listOf(existing) else emptyList()
                    return ExploreSection(null, rows, nextKey = "$index-k${count + 1}")
                }
            }
            val vm = ExploreViewModel(repository, ExploreScenario.Content)
            advanceUntilIdle()
            repeat(20) { vm.onAction(ExploreAction.AutomaticLoadShelf(0)); advanceUntilIdle() }
            var shelves = (vm.uiState.value.content as ExploreContent.Ready).page.sections
            assertArrayEquals(intArrayOf(3, 0), calls)
            assertTrue(shelves[0].automaticPagingPaused)
            assertFalse(shelves[1].automaticPagingPaused)
            assertEquals(AppendState.Idle, shelves[0].append)
            assertEquals("0-k4", shelves[0].nextKey)
            vm.onAction(ExploreAction.AutomaticLoadShelf(1))
            vm.onAction(ExploreAction.LoadShelf(0))
            advanceUntilIdle()
            shelves = (vm.uiState.value.content as ExploreContent.Ready).page.sections
            assertArrayEquals(intArrayOf(4, 1), calls)
            assertFalse(shelves[0].automaticPagingPaused)
            assertEquals(listOf("existing", "new0-4"), shelves[0].videos.map { it.id })
            vm.onAction(ExploreAction.AutomaticLoadShelf(0))
            advanceUntilIdle()
            assertArrayEquals(intArrayOf(5, 1), calls)
        }
    }

    @Test fun manualEmptyPagesRemainPausedAndCursorCyclesStillEndTheList() = runTest {
        var calls = 0
        val repository = object : ExploreRepository {
            override suspend fun explore(category: ExploreCategory) = ExplorePage(category, emptyList(), nextKey = "k1")
            override suspend fun exploreNext(category: ExploreCategory, key: String): ExplorePage {
                calls++
                return ExplorePage(category, emptyList(), nextKey = if (calls == 5) "k2" else "k${calls + 1}")
            }
        }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        repeat(4) { vm.onAction(ExploreAction.AutomaticLoadMore); advanceUntilIdle() }
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        assertEquals(4, calls)
        assertTrue((vm.uiState.value.content as ExploreContent.Ready).automaticPagingPaused)
        repeat(4) { vm.onAction(ExploreAction.AutomaticLoadMore); advanceUntilIdle() }
        assertEquals(4, calls)
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        assertNull((vm.uiState.value.content as ExploreContent.Ready).page.nextKey)
        assertEquals(5, calls)
    }
}
