package com.hhst.youtubelite.channel

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.channel.data.*
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String) -> ChannelPage) = object : ChannelRepository {
        override suspend fun channel(channelId: String) = block(channelId)
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
        assertEquals(ChannelTab.Home, vm.uiState.value.selectedTab)
        assertEquals("northlight", (vm.uiState.value.content as ChannelContent.Ready).page.profile.channelId)
    }

    @Test fun implementedTabsSwitchLocallyAndOtherTabsStayRequests() = runTest {
        val vm = ChannelViewModel(FixtureChannelRepository(), SavedStateHandle(), "blender")
        advanceUntilIdle()
        vm.onAction(ChannelAction.SelectTab(ChannelTab.Videos))
        assertEquals(ChannelTab.Videos, vm.uiState.value.selectedTab)
        for (tab in listOf(ChannelTab.Shorts, ChannelTab.Live, ChannelTab.Posts)) {
            vm.onAction(ChannelAction.SelectTab(tab))
            assertEquals(ChannelNavigationRequest.OtherTab(tab), vm.navigationRequests.first())
            assertEquals("Selecting ${tab.name} must not switch the local view", ChannelTab.Videos, vm.uiState.value.selectedTab)
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
}
