package com.hhst.youtubelite.channel

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.channel.data.*
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import com.hhst.youtubelite.post.domain.ChannelPostsPage
import com.hhst.youtubelite.post.domain.PostSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.core.AppNotice

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {
    @Test fun signedInNavigationSurvivesLoadingAndAccountRefresh() = runTest {
        val model = ChannelViewModel(FixtureChannelRepository(), SavedStateHandle(), "blender")
        model.updateSignedIn(true)
        advanceUntilIdle()
        assertTrue(model.uiState.value.signedIn)
        model.onAction(ChannelAction.SelectDestination(ChannelDestination.Subscriptions))
        assertEquals(ChannelNavigationRequest.Destination(ChannelDestination.Subscriptions), model.navigationRequests.first())
        model.updateSignedIn(false)
        model.refreshSession()
        advanceUntilIdle()
        assertFalse(model.uiState.value.signedIn)
    }

    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun aRestoredTabReceivesTheOpenedPageAndLaterReadsUseItsCanonicalChannel() = runTest {
        val base = FixtureChannelRepository()
        val opened = FixtureChannelRepository.samplePage("UCcanonical").copy(videosDeferred = true,
            readReference = object : ChannelReadReference {})
        var received: ChannelPage? = null
        var pagedId: String? = null
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String): ChannelPage {
                assertEquals("@handle", channelId)
                return opened
            }
            override suspend fun videos(channelId: String, opened: ChannelPage): ChannelVideosPage {
                assertEquals("UCcanonical", channelId)
                received = opened
                return ChannelVideosPage(opened.videos, "next")
            }
            override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
                assertEquals("next", continuation)
                pagedId = channelId
                return ChannelVideosPage(emptyList(), null)
            }
        }
        val state = SavedStateHandle(mapOf("tab" to ChannelTab.Videos.name))
        val vm = ChannelViewModel(repository, state, "@handle")
        advanceUntilIdle()
        assertSame(opened, received)
        assertEquals("UCcanonical", vm.uiState.value.channelId)
        assertEquals("UCcanonical", state.get<String>("channelId"))
        vm.onAction(ChannelAction.LoadVideosNext)
        advanceUntilIdle()
        assertEquals("UCcanonical", pagedId)
    }

    private fun repository(block: suspend (String) -> ChannelPage) = object : ChannelRepository {
        override suspend fun channel(channelId: String) = block(channelId)
        override suspend fun shorts(channelId: String) = ChannelShortsPage(emptyList())
        override suspend fun live(channelId: String) = ChannelLivePage()
        override suspend fun posts(channelId: String) = ChannelPostsPage(emptyList())
    }

    @Test fun appendedShortsPagesRenumberLoadedOrderForLocalSorts() = runTest {
        val base = FixtureChannelRepository()
        val repository = object : ChannelRepository by base {
            override suspend fun shorts(channelId: String) = ChannelShortsPage(listOf(
                ChannelShort("s1", "One", "1K views", "a", order = 0),
                ChannelShort("s2", "Two", "2K views", "b", order = 1)), nextKey = "shorts-2")
            override suspend fun shortsNext(channelId: String, cursor: String) = ChannelShortsPage(listOf(
                ChannelShort("s3", "Three", "3K views", "c", order = 0),
                ChannelShort("s4", "Four", "4K views", "d", order = 1)))
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Shorts))
        advanceUntilIdle()
        vm.onAction(ChannelAction.LoadShortsNext)
        advanceUntilIdle()
        val shorts = (vm.uiState.value.shorts as ChannelSection.Ready).value
        assertEquals(listOf("s1", "s2", "s3", "s4"), shorts.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), shorts.map { it.order })
    }

    @Test fun playlistsTabAppendsServerPagesAndStopsOnARepeatedCursor() = runTest {
        val base = FixtureChannelRepository()
        val playlist = { id: String -> com.hhst.youtubelite.playlist.domain.PlaylistSummary(id, "Playlist $id", "Blender",
            "blender", null, "", "", null) }
        val repository = object : ChannelRepository by base {
            override suspend fun channel(channelId: String) = ChannelPage(
                ChannelProfile(channelId = channelId, name = "Blender", handle = "@blender", avatar = "",
                    banner = null, subscribers = "30M subscribers", description = "Studio."),
                emptyList(), playlistsDeferred = true)
            override suspend fun playlists(channelId: String) =
                com.hhst.youtubelite.library.domain.PlaylistCollectionPage(listOf(playlist("p1"), playlist("p2")), "pl-2")
            override suspend fun playlistsNext(channelId: String, cursor: String) =
                com.hhst.youtubelite.library.domain.PlaylistCollectionPage(listOf(playlist("p3")), "pl-2")
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Playlists))
        advanceUntilIdle()
        val rows = (vm.uiState.value.playlists as ChannelSection.Ready).value
        assertEquals(listOf("p1", "p2"), rows.map { it.id })
        assertEquals("pl-2", vm.uiState.value.playlistsNextKey)
        vm.onAction(ChannelAction.LoadPlaylistsNext)
        advanceUntilIdle()
        assertEquals(listOf("p1", "p2", "p3"), (vm.uiState.value.playlists as ChannelSection.Ready).value.map { it.id })
        assertNull(vm.uiState.value.playlistsNextKey)
    }

    @Test fun restoredServerSearchLoadsTheSubmittedQueryAndKeepsTheDraft() = runTest {
        val base = FixtureChannelRepository()
        var searched: String? = null
        val repository = object : ChannelRepository by base {
            override val serverSearch = true
            override suspend fun searchVideos(channelId: String, query: String): ChannelSearchResults {
                searched = query
                return ChannelSearchResults(base.channel(channelId).videos.take(1))
            }
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(mapOf(
            "tab" to "Search", "submittedQuery" to "night walk", "channelQuery" to "new draft")), "blender")
        advanceUntilIdle()
        assertEquals("night walk", searched)
        assertEquals("new draft", vm.uiState.value.searchQuery)
        assertTrue(vm.uiState.value.searchResults is ChannelSection.Ready)
    }

    @Test fun loadsPageForTheRequestedChannelAndKeepsItsIdentity() = runTest {
        var requested: String? = null
        val vm = ChannelViewModel(repository { id ->
            requested = id
            ChannelPage(ChannelProfile(name = "Blender", handle = "@blender", avatar = "bunny",
                banner = "sintel", subscribers = "30M subscribers", description = "Open movie studio."), emptyList())
        }, SavedStateHandle(), "blender")
        advanceUntilIdle()
        assertEquals("blender", requested)
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
    }


    @Test fun restoredIdentityAndLocalTabStayConsistent() = runTest {
        val saved = SavedStateHandle(mapOf("channelId" to "northlight", "tab" to "Shorts"))
        val vm = ChannelViewModel(FixtureChannelRepository(), saved, "ignored")
        advanceUntilIdle()
        assertEquals("northlight", vm.uiState.value.channelId)
        assertEquals(ChannelTab.Shorts, vm.uiState.value.selectedTab)
        assertEquals("northlight", (vm.uiState.value.content as ChannelContent.Ready).page.profile.channelId)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.shorts is ChannelSection.Ready)
    }

    @Test fun allChannelTabsSwitchLocallyAndLoadTheirOwnSection() = runTest {
        val vm = ChannelViewModel(FixtureChannelRepository(), SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Videos))
        assertEquals(ChannelTab.Videos, vm.uiState.value.selectedTab)
        for (tab in listOf(ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts)) {
            vm.onAction(ChannelAction.SelectTab(tab))
            assertEquals(tab, vm.uiState.value.selectedTab)
            advanceUntilIdle()
            when (tab) {
                ChannelTab.Shorts -> assertTrue(vm.uiState.value.shorts is ChannelSection.Ready)
                ChannelTab.Live -> assertTrue(vm.uiState.value.live is ChannelSection.Ready)
                else -> assertTrue(vm.uiState.value.posts is ChannelSection.Ready)
            }
        }
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Playlists))
        assertEquals(ChannelTab.Playlists, vm.uiState.value.selectedTab)
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Home))
        assertEquals(ChannelTab.Home, vm.uiState.value.selectedTab)
    }

    @Test fun selectedTabSurvivesViewModelRecreation() = runTest {
        val saved = SavedStateHandle()
        val vm = ChannelViewModel(FixtureChannelRepository(), saved, "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Videos))
        val restored = ChannelViewModel(FixtureChannelRepository(), saved, "blender")
        assertEquals(ChannelTab.Videos, restored.uiState.value.selectedTab)
    }

    @Test fun channelSearchAndAboutRestoreWithChannelIdentityAndSeparateDraft() = runTest {
        val saved = SavedStateHandle()
        val vm = ChannelViewModel(FixtureChannelRepository(), saved, "northlight")
        advanceUntilIdle()

        vm.onAction(ChannelAction.ChannelSearch)
        assertEquals(ChannelTab.Search, vm.uiState.value.selectedTab)
        assertEquals(ChannelNavigationRequest.ChannelSearch, vm.navigationRequests.first())
        assertEquals("northlight", vm.uiState.value.channelId)

        vm.onAction(ChannelAction.QueryChanged("  night walk  "))
        vm.onAction(ChannelAction.SubmitSearch)
        assertEquals("night walk", vm.uiState.value.submittedQuery)
        vm.onAction(ChannelAction.QueryChanged("  draft only  "))
        vm.onAction(ChannelAction.OpenFullDescription)
        assertEquals(ChannelOverlay.About, vm.uiState.value.overlay)
        assertEquals(ChannelNavigationRequest.FullDescription, vm.navigationRequests.first())

        val restoredSaved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val restored = ChannelViewModel(FixtureChannelRepository(), restoredSaved, "ignored")
        advanceUntilIdle()
        assertEquals("northlight", restored.uiState.value.channelId)
        assertEquals(ChannelTab.Search, restored.uiState.value.selectedTab)
        assertEquals("  draft only  ", restored.uiState.value.searchQuery)
        assertEquals("night walk", restored.uiState.value.submittedQuery)
        assertEquals(ChannelOverlay.About, restored.uiState.value.overlay)

        restored.onAction(ChannelAction.CloseOverlay)
        val closed = ChannelViewModel(FixtureChannelRepository(), restoredSaved, "ignored")
        assertEquals(ChannelOverlay.None, closed.uiState.value.overlay)
        assertEquals(ChannelTab.Search, closed.uiState.value.selectedTab)
    }

    @Test fun playlistSortAndMenuRestoreWithoutLosingTheShareIdentity() = runTest {
        val saved = SavedStateHandle()
        val vm = ChannelViewModel(FixtureChannelRepository(), saved, "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Playlists))
        vm.onAction(ChannelAction.SortPlaylists(ChannelPlaylistSort.LastVideo))
        val id = (vm.uiState.value.content as ChannelContent.Ready).page.playlists.last().id
        vm.onAction(ChannelAction.OpenPlaylistMenu(id))
        val restored = ChannelViewModel(FixtureChannelRepository(), saved, "ignored")
        advanceUntilIdle()
        assertEquals(ChannelTab.Playlists, restored.uiState.value.selectedTab)
        assertEquals(ChannelPlaylistSort.LastVideo, restored.uiState.value.playlistSort)
        assertEquals(ChannelOverlay.PlaylistMenu(id), restored.uiState.value.overlay)
        restored.onAction(ChannelAction.SharePlaylist)
        assertEquals(ChannelNavigationRequest.SharePlaylist(id), restored.navigationRequests.first())
        assertEquals(ChannelOverlay.None, restored.uiState.value.overlay)
        assertEquals(ChannelPlaylistSort.LastVideo, restored.uiState.value.playlistSort)
    }

    @Test fun videoWatchAndHeaderRequestsAreTypedOnly() = runTest {
        val vm = ChannelViewModel(FixtureChannelRepository(), SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.OpenVideo("bunny"))
        assertEquals(ChannelNavigationRequest.Watch("bunny"), vm.navigationRequests.first())
        vm.onAction(ChannelAction.Subscribe)
        assertEquals(ChannelNavigationRequest.Subscribe("blender"), vm.navigationRequests.first())
        vm.onAction(ChannelAction.Share)
        assertEquals(ChannelNavigationRequest.Share, vm.navigationRequests.first())
        vm.onAction(ChannelAction.OpenFullDescription)
        assertEquals(ChannelNavigationRequest.FullDescription, vm.navigationRequests.first())
        assertEquals(ChannelOverlay.About, vm.uiState.value.overlay)
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
    }

    @Test fun failedLoadAndRetryClearTheWholePage() = runTest {
        var fail = true
        val vm = ChannelViewModel(repository { if (fail) error("offline") else FixtureChannelRepository.sampleVideos().let {
            ChannelPage(FixtureChannelRepository.profile(), it)
        } }, SavedStateHandle(), "blender")
        advanceUntilIdle()
        assertEquals(ChannelContent.Failed, vm.uiState.value.content)
        fail = false
        vm.onAction(ChannelAction.Retry)
        assertEquals(ChannelContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
    }

    @Test fun emptyChannelKeepsAReadyPageWithoutVideos() = runTest {
        val vm = ChannelViewModel(FixtureChannelRepository(ChannelScenario.Empty), SavedStateHandle(), "blender")
        advanceUntilIdle()
        val content = vm.uiState.value.content
        assertTrue(content is ChannelContent.Ready)
        assertTrue((content as ChannelContent.Ready).page.videos.isEmpty())
    }

    private class ServerChannelRepository : ChannelRepository {
        val searches = ArrayList<String>()
        val videoRequests = ArrayList<String?>()
        var failSubscribe = false
        override val serverSearch: Boolean get() = true
        override suspend fun channel(channelId: String) = ChannelPage(
            ChannelProfile(channelId = channelId, name = "Blender", handle = "@blender", avatar = "",
                banner = null, subscribers = "30M subscribers", description = "Studio.", subscribed = false),
            listOf(ChannelVideo("v1", "First", "1K views", "1 day", "1:00", "")),
            videosNextKey = "page-2")
        override suspend fun shorts(channelId: String) = ChannelShortsPage(emptyList())
        override suspend fun live(channelId: String) = ChannelLivePage()
        override suspend fun posts(channelId: String) = ChannelPostsPage(emptyList())
        override suspend fun searchVideos(channelId: String, query: String): ChannelSearchResults {
            searches += query
            return ChannelSearchResults(listOf(ChannelVideo("sv1", "Server hit", "2K views", "2 days", "2:00", "")))
        }
        override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
            videoRequests += continuation
            return ChannelVideosPage(listOf(ChannelVideo("v2", "Second", "3K views", "3 days", "3:00", "")), null)
        }
    }

    private class RecordingVideoActions : com.hhst.youtubelite.watch.domain.VideoActions {
        val subscriptions = ArrayList<Pair<String, Boolean>>()
        var error: Exception? = null
        override suspend fun rate(videoId: String, rating: com.hhst.youtubelite.watch.domain.VideoRating, previous: com.hhst.youtubelite.watch.domain.VideoRating) = Unit
        override suspend fun subscribe(channelId: String, subscribed: Boolean) {
            error?.let { throw it }
            subscriptions += channelId to subscribed
        }
    }

    @Test fun subscribeWritesThroughTheServerAndUpdatesTheButton() = runTest {
        val actions = RecordingVideoActions()
        val vm = ChannelViewModel(ServerChannelRepository(), SavedStateHandle(), "blender", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(ChannelAction.Subscribe)
        advanceUntilIdle()
        assertEquals(listOf("blender" to true), actions.subscriptions)
        val page = (vm.uiState.value.content as ChannelContent.Ready).page
        assertEquals(true, page.profile.subscribed)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun failedSubscribeKeepsMembershipAndNotifies() = runTest {
        val actions = RecordingVideoActions().apply { error = com.hhst.youtubelite.watch.domain.SignInRequiredException() }
        val vm = ChannelViewModel(ServerChannelRepository(), SavedStateHandle(), "blender", videoActions = actions)
        advanceUntilIdle()
        vm.onAction(ChannelAction.Subscribe)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as ChannelContent.Ready).page
        assertEquals(false, page.profile.subscribed)
        assertEquals(AppNotice.SignIn, withTimeoutOrNull(500) { vm.actionNotices.first() })
    }

    @Test fun submitSearchHitsTheServerAndRendersResults() = runTest {
        val repository = ServerChannelRepository()
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.QueryChanged("open movie"))
        vm.onAction(ChannelAction.SubmitSearch)
        advanceUntilIdle()
        assertEquals(listOf("open movie"), repository.searches)
        val results = vm.uiState.value.searchResults
        assertTrue(results is ChannelSection.Ready)
        assertEquals(listOf("sv1"), (results as ChannelSection.Ready).value.map { it.id })
    }

    @Test fun videosNextPageAppendsAndClearsTheContinuation() = runTest {
        val repository = ServerChannelRepository()
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.LoadVideosNext)
        advanceUntilIdle()
        assertEquals(listOf("page-2"), repository.videoRequests)
        val page = (vm.uiState.value.content as ChannelContent.Ready).page
        assertEquals(listOf("v1", "v2"), page.videos.map { it.id })
        assertNull(page.videosNextKey)
        assertEquals(com.hhst.youtubelite.home.domain.AppendState.Idle, vm.uiState.value.videosAppend)
    }

    @Test fun deferredTabsLoadOnDemandWithTabLocalFailures() = runTest {
        val repository = object : ChannelRepository {
            var videosFirstReads = 0
            var playlistsReads = 0
            var failPlaylists = true
            override suspend fun channel(channelId: String) = ChannelPage(
                ChannelProfile(channelId = channelId, name = "Blender", handle = "@blender", avatar = "",
                    banner = null, subscribers = "30M subscribers", description = "Studio."),
                emptyList(), videosDeferred = true, playlistsDeferred = true)
            override suspend fun shorts(channelId: String) = ChannelShortsPage(emptyList())
            override suspend fun live(channelId: String) = ChannelLivePage()
            override suspend fun posts(channelId: String) = ChannelPostsPage(emptyList())
            override suspend fun videos(channelId: String, continuation: String?): ChannelVideosPage {
                videosFirstReads++
                return ChannelVideosPage(listOf(ChannelVideo("v9", "Ninth", "9K views", "9 days", "9:00", "")), null)
            }
            override suspend fun playlists(channelId: String): com.hhst.youtubelite.library.domain.PlaylistCollectionPage {
                playlistsReads++
                if (failPlaylists) throw java.io.IOException("playlists offline")
                return com.hhst.youtubelite.library.domain.PlaylistCollectionPage(
                    com.hhst.youtubelite.playlist.data.FixturePlaylistRepository.summaries())
            }
        }
        val vm = ChannelViewModel(repository, SavedStateHandle(), "blender")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
        assertEquals(0, repository.videosFirstReads)
        assertEquals(0, repository.playlistsReads)
        assertEquals(ChannelSection.Loading, vm.uiState.value.videosTab)
        assertEquals(ChannelSection.Loading, vm.uiState.value.playlists)
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Videos))
        advanceUntilIdle()
        assertEquals(1, repository.videosFirstReads)
        val page = (vm.uiState.value.content as ChannelContent.Ready).page
        assertEquals(listOf("v9"), page.videos.map { it.id })
        assertTrue(vm.uiState.value.videosTab is ChannelSection.Ready)
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Playlists))
        advanceUntilIdle()
        assertEquals(1, repository.playlistsReads)
        assertEquals(ChannelSection.Failed, vm.uiState.value.playlists)
        assertTrue(vm.uiState.value.content is ChannelContent.Ready)
        repository.failPlaylists = false
        vm.onAction(ChannelAction.RetrySection(ChannelTab.Playlists))
        advanceUntilIdle()
        assertEquals(2, repository.playlistsReads)
        assertTrue(vm.uiState.value.playlists is ChannelSection.Ready)
        assertEquals(1, repository.videosFirstReads)
    }
}
