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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String) -> SearchPage) = object : SearchRepository {
        override suspend fun search(query: String, filters: SearchFilters) = block(query)
    }

    @Test fun pagingKeepsMetadataThatArrivesWhileTheNextPageIsPending() = runTest {
        val row = SearchResult("song", "Song", "Artist", "UCartist", "2K views", "", "", "cover")
        val metadata = kotlinx.coroutines.CompletableDeferred<SearchResult>()
        val next = kotlinx.coroutines.CompletableDeferred<SearchPage>()
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(row), nextCursor = "next")
            override suspend fun metadata(result: SearchResult) = metadata.await()
            override suspend fun next(query: String, filters: SearchFilters, cursor: String) = next.await()
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "music")
        runCurrent()
        assertTrue(vm.uiState.value.content is SearchContent.Results)
        vm.onAction(SearchAction.LoadMore)
        runCurrent()
        metadata.complete(row.copy(duration = "3:41", age = "2025-11-10"))
        runCurrent()
        next.complete(SearchPage("music", listOf(row.copy(id = "another", duration = "4:57"))))
        advanceUntilIdle()
        val result = (vm.uiState.value.content as SearchContent.Results).page.results.first()
        assertEquals("3:41", result.duration)
        assertEquals("2025-11-10", result.age)
    }

    @Test fun optionalMetadataIsBoundedAndDoesNotBlockResults() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var requests = 0
        val rows = (1..10).map { SearchResult("song$it", "Song", "Artist", "UCartist", "", "", "", "") }
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, rows)
            override suspend fun metadata(result: SearchResult): SearchResult {
                requests++; active++; maximum = maxOf(maximum, active)
                gate.await(); active--
                return result.copy(duration = "3:41", age = "Today", views = "1K views")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "music")
        runCurrent()
        assertTrue(vm.uiState.value.content is SearchContent.Results)
        assertEquals(3, requests)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, maximum)
        assertEquals(3, requests)
        vm.onAction(SearchAction.VisibleResults("music", SearchFilters(), rows.drop(3).map { it.id }))
        advanceUntilIdle()
        assertEquals(10, requests)
        assertTrue((vm.uiState.value.content as SearchContent.Results).page.results.all { it.duration == "3:41" })
    }

    @Test fun changingQueryCancelsMetadataAndAllowsTheSameVideoToBeEnrichedAgain() = runTest {
        val row = SearchResult("song", "Song", "Artist", "UCartist", "", "", "", "")
        var requests = 0
        var cancelled = false
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(row))
            override suspend fun metadata(result: SearchResult): SearchResult {
                if (++requests == 1) try { kotlinx.coroutines.awaitCancellation() }
                finally { cancelled = true }
                return result.copy(duration = "3:41", age = "Today", views = "1K views")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "old")
        runCurrent()
        vm.onAction(SearchAction.SubmitSearch("new"))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(2, requests)
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals("new", page.query)
        assertEquals("3:41", page.results.single().duration)
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
        assertEquals(SearchOverlay.ItemMenu("studio"), vm.uiState.value.overlay)
        vm.onAction(SearchAction.CloseItemMenu)
        assertEquals(SearchOverlay.None, vm.uiState.value.overlay)
        vm.onAction(SearchAction.SelectDestination(SearchDestination.Subscriptions))
        assertEquals(SearchNavigationRequest.Destination(SearchDestination.Subscriptions), vm.navigationRequests.first())
        vm.onAction(SearchAction.SelectDestination(SearchDestination.Home))
        assertEquals(SearchNavigationRequest.Destination(SearchDestination.Home), vm.navigationRequests.first())
        vm.onAction(SearchAction.OpenFilters)
        assertEquals(SearchNavigationRequest.Filters, vm.navigationRequests.first())
        assertTrue(vm.uiState.value.filtersOpen)
        vm.onAction(SearchAction.VoiceSearch)
        assertEquals(SearchNavigationRequest.VoiceSearch, vm.navigationRequests.first())
        vm.onAction(SearchAction.OpenPageMenu)
        assertEquals(SearchNavigationRequest.PageMenu, vm.navigationRequests.first())
    }

    @Test fun uploadDateAppliesImmediatelyAndReloadsWithTheFilter() = runTest {
        val seen = mutableListOf<SearchFilters>()
        val vm = SearchResultsViewModel(object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters): SearchPage {
                seen += filters
                return FixtureSearchRepository().search(query, filters)
            }
        }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.SelectUploadDate(SearchUploadDate.ThisYear))
        advanceUntilIdle()
        assertEquals(SearchUploadDate.ThisYear, vm.uiState.value.filters.uploaded)
        assertEquals(SearchFilters(uploaded = SearchUploadDate.ThisYear), seen.last())
        assertTrue(vm.uiState.value.content is SearchContent.Results)
        vm.onAction(SearchAction.SelectUploadDate(SearchUploadDate.Today))
        advanceUntilIdle()
        assertEquals(SearchFilters(uploaded = SearchUploadDate.Today), seen.last())
        assertEquals(SearchContent.Empty("open movie"), vm.uiState.value.content)
    }

    @Test fun channelsTypeLoadsChannelCardsAndSubscriptionKeepsTypedIdentity() = runTest {
        val seen = mutableListOf<SearchFilters>()
        val vm = SearchResultsViewModel(object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters): SearchPage {
                seen += filters
                return FixtureSearchRepository().search(query, filters)
            }
        }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.SelectResultType(SearchResultType.All))
        advanceUntilIdle()
        assertEquals(SearchResultType.All, vm.uiState.value.filters.type)
        vm.onAction(SearchAction.SelectResultType(SearchResultType.Channels))
        advanceUntilIdle()
        assertEquals(SearchResultType.Channels, vm.uiState.value.filters.type)
        assertEquals(SearchFilters(type = SearchResultType.Channels), seen.last())
        val channelPage = (vm.uiState.value.content as SearchContent.Results).page
        assertTrue(channelPage.results.isEmpty())
        assertEquals(FixtureSearchRepository.sampleChannels(), channelPage.channels)
        assertFalse(vm.uiState.value.signedInPreview)
        vm.onAction(SearchAction.Subscribe("northlight"))
        assertEquals(SearchNavigationRequest.Subscribe("northlight"), vm.navigationRequests.first())
        vm.onAction(SearchAction.SelectResultType(SearchResultType.Playlists))
        advanceUntilIdle()
        assertEquals(SearchResultType.Playlists, vm.uiState.value.filters.type)
        assertTrue(vm.uiState.value.content is SearchContent.Results)
        assertTrue((vm.uiState.value.content as SearchContent.Results).page.results.isEmpty())
        assertEquals(2, (vm.uiState.value.content as SearchContent.Results).page.playlists.size)
        assertFalse(vm.uiState.value.signedInPreview)
        assertEquals(SearchFilters(type = SearchResultType.Playlists), seen.last())
    }

    @Test fun itemMenuShareExitsAndOtherRowsStayTypedRequests() = runTest {
        val vm = SearchResultsViewModel(repository { SearchPage(it, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.Share))
        assertEquals(SearchNavigationRequest.Share("harbour"), vm.navigationRequests.first())
        assertEquals(SearchOverlay.None, vm.uiState.value.overlay)
        vm.onAction(SearchAction.OpenItemMenu("studio"))
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.WatchLater))
        assertEquals(SearchNavigationRequest.MenuAction("studio", SearchVideoMenuAction.WatchLater), vm.navigationRequests.first())
    }

    @Test fun inlineFiltersStayOpenUnderMenusAndSearch() = runTest {
        val vm = SearchResultsViewModel(FixtureSearchRepository(), SavedStateHandle(), "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenFilters)
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        assertTrue(vm.uiState.value.filtersOpen)
        vm.onAction(SearchAction.CloseItemMenu)
        assertTrue(vm.uiState.value.filtersOpen)
        vm.onAction(SearchAction.OpenSearch)
        vm.onAction(SearchAction.CloseSearch)
        assertTrue(vm.uiState.value.filtersOpen)
    }

    @Test fun filterMenuAndSearchOverlaysRestoreThroughSavedState() = runTest {
        val handle = SavedStateHandle()
        val vm = SearchResultsViewModel(FixtureSearchRepository(), handle, "open movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenFilters)
        vm.onAction(SearchAction.SelectUploadDate(SearchUploadDate.ThisMonth))
        var restored = SearchResultsViewModel(
            FixtureSearchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "intent",
        )
        assertTrue(restored.uiState.value.filtersOpen)
        assertEquals(SearchUploadDate.ThisMonth, restored.uiState.value.filters.uploaded)
        vm.onAction(SearchAction.CloseFilters)
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        restored = SearchResultsViewModel(
            FixtureSearchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "intent",
        )
        assertEquals(SearchOverlay.ItemMenu("harbour"), restored.uiState.value.overlay)
        vm.onAction(SearchAction.CloseItemMenu)
        restored = SearchResultsViewModel(
            FixtureSearchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "intent",
        )
        assertEquals(SearchOverlay.None, restored.uiState.value.overlay)
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
        // 390 × 844: sampled cover bleeds both edges, keeps a 16:9 box, 48px menu.
        assertTrue(SearchResultsLayout.fullBleed(390f))
        assertEquals(390f, SearchResultsLayout.stackedThumbnailWidth(390f), 0.01f)
        assertEquals(218.875f, SearchResultsLayout.stackedThumbnailHeight(390f), 0.01f)
        assertEquals(0f, SearchResultsLayout.stackedThumbnailRadius(390f), 0.01f)
        assertEquals(48f, SearchResultsLayout.stackedMenuSize(390f), 0.01f)
        assertEquals(0f, SearchResultsLayout.listTopInset(390f), 0.01f)
        // 390 × 844: text starts after the 12px inset, 40px avatar and 12px gap.
        assertEquals(278f, 390f - 12f - 40f - 12f - SearchResultsLayout.stackedMenuSize(390f), 0.01f)
        // 600 × 960 and 840 × 1000: 12px inset cover of an exact 16:9 box with a 40px menu.
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
    @Test fun paginationAppendsUniqueResultsAndRetriesWithoutReplacingTheList() = runTest {
        val initial = com.hhst.youtubelite.search.data.FixtureSearchRepository().search("movie")
        var calls = 0
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = initial.copy(nextCursor = "page-2")
            override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage {
                calls++
                if (calls == 1) throw java.io.IOException("offline")
                return initial.copy(results = initial.results.take(1) + initial.results.take(1).map { it.copy(id = "new-result") })
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "movie")
        advanceUntilIdle()
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        val failed = vm.uiState.value.content as SearchContent.Results
        assertTrue(failed.loadMoreFailed)
        assertEquals(initial.results, failed.page.results)
        vm.onAction(SearchAction.LoadMore)
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as SearchContent.Results
        assertEquals(2, calls)
        assertEquals(initial.results.size + 1, ready.page.results.size)
        assertEquals(null, ready.page.nextCursor)
        assertFalse(ready.loadMoreFailed)
    }

    @Test fun changingQueryCancelsOldPaginationAndPreservesTheNewResults() = runTest {
        val initial = com.hhst.youtubelite.search.data.FixtureSearchRepository().search("movie")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = initial.copy(query = query, nextCursor = "page-2")
            override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage {
                gate.await()
                return initial.copy(query = query)
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "old")
        advanceUntilIdle()
        vm.onAction(SearchAction.LoadMore)
        runCurrent()
        vm.onAction(SearchAction.SubmitSearch("new"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", (vm.uiState.value.content as SearchContent.Results).page.query)
    }

    @Test fun changedSessionReloadsResultsWithoutLosingQueryOrFilters() = runTest {
        var calls = 0
        val vm = SearchResultsViewModel(repository { query ->
            calls++
            SearchPage(query, com.hhst.youtubelite.search.data.FixtureSearchRepository.sampleResults())
        }, SavedStateHandle(), "movie", signedIn = false)
        advanceUntilIdle()
        vm.updateSession(false, 1L)
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("movie", vm.uiState.value.query)
        assertTrue(vm.uiState.value.signedInPreview)
    }

    @Test fun signedInRecoveryWithinTheGenerationDoesNotRestartThePendingSearch() = runTest {
        val response = kotlinx.coroutines.CompletableDeferred<SearchPage>()
        var calls = 0
        val vm = SearchResultsViewModel(repository {
            calls++
            response.await()
        }, SavedStateHandle(), "movie", signedIn = false)
        vm.updateSession(false, 1L)
        runCurrent()

        vm.updateSession(true, 1L)
        assertTrue(vm.uiState.value.signedInPreview)
        response.complete(SearchPage("movie", FixtureSearchRepository.sampleResults()))
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals("movie", (vm.uiState.value.content as SearchContent.Results).page.query)
        assertTrue(vm.uiState.value.signedInPreview)
    }

    private class RecordingVideoActions : com.hhst.youtubelite.watch.domain.VideoActions {
        val subscriptions = ArrayList<Pair<String, Boolean>>()
        var error: Exception? = null
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating, previous: com.hhst.youtubelite.watch.domain.VideoRating) = Unit
        override suspend fun subscribe(channelId: String, subscribed: Boolean) {
            error?.let { throw it }
            subscriptions += channelId to subscribed
            gate?.await()
        }
    }

    private class FakeSaveRepository(
        var choicesError: Exception? = null,
        var writeError: Exception? = null,
    ) : com.hhst.youtubelite.saves.domain.SaveRepository {
        var watchLaterCalls = 0
        override suspend fun choices(videoId: String) = choicesError?.let { throw it }
            ?: listOf(com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {
            writeError?.let { throw it }
        }
        override suspend fun saveWatchLater(videoId: String) {
            writeError?.let { throw it }
            watchLaterCalls++
        }
        override suspend fun create(title: String, description: String, videoId: String): com.hhst.youtubelite.saves.domain.SaveChoice {
            writeError?.let { throw it }
            return com.hhst.youtubelite.saves.domain.SaveChoice("PLnew", title, true)
        }
        override suspend fun savePlaylistToLibrary(playlistId: String) {
            writeError?.let { throw it }
        }
    }

    private fun liveViewModel(actions: RecordingVideoActions? = null, saves: FakeSaveRepository? = null) =
        SearchResultsViewModel(repository { SearchPage(it, FixtureSearchRepository.sampleResults()) }, SavedStateHandle(), "open movie",
            videoActions = actions, saveRepository = saves)

    @Test fun liveSubscribeWritesThroughTheServerAndUpdatesTheState() = runTest {
        val actions = RecordingVideoActions()
        val vm = liveViewModel(actions = actions)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.live)
        vm.onAction(SearchAction.Subscribe("northlight"))
        advanceUntilIdle()
        assertEquals(listOf("northlight" to true), actions.subscriptions)
        assertEquals(true, vm.uiState.value.subscriptions["northlight"])
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(SearchAction.Subscribe("northlight"))
        advanceUntilIdle()
        assertEquals(listOf("northlight" to true, "northlight" to false), actions.subscriptions)
        assertEquals(false, vm.uiState.value.subscriptions["northlight"])
    }

    @Test fun subscriptionProgressClearsOnFailureAndPreservesConfirmedMembership() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = RecordingVideoActions().apply { this.gate = gate }
        val vm = liveViewModel(actions = actions)
        advanceUntilIdle()
        vm.onAction(SearchAction.Subscribe("northlight"))
        assertEquals(setOf("northlight"), vm.uiState.value.subscriptionsPending)
        vm.onAction(SearchAction.Subscribe("northlight"))
        runCurrent()
        assertEquals(1, actions.subscriptions.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.subscriptionsPending.isEmpty())
        assertEquals(true, vm.uiState.value.subscriptions["northlight"])
        actions.error = java.io.IOException("offline")
        vm.onAction(SearchAction.Subscribe("northlight"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.subscriptionsPending.isEmpty())
        assertEquals(true, vm.uiState.value.subscriptions["northlight"])
    }

    @Test fun failedLiveSubscribeKeepsTheStateAndNotifies() = runTest {
        val actions = RecordingVideoActions().apply { error = com.hhst.youtubelite.watch.domain.SignInRequiredException() }
        val vm = liveViewModel(actions = actions)
        advanceUntilIdle()
        vm.onAction(SearchAction.Subscribe("northlight"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.subscriptions.isEmpty())
        assertEquals(AppNotice.SignIn, vm.actionNotices.first())
    }

    @Test fun liveMenuWatchLaterWritesThroughTheSaveSheet() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveViewModel(saves = saves)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(1, saves.watchLaterCalls)
        assertEquals(SearchOverlay.None, vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun liveMenuSaveToPlaylistOpensTheSheetWithChoices() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveViewModel(saves = saves)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        val sheet = vm.saveSheet?.state?.value
        assertEquals("harbour", sheet?.videoId)
        assertEquals(listOf(com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false)), sheet?.choices)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun liveMenuWithoutCapturedTokensNeverWritesFeedback() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveViewModel(saves = saves)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.NotInterested))
        advanceUntilIdle()
        assertEquals(SearchOverlay.None, vm.uiState.value.overlay)
        assertEquals(0, saves.watchLaterCalls)
        assertNull(vm.saveSheet?.state?.value)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    /** Live repository with deferred-menu reads; records get_panel and feedback operations. */
    private class MenuRepository(val cards: List<SearchResult>) : SearchRepository {
        var menuCalls = 0
        var menuError: Exception? = null
        val feedback = mutableListOf<Pair<SearchResult, SearchVideoMenuAction>>()
        override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, cards)
        override suspend fun menu(result: SearchResult): SearchResult {
            menuCalls++
            menuError?.let { throw it }
            return result.copy(notInterestedToken = "token-${result.id}", dontRecommendToken = "chan-${result.id}")
        }
        override suspend fun feedback(result: SearchResult, action: SearchVideoMenuAction) {
            feedback += result to action
        }
    }

    private fun menuViewModel(repository: MenuRepository, session: Pair<Boolean, Long>? = true to 2L): SearchResultsViewModel {
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "open movie",
            videoActions = RecordingVideoActions(), saveRepository = FakeSaveRepository())
        if (session != null) vm.updateSession(session.first, session.second)
        return vm
    }

    private fun card(id: String, channelId: String = "northlight") = SearchResult(id, "Title $id", "Author", channelId,
        "", "", "", "", menuEndpoint = com.hhst.youtubelite.home.domain.FeedMenuEndpoint("menu", "params-$id"),
        feedbackGeneration = 2)

    @Test fun liveMenuReadsTheCardsOwnEndpointAndWritesBackItsCapabilities() = runTest {
        val repository = MenuRepository(listOf(card("harbour"), card("studio", channelId = "other")))
        val vm = menuViewModel(repository)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        advanceUntilIdle()
        assertEquals(1, repository.menuCalls)
        assertFalse(vm.uiState.value.menuLoading)
        assertFalse(vm.uiState.value.menuFailed)
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals("token-harbour", page.results.first { it.id == "harbour" }.notInterestedToken)
        assertNull(page.results.first { it.id == "studio" }.notInterestedToken)
        assertEquals(1, repository.menuCalls)
    }

    @Test fun failedMenuReadOffersRetryAndRecoveryClearsIt() = runTest {
        val repository = MenuRepository(listOf(card("harbour"))).apply { menuError = java.io.IOException("offline") }
        val vm = menuViewModel(repository)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.menuFailed)
        assertFalse(vm.uiState.value.menuLoading)
        repository.menuError = null
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.menuFailed)
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals("token-harbour", page.results.first { it.id == "harbour" }.notInterestedToken)
    }

    @Test fun liveFeedbackRemovesTheCardAndOnlyItsOwnChannelCards() = runTest {
        val repository = MenuRepository(listOf(card("harbour"), card("studio"), card("meadow", channelId = "openfield")))
        val vm = menuViewModel(repository)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        advanceUntilIdle()
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.DontRecommend))
        advanceUntilIdle()
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals(listOf("meadow"), page.results.map { it.id })
        assertEquals(1, repository.feedback.size)
        assertEquals("harbour", repository.feedback.single().first.id)
        assertEquals(SearchVideoMenuAction.DontRecommend, repository.feedback.single().second)
        assertEquals(AppNotice.Message("Removed from recommendations"), vm.actionNotices.first())
        assertEquals(SearchOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun liveNotInterestedRemovesOnlyTheTargetCard() = runTest {
        val repository = MenuRepository(listOf(card("harbour"), card("studio")))
        val vm = menuViewModel(repository)
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        advanceUntilIdle()
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.NotInterested))
        advanceUntilIdle()
        val page = (vm.uiState.value.content as SearchContent.Results).page
        assertEquals(listOf("studio"), page.results.map { it.id })
        assertEquals(1, repository.feedback.size)
    }

    @Test fun aMenuCapturedByAnotherSessionIsNeverReadOrWritten() = runTest {
        val repository = MenuRepository(listOf(card("harbour", channelId = "northlight").copy(feedbackGeneration = 9)))
        val vm = menuViewModel(repository) // session generation 2, card captured for 9
        advanceUntilIdle()
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        advanceUntilIdle()
        assertEquals(0, repository.menuCalls)
        assertFalse(vm.uiState.value.menuLoading)
        vm.onAction(SearchAction.OpenItemMenu("harbour"))
        vm.onAction(SearchAction.MenuAction(SearchVideoMenuAction.NotInterested))
        advanceUntilIdle()
        assertTrue(repository.feedback.isEmpty())
    }

}
