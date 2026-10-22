package com.hhst.youtubelite.explore

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.data.FixtureExploreRepository
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun musicIsTheDefaultContainerAndCategoriesLoadTheirOwnPages() = runTest {
        val vm = ExploreViewModel(FixtureExploreRepository(), ExploreScenario.Content)
        advanceUntilIdle()
        val first = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(ExploreCategory.Music, first.page.category)
        assertTrue(first.page.videos.all { it.id.startsWith("music-") })
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Sports))
        advanceUntilIdle()
        val sports = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(ExploreCategory.Sports, sports.page.category)
        assertTrue(sports.page.videos.all { it.id.startsWith("sports-") })
    }

    @Test fun videoAndChannelOpeningsStayTyped() = runTest {
        val vm = ExploreViewModel(FixtureExploreRepository(), ExploreScenario.Content)
        advanceUntilIdle()
        val requests = mutableListOf<ExploreNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(ExploreAction.OpenVideo("music-bunny"))
        vm.onAction(ExploreAction.OpenChannel("blender"))
        advanceUntilIdle()
        assertEquals(listOf(ExploreNavigationRequest.Watch("music-bunny"), ExploreNavigationRequest.Channel("blender")), requests)
    }

    @Test fun failureScenarioStaysFailedUntilRetry() = runTest {
        var fail = true
        val vm = ExploreViewModel(object : ExploreRepository {
            override suspend fun explore(category: ExploreCategory): ExplorePage {
                if (fail) throw java.io.IOException("offline")
                return ExplorePage(category, FixtureExploreRepository.sample(category))
            }
        }, ExploreScenario.Content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ExploreContent.Failed)
        fail = false
        vm.onAction(ExploreAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ExploreContent.Ready)
    }
}
