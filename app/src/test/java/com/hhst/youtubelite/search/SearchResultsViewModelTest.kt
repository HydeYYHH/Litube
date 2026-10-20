package com.hhst.youtubelite.search

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.search.data.FixtureSearchRepository
import com.hhst.youtubelite.search.data.SearchScenario
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String) -> SearchPage) = object : SearchRepository {
        override suspend fun search(query: String) = block(query)
    }

    @Test fun ordinaryEntryLoadsTheLocalResultsForTheSubmittedQuery() = runTest {
        val queries = mutableListOf<String>()
        val vm = SearchResultsViewModel(repository { query -> queries += query; SearchPage(query, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie")
        assertEquals(SearchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(listOf("open movie"), queries)
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals("open movie", page.query)
        assertEquals(FixtureSearchRepository.sampleResults(), page.results)
    }

    @Test fun emptyReplyIsItsOwnStateAndNotAFailure() = runTest {
        val vm = SearchResultsViewModel(repository { SearchPage(it, emptyList()) }, SavedStateHandle(), "nothing here")
        advanceUntilIdle()
        assertEquals(SearchContent.Empty("nothing here"), vm.uiState.value.content)
    }

    @Test fun failedRequestKeepsLoadingOutAndRetryRecovers() = runTest {
        var fail = true
        val vm = SearchResultsViewModel(repository { query ->
            if (fail) error("offline")
            SearchPage(query, FixtureSearchRepository.sampleResults())
        }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        assertEquals(SearchContent.Failed, vm.uiState.value.content)
        fail = false
        vm.onAction(SearchAction.Retry)
        assertEquals(SearchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SearchContent.Results)
    }

    @Test fun pinnedScenariosDoNotCallTheRepository() = runTest {
        var calls = 0
        for (scenario in listOf(SearchScenario.Empty, SearchScenario.Loading, SearchScenario.Error)) {
            val vm = SearchResultsViewModel(repository { calls++; SearchPage(it, emptyList()) }, SavedStateHandle(), "open movie", scenario)
            advanceUntilIdle()
            assertEquals(scenario.initialContent("open movie"), vm.uiState.value.content)
        }
        assertEquals(0, calls)
    }

    @Test fun resultClickEmitsOnlyTheWatchRequest() = runTest {
        val vm = SearchResultsViewModel(repository { SearchPage(it, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenResult("harbour"))
        assertEquals(SearchNavigationRequest.Watch("harbour"), vm.navigationRequests.first())
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        assertTrue(vm.uiState.value.content is SearchContent.Results)
    }

    @Test fun channelAvatarEmitsTheChannelRequest() = runTest {
        val vm = SearchResultsViewModel(repository { SearchPage(it, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenChannel("northlight"))
        assertEquals(SearchNavigationRequest.Channel("northlight"), vm.navigationRequests.first())
    }

    @Test fun chromeControlsEmitTypedRequestsWithoutSimulatingSuccess() = runTest {
        val vm = SearchResultsViewModel(repository { SearchPage(it, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("studio"))
        assertEquals(SearchNavigationRequest.ItemMenu("studio"), vm.navigationRequests.first())
        vm.onAction(SearchAction.SelectDestination(SearchDestination.Subscriptions))
        assertEquals(SearchNavigationRequest.Destination(SearchDestination.Subscriptions), vm.navigationRequests.first())
        vm.onAction(SearchAction.SelectDestination(SearchDestination.Home))
        assertEquals(SearchNavigationRequest.Destination(SearchDestination.Home), vm.navigationRequests.first())
        vm.onAction(SearchAction.OpenFilters)
        assertEquals(SearchNavigationRequest.Filters, vm.navigationRequests.first())
        vm.onAction(SearchAction.VoiceSearch)
        assertEquals(SearchNavigationRequest.VoiceSearch, vm.navigationRequests.first())
        vm.onAction(SearchAction.OpenPageMenu)
        assertEquals(SearchNavigationRequest.PageMenu, vm.navigationRequests.first())
    }

    @Test fun querySurvivesSavedStateReconstruction() {
        val handle = SavedStateHandle()
        val vm = SearchResultsViewModel(FixtureSearchRepository(), handle, "open movie")
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = SearchResultsViewModel(FixtureSearchRepository(), restoredHandle, "something else")
        assertEquals("open movie", restored.uiState.value.query)
    }

    @Test fun singleTopIntentWithAnotherQueryReloadsTheLocalPage() = runTest {
        val queries = mutableListOf<String>()
        val vm = SearchResultsViewModel(repository { query -> queries += query; SearchPage(query, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.updateQuery("open movie")
        advanceUntilIdle()
        vm.updateQuery("meadow")
        advanceUntilIdle()
        assertEquals(listOf("open movie", "meadow"), queries)
        assertEquals("meadow", (vm.uiState.value.content as SearchContent.Results).page.query)
    }

    @Test fun sampledSessionUsesTheSignedInPivotBar() {
        val vm = SearchResultsViewModel(FixtureSearchRepository(), SavedStateHandle(), "open movie")
        assertTrue(vm.uiState.value.signedInPreview)
    }

    @Test fun listLockupFollowsTheSampledLandscapeRule() {
        listOf(
            Triple(320f, 844f, false), Triple(390f, 844f, false), Triple(411f, 844f, false),
            Triple(600f, 960f, false), Triple(840f, 1000f, false),
            Triple(700f, 400f, true), Triple(840f, 390f, true), Triple(900f, 900f, false),
            Triple(930f, 900f, false), Triple(931f, 900f, true), Triple(960f, 900f, true),
            Triple(1000f, 1200f, false), Triple(1280f, 800f, true), Triple(1600f, 900f, true),
        ).forEach { (width, height, expected) ->
            assertEquals("$width × $height", expected, SearchResultsLayout.listLockup(width, height))
        }
    }

    @Test fun resultsColumnIsCappedAtTheSampledWideColumn() {
        listOf(320f, 390f, 411f, 600f, 840f).forEach { width ->
            assertEquals(width, SearchResultsLayout.columnWidth(width), 0.01f)
            assertEquals(0f, SearchResultsLayout.columnInset(width), 0.01f)
        }
        assertEquals(840f, SearchResultsLayout.columnWidth(1280f), 0.01f)
        assertEquals(220f, SearchResultsLayout.columnInset(1280f), 0.01f)
        assertEquals(840f, SearchResultsLayout.columnWidth(1600f), 0.01f)
        assertEquals(380f, SearchResultsLayout.columnInset(1600f), 0.01f)
    }

    @Test fun stackedLockupMatchesTheSampledPhoneAndTabletAnchors() {
        // 390 × 844: the sampled cover bleeds to both edges, keeps a 16:9 box and carries a 48px menu.
        assertTrue(SearchResultsLayout.fullBleed(390f))
        assertEquals(390f, SearchResultsLayout.stackedThumbnailWidth(390f), 0.01f)
        assertEquals(218.875f, SearchResultsLayout.stackedThumbnailHeight(390f), 0.01f)
        assertEquals(0f, SearchResultsLayout.stackedThumbnailRadius(390f), 0.01f)
        assertEquals(48f, SearchResultsLayout.stackedMenuSize(390f), 0.01f)
        assertEquals(0f, SearchResultsLayout.listTopInset(390f), 0.01f)
        // 390 × 844: text starts after the 12px inset, the 40px avatar and the 12px gap.
        assertEquals(278f, 390f - 12f - 40f - 12f - SearchResultsLayout.stackedMenuSize(390f), 0.01f)
        // 600 × 960 and 840 × 1000: a 12px inset cover of an exact 16:9 box with a 40px menu.
        for (width in listOf(600f, 840f)) {
            assertFalse(SearchResultsLayout.fullBleed(width))
            assertEquals(width - 24f, SearchResultsLayout.stackedThumbnailWidth(width), 0.01f)
            assertEquals((width - 24f) * 9f / 16f, SearchResultsLayout.stackedThumbnailHeight(width), 0.01f)
            assertEquals(12f, SearchResultsLayout.stackedThumbnailRadius(width), 0.01f)
            assertEquals(40f, SearchResultsLayout.stackedMenuSize(width), 0.01f)
            assertEquals(12f, SearchResultsLayout.listTopInset(width), 0.01f)
        }
        assertEquals(484f, 600f - 12f - 40f - 12f - 40f - 12f, 0.01f)
        assertEquals(724f, 840f - 12f - 40f - 12f - 40f - 12f, 0.01f)
        assertEquals(24f, SearchResultsLayout.itemSpacing(390f, 844f), 0.01f)
        assertEquals(24f, SearchResultsLayout.itemSpacing(840f, 1000f), 0.01f)
    }

    @Test fun listLockupMatchesTheSampledWideAnchors() {
        val width = 1280f
        val height = 800f
        assertEquals(40f, SearchResultsLayout.menuSize(width, height), 0.01f)
        assertEquals(12f, SearchResultsLayout.itemSpacing(width, height), 0.01f)
        assertEquals(12f, SearchResultsLayout.listTopInset(width), 0.01f)
        val itemLeft = SearchResultsLayout.columnInset(width)
        assertEquals(220f, itemLeft, 0.01f)
        val thumbLeft = itemLeft + 12f
        assertEquals(232f, thumbLeft, 0.01f)
        assertEquals(200f, SearchResultsLayout.ListThumbnailWidth, 0.01f)
        assertEquals(112f, SearchResultsLayout.ListThumbnailHeight, 0.01f)
        // Text starts after the 200px thumbnail and its 12px gap; the menu closes the row.
        val textLeft = thumbLeft + 200f + 12f
        assertEquals(444f, textLeft, 0.01f)
        val textWidth = 840f - 24f - 200f - 12f - 12f - 40f
        assertEquals(552f, textWidth, 0.01f)
        val menuRight = itemLeft + 840f - 12f - 12f
        assertEquals(1036f, menuRight, 0.01f)
        assertEquals(5f, SearchResultsLayout.ListBadgeInset, 0.01f)
        assertEquals(8f, SearchResultsLayout.BadgeInset, 0.01f)
    }


    @Test fun restoredEmptyStateUsesTheRestoredQuery() {
        val vm = SearchResultsViewModel(FixtureSearchRepository(), SavedStateHandle(mapOf("query" to "saved")),
            "intent", SearchScenario.Empty)
        assertEquals(SearchContent.Empty("saved"), vm.uiState.value.content)
    }

    @Test fun editingSurvivesReconstructionAndDoesNotSearchUntilSubmitted() = runTest {
        val queries = mutableListOf<String>()
        val repo = repository { query -> queries += query; SearchPage(query, FixtureSearchRepository.sampleResults()) }
        val handle = SavedStateHandle()
        val vm = SearchResultsViewModel(repo, handle, "first")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenSearch)
        vm.onAction(SearchAction.QueryChanged("  next  "))
        assertEquals(listOf("first"), queries)
        val restored = SearchResultsViewModel(repo,
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), "intent")
        assertTrue(restored.uiState.value.searchOpen)
        assertEquals("  next  ", restored.uiState.value.draftQuery)
        advanceUntilIdle()
        restored.onAction(SearchAction.SubmitSearch(restored.uiState.value.draftQuery))
        advanceUntilIdle()
        assertEquals("next", restored.uiState.value.query)
        assertFalse(restored.uiState.value.searchOpen)
        assertEquals("next", (restored.uiState.value.content as SearchContent.Results).page.query)
        restored.onAction(SearchAction.OpenSearch)
        restored.onAction(SearchAction.QueryChanged(""))
        restored.onAction(SearchAction.SubmitSearch(""))
        assertTrue(restored.uiState.value.searchOpen)
        restored.onAction(SearchAction.CloseSearch)
        assertEquals("next", restored.uiState.value.query)
    }


    @Test fun narrowLandscapeAndSquareWindowsKeepTheirOwnGeometry() {
        assertEquals(445.5f, SearchResultsLayout.stackedThumbnailHeight(900f, 844f), 0.01f)
        assertEquals(445.5f, SearchResultsLayout.stackedThumbnailHeight(930f, 820f), 0.01f)
        assertEquals(0f, SearchResultsLayout.stackedThumbnailRadius(900f, 844f), 0.01f)
        assertEquals(48f, SearchResultsLayout.menuSize(900f, 844f), 0.01f)
        assertEquals(48f, SearchResultsLayout.menuSize(700f, 352f), 0.01f)
        assertEquals(48f, SearchResultsLayout.menuSize(840f, 334f), 0.01f)
        assertEquals(40f, SearchResultsLayout.menuSize(931f, 820f), 0.01f)
        assertEquals(459f, SearchResultsLayout.stackedThumbnailHeight(1000f, 1104f), 0.01f)
    }

    @Test fun fixtureKeepsEnglishMetadataAndLocalArtwork() {
        val results = FixtureSearchRepository.sampleResults()
        assertTrue(results.size >= 6)
        assertTrue(results.map { it.id }.distinct().size == results.size)
        assertEquals(listOf("bunny", "dream", "sintel"), results.map { it.artwork }.distinct().sorted())
        assertTrue(results.all { it.title.isNotBlank() && it.channel.isNotBlank() && it.channelId.isNotBlank() })
        assertTrue(results.all { it.views.endsWith("views") })
        assertTrue(results.all { it.age.isNotBlank() && it.duration.isNotBlank() })
        assertEquals(listOf("4K"), results.mapNotNull { it.badge })
        assertTrue(results.any { it.progress != null })
        assertEquals(results[0].id, results[0].copy().id)
    }
}
