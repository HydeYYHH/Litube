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
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun menuVideo(id: String = "video") = FeedItem.Video(id, "Title", "Author", "", "", "", "channel",
        feedbackGeneration = 7, menuEndpoint = FeedMenuEndpoint("menu", id))

    @Test fun homeRecoversMissingChannelBeforeRequestingItsAvatar() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val menu = """{"content":{"engagementPanelSectionListRenderer":{"content":{"listViewModel":{"listItems":[{"listItemViewModel":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCabc"}}}}}]}}}}}"""
        val artwork = "https://yt3.googleusercontent.com/avatar"
        val header = """{"header":{"musicVisualHeaderRenderer":{"foregroundThumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"$artwork"}]}}}}}}"""
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val body = if (chain.request().url.encodedPath.endsWith("get_panel")) menu else header
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("test").body(body.toResponseBody()).build()
        }.build()
        val directory = com.hhst.youtubelite.data.media.MediaAvatarDirectory(com.hhst.youtubelite.data.media.MediaClient(http))
        try {
            val card = menuVideo().copy(channelId = "", avatar = null)
            val repo = object : HomeRepository {
                override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(card))
            }
            val vm = HomeViewModel(repo, SavedStateHandle(), loadOnStart = true, avatars = directory)
            runCurrent()
            runBlocking { withTimeout(5_000) {
                directory.channel(card.id, com.liskovsoft.youtubeapi.videoinfo.V2.MenuEndpoint("menu", card.id))
                directory.avatar("UCabc")
            } }
            runCurrent()
            val row = (vm.uiState.value.content as HomeContent.Feed).page.items.single() as FeedItem.Video
            assertEquals("UCabc", row.channelId)
            assertEquals(artwork, row.avatar)
            assertEquals(2, calls.get())
        } finally { directory.dispose() }
    }

    @Test fun signedInHomeLoadsFeedbackWithoutAnInlineMenuEndpoint() = runTest {
        val card = menuVideo().copy(menuEndpoint = null)
        var reads = 0
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(card))
            override suspend fun menu(video: FeedItem.Video): FeedItem.Video {
                reads++
                return video.copy(notInterestedToken = "ni", dontRecommendToken = "dc")
            }
        }
        val vm = HomeViewModel(repo, SavedStateHandle(), saveRepository = FakeSaveRepository())
        vm.updateSession(true, 7)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        assertEquals(0, reads)
        vm.onAction(HomeAction.OpenMenu(card.id))
        advanceUntilIdle()
        val loaded = (vm.uiState.value.content as HomeContent.Feed).page.items.single() as FeedItem.Video
        assertEquals("ni", loaded.notInterestedToken)
        assertEquals("dc", loaded.dontRecommendToken)
        assertEquals(1, reads)
    }

    private fun menuViewModel(resolve: suspend (FeedItem.Video) -> FeedItem.Video): HomeViewModel {
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(menuVideo(), menuVideo("other")))
            override suspend fun menu(video: FeedItem.Video) = resolve(video)
        }
        return HomeViewModel(repo, SavedStateHandle(), saveRepository = FakeSaveRepository()).also {
            it.updateSession(true, 7)
            it.onAction(HomeAction.Refresh)
        }
    }

    @Test fun menuLoadsOnDemandAndFailureCanRetryWithoutReloadingTheFeed() = runTest {
        var calls = 0
        val vm = menuViewModel { video ->
            calls++
            if (calls == 1) error("offline")
            video.copy(notInterestedToken = "token", dontRecommendToken = "channel-token")
        }
        advanceUntilIdle()
        assertEquals(0, calls)
        vm.onAction(HomeAction.OpenMenu("video"))
        assertTrue((vm.uiState.value.overlay as HomeOverlay.Menu).loading)
        advanceUntilIdle()
        assertTrue((vm.uiState.value.overlay as HomeOverlay.Menu).failed)
        vm.onAction(HomeAction.OpenMenu("video"))
        advanceUntilIdle()
        assertEquals(HomeOverlay.Menu("video"), vm.uiState.value.overlay)
        val rows = (vm.uiState.value.content as HomeContent.Feed).page.items.filterIsInstance<FeedItem.Video>()
        assertEquals("token", rows.first().notInterestedToken)
        assertEquals("channel-token", rows.first().dontRecommendToken)
        assertNull(rows.last().notInterestedToken)
        assertEquals(2, calls)
    }

    @Test fun aLateMenuCannotOverwriteANewerMenuForTheSameVideo() = runTest {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val vm = menuViewModel { video ->
            if (++calls == 1) withContext(NonCancellable) { first.await(); video.copy(notInterestedToken = "old") }
            else video.copy(notInterestedToken = "new")
        }
        advanceUntilIdle()
        vm.onAction(HomeAction.OpenMenu("video"))
        runCurrent()
        vm.onAction(HomeAction.CloseOverlay)
        vm.onAction(HomeAction.OpenMenu("video"))
        runCurrent()
        first.complete(Unit)
        advanceUntilIdle()
        val video = (vm.uiState.value.content as HomeContent.Feed).page.items.first() as FeedItem.Video
        assertEquals("new", video.notInterestedToken)
        assertEquals(HomeOverlay.Menu("video"), vm.uiState.value.overlay)
    }

    @Test fun identityChangesCloseTheMenuAndRejectLateCapabilities() = runTest {
        val ready = CompletableDeferred<Unit>()
        val vm = menuViewModel { video -> withContext(NonCancellable) {
            ready.await(); video.copy(notInterestedToken = "old-account")
        } }
        advanceUntilIdle()
        vm.onAction(HomeAction.OpenMenu("video"))
        runCurrent()
        vm.updateSession(false, 8)
        ready.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        assertNull(((vm.uiState.value.content as HomeContent.Feed).page.items.first() as FeedItem.Video).notInterestedToken)
    }

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

    @Test fun coldStartPaintsTheCapturedSnapshotAndTheLiveLoadReplacesIt() = runTest {
        // Unconfined Main keeps seeding, load and paint synchronous; no scheduler pumping.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dir = java.nio.file.Files.createTempDirectory("content-snapshots").toFile()
        val store = com.hhst.youtubelite.core.ContentSnapshots(dir)
        store.write("home", "visitor", "All", listOf(
            FeedItem.Video("cached-1", "Cached", "Blender", "1K views", "bunny", "9:56", "blender")), FeedItem.Video::class.java)
        val vm = HomeViewModel(repository { _, _ ->
            HomePage(listOf(FeedItem.Video("live-1", "Live", "Blender", "2K views", "bunny", "9:56", "blender")))
        }, SavedStateHandle(), snapshots = store, seedDispatcher = UnconfinedTestDispatcher())
        val seeded = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("cached-1"), seeded.page.items.map { it.id })
        assertTrue(seeded.refreshing)
        assertNull(seeded.page.nextKey)
        vm.onAction(HomeAction.Refresh)
        // The persisted snapshot write hops to real IO; wait for it before reading the store.
        val writeDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < writeDeadline &&
            store.read("home", "visitor", "All", FeedItem.Video::class.java
            )?.map { it.id }?.singleOrNull() != "live-1") Thread.sleep(20)
        val live = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("live-1"), live.page.items.map { it.id })
        assertEquals(listOf("live-1"), store.read("home", "visitor", "All",
            FeedItem.Video::class.java
        )!!.map { it.id })
        dir.deleteRecursively()
    }

    @Test fun appendedShelvesKeepTheirIdentityAndSplitRailsMerge() = runTest {
        fun rail(id: String, videoId: String) = FeedItem.Shorts(id, listOf(
            FeedItem.Video(videoId, videoId, "Blender", "1K views", "bunny", "0:30", "blender")))
        fun row(id: String) = FeedItem.Video(id, id, "Blender", "1K views", "bunny", "9:56", "blender")
        var appended = false
        val vm = HomeViewModel(repository { _, _ ->
            if (appended) HomePage(listOf(rail("shorts-a2", "a2"), row("v2"), rail("shorts-c", "c1")))
            else { appended = true; HomePage(listOf(row("v1"), rail("shorts-a", "a1")), nextKey = "page-2") }
        }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        vm.onAction(HomeAction.LoadNext)
        advanceUntilIdle()
        val items = (vm.uiState.value.content as HomeContent.Feed).page.items
        assertEquals(listOf("v1", "shorts-a", "v2", "shorts-c"), items.map { it.id })
        assertEquals(listOf("a1", "a2"), (items[1] as FeedItem.Shorts).videos.map { it.id })
    }

    @Test fun aUserRefreshNeverRestartsAnInFlightRequest() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var loads = 0
        val vm = HomeViewModel(repository { _, _ ->
            loads++
            gate.await()
            FixtureHomeRepository.samplePage()
        }, SavedStateHandle())
        vm.onAction(HomeAction.Refresh)
        runCurrent()
        assertEquals(1, loads)
        vm.onAction(HomeAction.Refresh)
        runCurrent()
        assertEquals(1, loads)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HomeContent.Feed)
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

    @Test fun confirmedFeedbackRemovesTheRow() = runTest {
        val calls = ArrayList<Pair<String, String?>>()
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(
                FeedItem.Video("a", "A", "Blender", "1 view", "a", "1:00", "blender", notInterestedToken = "token-a", feedbackGeneration = 1),
                FeedItem.Video("b", "B", "Blender", "1 view", "b", "1:00", "blender"),
            ))
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) { calls += video.id to video.notInterestedToken }
        }
        val vm = HomeViewModel(repo, SavedStateHandle(), saveRepository = FakeSaveRepository())
        vm.updateSession(true, 1)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        vm.onAction(HomeAction.OpenMenu("a"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.NotInterested))
        advanceUntilIdle()
        assertEquals(HomeOverlay.None, vm.uiState.value.overlay)
        assertEquals(listOf("b"), (vm.uiState.value.content as HomeContent.Feed).page.items.map { it.id })
        assertEquals(listOf("a" to "token-a"), calls)
        assertEquals(AppNotice.Message("Removed from recommendations"), withTimeoutOrNull(500) { vm.actionNotices.first() })
    }

    @Test fun failedFeedbackKeepsTheRow() = runTest {
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(
                FeedItem.Video("a", "A", "Blender", "1 view", "a", "1:00", "blender", notInterestedToken = "token-a", feedbackGeneration = 1),
            ))
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) { throw java.io.IOException("offline") }
        }
        val vm = HomeViewModel(repo, SavedStateHandle(), saveRepository = FakeSaveRepository())
        vm.updateSession(true, 1)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        vm.onAction(HomeAction.OpenMenu("a"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.NotInterested))
        advanceUntilIdle()
        assertEquals(listOf("a"), (vm.uiState.value.content as HomeContent.Feed).page.items.map { it.id })
        val notice = withTimeoutOrNull(500) { vm.actionNotices.first() }
        assertTrue(notice is AppNotice.Failure)
        assertEquals("Couldn't update recommendations. Try again.", (notice as AppNotice.Failure).text)
        assertTrue(notice.report.text.contains("Outcome: unexpected:IOException"))
    }

    @Test fun guestNotInterestedStillOpensTheReasonSheetWithoutWriting() = runTest {
        val vm = HomeViewModel(FixtureHomeRepository(), SavedStateHandle(), HomeScenario.Feed)
        vm.onAction(HomeAction.OpenMenu("bunny"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.NotInterested))
        assertEquals(HomeOverlay.Feedback("bunny", HomeFeedbackKind.NotInterested), vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        assertEquals(FixtureHomeRepository.samplePage().items, (vm.uiState.value.content as HomeContent.Feed).page.items)
    }

    @Test fun repeatedFeedbackClicksSendOneRequestAndStaleResultsNeverLand() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(
                FeedItem.Video("a", "A", "Blender", "1 view", "a", "1:00", "blender", notInterestedToken = "token-a", feedbackGeneration = 1),
            ))
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) {
                calls++
                gate.await()
            }
        }
        val vm = HomeViewModel(repo, SavedStateHandle(), saveRepository = FakeSaveRepository())
        vm.updateSession(true, 1)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()
        vm.onAction(HomeAction.OpenMenu("a"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.NotInterested))
        vm.onAction(HomeAction.OpenMenu("a"))
        vm.onAction(HomeAction.MenuSelected(FeedMenuAction.NotInterested))
        runCurrent()
        assertEquals(1, calls)
        vm.updateSession(true, 2)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf("a"), (vm.uiState.value.content as HomeContent.Feed).page.items.map { it.id })
    }

    @Test fun productionMenusOfferOnlyRowsWithARealRoute() {
        assertEquals(listOf(FeedMenuAction.Queue, FeedMenuAction.Download, FeedMenuAction.WatchLater, FeedMenuAction.SaveToPlaylist, FeedMenuAction.Share), FeedMenuKind.Video.productionActions())
        assertEquals(listOf(FeedMenuAction.Share), FeedMenuKind.Mix.productionActions())
        assertTrue(FeedMenuKind.Short.productionActions().isEmpty())
        assertTrue(FeedMenuKind.Shorts.productionActions().isEmpty())
        assertTrue(FeedMenuKind.Post.productionActions().isEmpty())
        assertEquals(FeedMenuKind.Video.actions, FeedMenuKind.Video.visibleActions(false))
        assertEquals(FeedMenuKind.Video.productionActions(), FeedMenuKind.Video.visibleActions(true))
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

    @Test fun trafficChallengeKeepsTheFeedAndReportsVerification() = runTest {
        var challenged = false
        val vm = HomeViewModel(repository { _, _ ->
            if (challenged) FixtureHomeRepository.samplePage()
            else {
                challenged = true
                throw com.hhst.youtubelite.data.media.MediaException(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                        com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION,
                        "traffic_challenge", 302, detail = "https://www.google.com/sorry/index?q=x"))
            }
        }, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        // First load is challenged before any feed exists: the card state wins, no fake feed.
        assertEquals(HomeContent.Failed, vm.uiState.value.content)
        assertEquals("https://www.google.com/sorry/index?q=x", vm.uiState.value.verification?.challengeUrl)
        vm.verificationCompleted()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HomeContent.Feed)
        assertNull(vm.uiState.value.verification)
    }

    @Test fun ordinaryFailuresNeverReportVerification() = runTest {
        val vm = HomeViewModel(repository { _, _ ->
            throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.HTTP, "http_429", 429))
        }, SavedStateHandle(), loadOnStart = true)
        advanceUntilIdle()
        assertEquals(HomeContent.Failed, vm.uiState.value.content)
        assertNull(vm.uiState.value.verification)
    }

    @Test fun aNewAccountInvalidatesVerificationAndItsLateResult() = runTest {
        var calls = 0
        val url = "https://www.google.com/sorry/index?q=old-account"
        val vm = HomeViewModel(repository { _, _ ->
            if (++calls == 1) throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION, "traffic_challenge", detail = url))
            FixtureHomeRepository.samplePage()
        }, SavedStateHandle(), loadOnStart = true)
        vm.updateSession(true, 1)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.verification)
        vm.updateSession(true, 2)
        advanceUntilIdle()
        assertNull(vm.uiState.value.verification)
        vm.verificationCompleted(url)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun asynchronousSnapshotPaintsWhileAutomaticLoadIsPending() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("home-seed").toFile()
        val store = com.hhst.youtubelite.core.ContentSnapshots(directory)
        val row = FeedItem.Video("cached", "Cached", "Author", "1K views", "bunny", "1:00", "blender")
        store.write("home", "visitor", "All", listOf(row), FeedItem.Video::class.java)
        val response = kotlinx.coroutines.CompletableDeferred<HomePage>()
        try {
            val vm = HomeViewModel(repository { _, _ -> response.await() }, SavedStateHandle(), loadOnStart = true,
                snapshots = store, seedDispatcher = StandardTestDispatcher(testScheduler))
            runCurrent()
            assertEquals(listOf(row), (vm.uiState.value.content as HomeContent.Feed).page.items)
            response.complete(HomePage(emptyList()))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.content is HomeContent.Empty)
        } finally { directory.deleteRecursively() }
    }

}
