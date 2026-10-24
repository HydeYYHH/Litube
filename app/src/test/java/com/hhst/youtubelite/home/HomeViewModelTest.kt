package com.hhst.youtubelite.home

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.data.*
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun menuKindsRejectActionsThatDoNotBelongToTheContent() = runTest {
        val vm = HomeViewModel(repository { _, _ -> HomePage(emptyList()) }, SavedStateHandle())
        for (kind in FeedMenuKind.entries) {
            vm.onAction(HomeAction.OpenMenu("item", kind))
            val invalid = FeedMenuAction.entries.first { it !in kind.actions }
            vm.onAction(HomeAction.MenuSelected(invalid))
            assertEquals(HomeOverlay.Menu("item", kind), vm.uiState.value.overlay)
            assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
            vm.onAction(HomeAction.CloseOverlay)
            assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        }
    }

    @Test fun failedInitialRequestCanRecoverWithTheSameFilter() = runTest {
        var fail = true
        val filters = mutableListOf<String>()
        val vm = HomeViewModel(repository { filter, _ ->
            filters += filter
            if (fail) error("offline")
            FixtureHomeRepository.samplePage()
        }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(HomeContent.Failed, vm.uiState.value.content)
        fail = false
        vm.onAction(HomeAction.Refresh)
        assertEquals(HomeContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HomeContent.Feed)
        assertEquals(listOf("All", "All"), filters)
    }

    @Test fun shortsColumnsRespectTheNarrowShelfBoundary() {
        listOf(320f to 2, 527f to 2, 528f to 2, 599f to 2, 600f to 4, 993f to 4,
            994f to 6, 1319f to 6, 1320f to 8, 1645f to 8, 1646f to 10, 1971f to 10, 1972f to 12)
            .forEach { (width, count) -> assertEquals("width=$width", count, HomeLayout.shortsColumns(width)) }
    }

    private fun repository(block: suspend (String, String?) -> HomePage) = object : HomeRepository {
        override suspend fun loadHome(filter: String, continuation: String?) = block(filter, continuation)
    }

    private class FakeSaveRepository(
        var choicesResult: List<com.hhst.youtubelite.saves.domain.SaveChoice> = listOf(
            com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false),
        ),
        var choicesError: Exception? = null,
        var writeError: Exception? = null,
    ) : com.hhst.youtubelite.saves.domain.SaveRepository {
        val writes = ArrayList<Triple<String, String, Boolean>>()
        var watchLaterCalls = 0
        override suspend fun choices(videoId: String) = choicesError?.let { throw it } ?: choicesResult
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {
            writeError?.let { throw it }
            writes.add(Triple(playlistId, videoId, contains))
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

    private fun liveSaveViewModel(saves: FakeSaveRepository): HomeViewModel =
        HomeViewModel(repository { _, _ -> FixtureHomeRepository.samplePage() }, SavedStateHandle(), saveRepository = saves)

    @Test fun liveSaveSheetLoadsChoicesAndTogglesThroughTheServer() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveSaveViewModel(saves)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        val loaded = vm.uiState.value.overlay as HomeOverlay.Save
        assertTrue(loaded.live)
        assertEquals(listOf("WL"), loaded.choices!!.map { it.id })
        vm.onAction(HomeAction.SaveChoiceToggled(loaded.choices!![0]))
        advanceUntilIdle()
        assertEquals(listOf(Triple("WL", "bunny", true)), saves.writes)
        assertTrue((vm.uiState.value.overlay as HomeOverlay.Save).choices!![0].contains)
    }

    @Test fun failedChoicesOfferRetryAndKeepTheSheetOpen() = runTest {
        val saves = FakeSaveRepository(choicesError = java.io.IOException("offline"))
        val vm = liveSaveViewModel(saves)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        val failed = vm.uiState.value.overlay as HomeOverlay.Save
        assertTrue(failed.choicesFailed)
        saves.choicesError = null
        vm.onAction(HomeAction.SaveChoicesRetry)
        advanceUntilIdle()
        val retried = vm.uiState.value.overlay as HomeOverlay.Save
        assertFalse(retried.choicesFailed)
        assertEquals(listOf("WL"), retried.choices!!.map { it.id })
    }

    @Test fun guestSaveClosesTheSheetAndRequestsSignIn() = runTest {
        val saves = FakeSaveRepository(choicesError = com.hhst.youtubelite.watch.domain.SignInRequiredException())
        val vm = liveSaveViewModel(saves)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        assertEquals(AppNotice.SignIn, withTimeoutOrNull(500) { vm.actionNotices.first() })
    }

    @Test fun watchLaterMenuRowWritesThroughTheRepositoryInLiveMode() = runTest {
        val saves = FakeSaveRepository()
        val vm = liveSaveViewModel(saves)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(1, saves.watchLaterCalls)
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun firstVisitDoesNotLoadRecommendations() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository { _, _ -> calls++; FixtureHomeRepository.samplePage() }, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(0, calls)
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
        assertFalse(vm.uiState.value.signedInPreview)
    }

    @Test fun successfulEmptyReplyPreservesServerMessageWithoutFallback() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val vm = HomeViewModel(repository { filter, cursor -> requests += filter to cursor; HomePage(emptyList(), listOf("Search for something you love")) }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(listOf("All" to null), requests)
        assertEquals("Search for something you love", (vm.uiState.value.content as HomeContent.Empty).title)
    }

    @Test fun requestFailureIsNotAnEmptyFeed() = runTest {
        val vm = HomeViewModel(repository { _, _ -> error("offline") }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(HomeContent.Failed, vm.uiState.value.content)
    }

    @Test fun failedAppendKeepsVisibleItemsAndCanRetrySameCursor() = runTest {
        var fail = true
        val cursors = mutableListOf<String?>()
        val original = FixtureHomeRepository.samplePage()
        val vm = HomeViewModel(repository { _, cursor ->
            cursors += cursor
            if (fail) error("offline")
            original.copy(nextKey = null)
        }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        assertEquals(original.items, (vm.uiState.value.content as HomeContent.Feed).page.items)
        assertEquals(AppendState.Failed, (vm.uiState.value.content as HomeContent.Feed).append)
        fail = false
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("fixture-page-2", "fixture-page-2"), cursors)
        assertEquals(original.items, feed.page.items)
        assertNull(feed.page.nextKey)
    }

    @Test fun refreshFailureIsNotPresentedAsPaginationFailure() = runTest {
        val vm = HomeViewModel(repository { _, _ -> error("offline") }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertTrue(feed.refreshFailed)
        assertFalse(feed.refreshing)
        assertEquals(AppendState.Idle, feed.append)
    }

    @Test fun lateFilterReplyCannotReplaceNewerSelection() = runTest {
        val vm = HomeViewModel(repository { filter, _ ->
            withContext(NonCancellable) { delay(if (filter == "Music") 100 else 10) }
            HomePage(listOf(FeedItem.Mix(filter, filter, "", "bunny", ThumbnailPalette(0, 0))))
        }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.SelectChip("Music"))
        runCurrent()
        vm.onAction(HomeAction.SelectChip("Live"))
        advanceUntilIdle()
        assertEquals("Live", vm.uiState.value.selectedChip)
        assertEquals("Live", (vm.uiState.value.content as HomeContent.Feed).page.items.single().id)
    }

    @Test fun queryAndOpenSearchSurviveSavedStateReconstruction() {
        val handle = SavedStateHandle()
        val vm = HomeViewModel(FixtureHomeRepository(), handle)
        vm.onAction(HomeAction.QueryChanged("nature"))
        vm.onAction(HomeAction.OpenSearch)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = HomeViewModel(FixtureHomeRepository(), restoredHandle)
        assertEquals("nature", restored.uiState.value.query)
        assertEquals(HomeOverlay.Search, restored.uiState.value.overlay)
        restored.onAction(HomeAction.CloseOverlay)
        assertEquals("nature", restored.uiState.value.query)
    }

    @Test fun searchEmitsTypedRequestWithoutInventingResults() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle())
        vm.onAction(HomeAction.SubmitSearch("  nature  "))
        assertEquals(HomeNavigationRequest.Search("nature"), vm.navigationRequests.first())
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
    }

    @Test fun blankSearchDoesNotEmitNavigation() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle())
        vm.onAction(HomeAction.SubmitSearch("   "))
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun menuEmitsExactItemAndActionWithoutWritingAnAccount() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.WatchLater))
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        assertEquals(HomeNavigationRequest.MenuAction("bunny", FeedMenuAction.WatchLater), vm.navigationRequests.first())
        assertEquals(FixtureHomeRepository.samplePage(), (vm.uiState.value.content as HomeContent.Feed).page)
    }

    @Test fun saveToPlaylistIsLocalUntilTheTargetIsChosen() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        assertEquals(HomeOverlay.Save("bunny"), vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(HomeAction.SaveDestinationSelected(
            com.hhst.youtubelite.saves.domain.SaveDestination.Playlist("LL", "Liked videos")))
        assertEquals(HomeNavigationRequest.SaveToPlaylist("bunny", "LL", "Liked videos"), vm.navigationRequests.first())
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        vm.onAction(HomeAction.OpenNewPlaylist)
        vm.onAction(HomeAction.SaveDraftChanged("Road trip", ""))
        vm.onAction(HomeAction.SubmitNewPlaylist)
        assertEquals(HomeNavigationRequest.CreatePlaylist("bunny", "Road trip", ""), vm.navigationRequests.first())
        assertEquals(FixtureHomeRepository.samplePage(), (vm.uiState.value.content as HomeContent.Feed).page)
    }

    @Test fun doubleAppendDoesNotStartDuplicateRequests() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository { _, _ -> calls++; delay(100); FixtureHomeRepository.samplePage().copy(nextKey = null) }, SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.LoadNext)
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun menuFollowsAspectRatioAndWidthBoundaries() {
        listOf(
            Triple(390f, 844f, 374f), Triple(500f, 844f, 484f), Triple(600f, 960f, 584f),
            Triple(840f, 1000f, 824f), Triple(1280f, 800f, 400f),
            Triple(415f, 280f, 399f), Triple(416f, 288f, 400f), Triple(520f, 360f, 400f), Triple(520f, 360.5f, 504f),
            Triple(840f, 600f, 824f), Triple(930f, 900f, 914f), Triple(931f, 900f, 400f),
        ).forEach { (width, height, expected) -> assertEquals("$width × $height", expected, HomeLayout.menuWidth(width, height), 0.01f) }
    }

    @Test fun websiteColumnBoundariesDoNotDependOnOrientation() {
        val boundaries = listOf(528f, 994f, 1320f, 1646f, 1972f)
        boundaries.forEachIndexed { index, width ->
            assertEquals(index + 1, HomeLayout.columns(width - 0.5f))
            assertEquals(index + 2, HomeLayout.columns(width))
            assertEquals(index + 2, HomeLayout.columns(width + 0.5f))
        }
        assertEquals(listOf(1, 1, 1, 2, 2, 3, 4), listOf(320f, 390f, 411f, 600f, 840f, 1280f, 1600f).map(HomeLayout::columns))
    }

    @Test fun newPlaylistDraftRestoresWithItsVideoIdentity() = runTest {
        val saved = SavedStateHandle()
        val vm = HomeViewModel(FixtureHomeRepository(HomeScenario.Feed), saved, HomeScenario.Feed)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        vm.onAction(HomeAction.OpenNewPlaylist)
        vm.onAction(HomeAction.SaveDraftChanged("Films", "Weekend"))
        val restored = HomeViewModel(FixtureHomeRepository(HomeScenario.Feed), saved, HomeScenario.Feed)
        assertEquals(HomeOverlay.Save("bunny", true, "Films", "Weekend"), restored.uiState.value.overlay)
    }
    @Test fun changedAccountSessionReloadsOnceEvenWhenBothSessionsAreSignedIn() = runTest {
        var calls = 0
        val vm = HomeViewModel(repository { _, _ -> calls++; FixtureHomeRepository.samplePage() }, SavedStateHandle(), HomeScenario.Feed)
        vm.updateSession(true, 1L)
        advanceUntilIdle()
        assertEquals(0, calls)
        vm.onAction(HomeAction.QueryChanged("preserved query"))
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("preserved query", vm.uiState.value.query)
        assertTrue(vm.uiState.value.signedInPreview)
    }


}
