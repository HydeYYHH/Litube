package com.hhst.youtubelite.search

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.data.media.MediaAvatarDirectory
import com.hhst.youtubelite.data.media.MediaClient
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLoadingPerformanceTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun row(id: String) = SearchResult(id, "Title", "Author", "UCauthor", "", "", "", "cover")
    private fun SearchResultsViewModel.page() = (uiState.value.content as SearchContent.Results).page

    @Test fun aFlingCancelsOldMetadataAndScrollingBackCanRepairThoseRows() = runTest {
        val rows = (0..29).map { row("video$it") }
        val requested = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var hold = true
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, rows)
            override suspend fun metadata(result: SearchResult): SearchResult {
                requested += result.id
                if (hold) try { awaitCancellation() } finally { cancelled += result.id }
                return result.copy(duration = "1:00", age = "Today", views = "1K views")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        runCurrent()
        assertEquals(rows.take(3).map { it.id }, requested)
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), rows.takeLast(3).map { it.id }))
        runCurrent()
        assertEquals(rows.take(3).map { it.id }.toSet(), cancelled.toSet())
        assertEquals(6, requested.size)
        hold = false
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), rows.take(3).map { it.id }))
        advanceUntilIdle()
        assertTrue(vm.page().results.take(3).all { it.duration == "1:00" && it.views == "1K views" })
        assertEquals(9, requested.size)
    }

    @Test fun metadataFailureWaitsForViewportReentryAndKeepsAlreadyValidFields() = runTest {
        val partial = row("video").copy(age = "Yesterday", avatar = "avatar", notInterestedToken = "capability")
        var calls = 0
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(partial))
            override suspend fun metadata(result: SearchResult): SearchResult {
                if (++calls == 1) throw java.io.IOException("temporary")
                return result.copy(channel = "Wrong", age = "Old", duration = "1:23", views = "2K views", avatar = "")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        assertEquals(1, calls)
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), emptyList()))
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf("video")))
        advanceUntilIdle()
        val repaired = vm.page().results.single()
        assertEquals(2, calls)
        assertEquals("2K views", repaired.views)
        assertEquals("1:23", repaired.duration)
        assertEquals("Author", repaired.channel)
        assertEquals("Yesterday", repaired.age)
        assertEquals("avatar", repaired.avatar)
        assertEquals("capability", repaired.notInterestedToken)
    }

    @Test fun duplicateContinuationRowsCompleteMissingFieldsWithoutErasingGoodValues() = runTest {
        val partial = row("video").copy(duration = "1:23", avatar = "avatar")
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(partial), nextCursor = "next")
            override suspend fun next(query: String, filters: SearchFilters, cursor: String) =
                SearchPage(query, listOf(partial.copy(duration = "", avatar = "", views = "3K views", age = "Today")))
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        val result = vm.page().results.single()
        assertEquals("3K views", result.views)
        assertEquals("Today", result.age)
        assertEquals("1:23", result.duration)
        assertEquals("avatar", result.avatar)
    }

    @Test fun staleViewportEventsCannotHydrateAnotherSearch() = runTest {
        val requested = mutableListOf<String>()
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, (0..9).map { row("$query-$it") })
            override suspend fun metadata(result: SearchResult): SearchResult { requested += result.id; return result }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "old")
        advanceUntilIdle()
        vm.onAction(SearchAction.SubmitSearch("new"))
        advanceUntilIdle()
        vm.onAction(SearchAction.VisibleResults("old", SearchFilters(), listOf("new-9")))
        advanceUntilIdle()
        assertFalse("new-9" in requested)
    }

    @Test fun repeatedVisibleEventsDoNotLoopOnFailureButReentryCanRetry() = runTest {
        val rows = listOf(row("video"), row("other").copy(views = "1K views", age = "Today", duration = "1:00"))
        var calls = 0
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, rows)
            override suspend fun metadata(result: SearchResult): SearchResult {
                if (++calls == 1) throw java.io.IOException("temporary")
                return result.copy(views = "1K views", age = "Today", duration = "1:00")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        assertEquals(1, calls)
        repeat(5) { vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf("video"))) }
        advanceUntilIdle()
        assertEquals(1, calls)
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf("other")))
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf("video")))
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("1K views", vm.page().results.first().views)
    }

    @Test fun liveCardsRepairRealFieldsWithoutRequestingANonexistentDuration() = runTest {
        val complete = row("complete").copy(badge = "LIVE", views = "1K watching")
        val partial = row("partial").copy(badge = "LIVE", channelId = "")
        val requested = mutableListOf<String>()
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(complete, partial))
            override suspend fun metadata(result: SearchResult): SearchResult {
                requested += result.id
                return result.copy(channelId = "UCauthor", views = "2K watching")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        assertEquals(listOf("partial"), requested)
        assertEquals("2K watching", vm.page().results.last().views)
        assertEquals("", vm.page().results.last().duration)
    }

    @Test fun shortsFilterHydratesItsGridAndLaterVisibleRowsWithoutRequestingAnAge() = runTest {
        val rows = (0..9).map { row("short$it") }
        val requested = mutableListOf<String>()
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, emptyList(), shorts = rows)
            override suspend fun metadata(result: SearchResult): SearchResult {
                requested += result.id
                return result.copy(views = "1K views", duration = "0:30")
            }
        }
        val filters = SearchFilters(type = SearchResultType.Shorts)
        val vm = SearchResultsViewModel(repository, SavedStateHandle(mapOf("filterType" to "Shorts")), "q")
        advanceUntilIdle()
        assertEquals(rows.take(3).map { it.id }, requested)
        vm.onAction(SearchAction.VisibleResults("q", filters, listOf("short9")))
        advanceUntilIdle()
        assertEquals(4, requested.size)
        assertEquals("1K views", vm.page().shorts.last().views)
        assertEquals("0:30", vm.page().shorts.last().duration)
    }

    @Test fun aSparseSuccessfulResponseWaitsForReentryBeforeRepairingTheRemainingFields() = runTest {
        var calls = 0
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(row("video")))
            override suspend fun metadata(result: SearchResult): SearchResult {
                calls++
                return if (calls == 1) result.copy(duration = "1:00") else result.copy(age = "Today", views = "1K views")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        repeat(5) { vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf("video"))) }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("1:00", vm.page().results.single().duration)
        assertEquals("", vm.page().results.single().views)
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), emptyList()))
        vm.onAction(SearchAction.VisibleResults("q", SearchFilters(), listOf("video")))
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("1:00", vm.page().results.single().duration)
        assertEquals("1K views", vm.page().results.single().views)
        assertEquals("Today", vm.page().results.single().age)
    }

    @Test fun aLaterPageUsesTheAvatarAlreadyResolvedForTheSameChannel() = runTest {
        var requests = 0
        val body = """{"header":{"musicVisualHeaderRenderer":{"foregroundThumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3/avatar.png"}]}}}}}}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("67", chain.request().header("X-Youtube-Client-Name"))
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val directory = MediaAvatarDirectory(MediaClient(http))
        try {
            assertEquals("https://yt3/avatar.png", directory.avatar("UCauthor"))
            fun complete(id: String) = row(id).copy(views = "1K views", age = "Today", duration = "1:00")
            val repository = object : SearchRepository {
                override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, listOf(complete("first")), nextCursor = "next")
                override suspend fun next(query: String, filters: SearchFilters, cursor: String) = SearchPage(query, listOf(complete("second")))
            }
            val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q", avatars = directory)
            advanceUntilIdle()
            assertEquals("https://yt3/avatar.png", vm.page().results.single().avatar)
            vm.onAction(SearchAction.LoadMore)
            advanceUntilIdle()
            assertEquals("https://yt3/avatar.png", vm.page().results.last().avatar)
            assertEquals(1, requests)
        } finally { directory.dispose() }
    }

    @Test fun emptySearchPagesPauseAutomaticReadsAndOneManualPageCanRecover() = runTest {
        var calls = 0
        val complete = row("recovered").copy(views = "1K views", age = "Today", duration = "1:00")
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters): SearchPage {
                calls++
                return SearchPage(query, emptyList(), nextCursor = "cursor$calls")
            }
            override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage {
                calls++
                return if (calls == 5) SearchPage(query, listOf(complete)) else SearchPage(query, emptyList(), nextCursor = "cursor$calls")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        repeat(10) { vm.onAction(SearchAction.AutomaticLoadMore); advanceUntilIdle() }
        val paused = vm.uiState.value.content as SearchContent.Results
        assertEquals(4, calls)
        assertEquals("cursor4", paused.page.nextCursor)
        assertTrue(paused.automaticPagingPaused)
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        val recovered = vm.uiState.value.content as SearchContent.Results
        assertEquals(5, calls)
        assertEquals(listOf(complete), recovered.page.results)
        assertFalse(recovered.automaticPagingPaused)
        assertNull(recovered.page.nextCursor)
    }

    @Test fun duplicateSearchPagesPauseEvenWhenTheirMetadataChangesAndManualReadAddsRows() = runTest {
        var calls = 0
        val complete = row("existing").copy(views = "1K views", age = "Today", duration = "1:00")
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters): SearchPage {
                calls++
                return SearchPage(query, listOf(complete), nextCursor = "cursor$calls")
            }
            override suspend fun next(query: String, filters: SearchFilters, cursor: String): SearchPage {
                calls++
                return SearchPage(query, if (calls == 5) listOf(complete.copy(id = "new")) else listOf(complete.copy(views = "$calls views")),
                    nextCursor = if (calls == 5) null else "cursor$calls")
            }
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        repeat(10) { vm.onAction(SearchAction.AutomaticLoadMore); advanceUntilIdle() }
        assertEquals(4, calls)
        assertEquals(listOf(complete), vm.page().results)
        assertTrue((vm.uiState.value.content as SearchContent.Results).automaticPagingPaused)
        vm.onAction(SearchAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("existing", "new"), vm.page().results.map { it.id })
        assertFalse((vm.uiState.value.content as SearchContent.Results).automaticPagingPaused)
        assertNull(vm.page().nextCursor)
    }

    @Test fun theFinalEmptySearchContinuationShowsAnEmptyResultInsteadOfABlankList() = runTest {
        val repository = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, emptyList(), nextCursor = "next")
            override suspend fun next(query: String, filters: SearchFilters, cursor: String) = SearchPage(query, emptyList())
        }
        val vm = SearchResultsViewModel(repository, SavedStateHandle(), "q")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SearchContent.Results)
        vm.onAction(SearchAction.AutomaticLoadMore)
        advanceUntilIdle()
        assertEquals(SearchContent.Empty("q"), vm.uiState.value.content)
    }
}
