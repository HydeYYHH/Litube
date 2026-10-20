package com.hhst.youtubelite.watch

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.data.WatchScenario
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchViewModel
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
class WatchViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (String) -> WatchPage) = object : WatchRepository {
        override suspend fun watch(videoId: String) = block(videoId)
    }

    @Test fun ordinaryEntryLoadsTheLocalPageForTheRequestedVideo() = runTest {
        val ids = mutableListOf<String>()
        val vm = WatchViewModel(repository { id -> ids += id; FixtureWatchRepository.samplePage(id) }, SavedStateHandle(), "bunny")
        assertEquals(WatchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(listOf("bunny"), ids)
        val page = (vm.uiState.value.content as WatchContent.Ready).page
        assertEquals("bunny", page.video.id)
        assertEquals(FixtureWatchRepository.samplePage("bunny").related, page.related)
        assertTrue(page.related.none { it.id == "bunny" })
    }

    @Test fun failedRequestKeepsLoadingOutAndRetryRecovers() = runTest {
        var fail = true
        val vm = WatchViewModel(repository { id ->
            if (fail) error("offline")
            FixtureWatchRepository.samplePage(id)
        }, SavedStateHandle(), "bunny")
        advanceUntilIdle()
        assertEquals(WatchContent.Failed, vm.uiState.value.content)
        fail = false
        vm.onAction(WatchAction.Retry)
        assertEquals(WatchContent.Loading, vm.uiState.value.content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
    }

    @Test fun pinnedScenariosDoNotCallTheRepository() = runTest {
        var calls = 0
        for (scenario in listOf(WatchScenario.Loading, WatchScenario.Error)) {
            val vm = WatchViewModel(repository { calls++; FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", scenario)
            advanceUntilIdle()
            assertEquals(scenario.initialContent(), vm.uiState.value.content)
        }
        assertEquals(0, calls)
    }

    @Test fun everyActionEmitsOnlyATypedRequest() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        val expectations: List<Pair<WatchAction, WatchNavigationRequest>> = listOf(
            WatchAction.VoiceSearch to WatchNavigationRequest.VoiceSearch,
            WatchAction.OpenPageMenu to WatchNavigationRequest.PageMenu,
            WatchAction.Like to WatchNavigationRequest.Like("harbour"),
            WatchAction.Dislike to WatchNavigationRequest.Dislike("harbour"),
            WatchAction.Download to WatchNavigationRequest.Download("harbour"),
            WatchAction.Queue to WatchNavigationRequest.Queue("harbour"),
            WatchAction.OpenWith to WatchNavigationRequest.OpenWith("harbour"),
            WatchAction.Share to WatchNavigationRequest.Share("harbour"),
            WatchAction.Save to WatchNavigationRequest.Save("harbour"),
            WatchAction.Report to WatchNavigationRequest.Report("harbour"),
            WatchAction.Subscribe to WatchNavigationRequest.Subscribe("northlight"),
            WatchAction.OpenChannel("studio") to WatchNavigationRequest.Channel("studio"),
            WatchAction.OpenComments("harbour") to WatchNavigationRequest.Comments("harbour"),
            WatchAction.OpenRelated("meadow") to WatchNavigationRequest.Watch("meadow"),
        )
        for ((action, expected) in expectations) {
            vm.onAction(action)
            assertEquals(expected, vm.navigationRequests.first())
            assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        }
        // Every emission is a request only: the page itself never changed.
        assertTrue(vm.uiState.value.content is WatchContent.Ready)
        assertEquals("harbour", vm.uiState.value.videoId)
    }

    @Test fun relatedCardMenuIsPageStateAndItsShareRowIsTheOnlyExit() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu("studio"))
        assertEquals(WatchOverlay.ItemMenu("studio"), vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.Share))
        assertEquals(WatchNavigationRequest.Share("studio"), vm.navigationRequests.first())
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
        vm.onAction(WatchAction.OpenItemMenu("meadow"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.WatchLater))
        assertEquals(WatchNavigationRequest.MenuAction("meadow", WatchVideoMenuAction.WatchLater), vm.navigationRequests.first())
        vm.onAction(WatchAction.OpenItemMenu("orbit"))
        vm.onAction(WatchAction.CloseOverlay)
        assertEquals(WatchOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun relatedCardMenuRestoresThroughSavedState() = runTest {
        val handle = SavedStateHandle()
        val vm = WatchViewModel(FixtureWatchRepository(), handle, "harbour")
        advanceUntilIdle()
        vm.onAction(WatchAction.OpenItemMenu("studio"))
        var restored = WatchViewModel(
            FixtureWatchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(WatchOverlay.ItemMenu("studio"), restored.uiState.value.overlay)
        vm.onAction(WatchAction.CloseOverlay)
        restored = WatchViewModel(
            FixtureWatchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "ignored",
        )
        assertEquals(WatchOverlay.None, restored.uiState.value.overlay)
    }

    @Test fun subscribeWithoutLoadedContentEmitsNothing() = runTest {
        val vm = WatchViewModel(repository { FixtureWatchRepository.samplePage(it) }, SavedStateHandle(), "bunny", WatchScenario.Loading)
        vm.onAction(WatchAction.Subscribe)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    @Test fun descriptionExpansionPersistsThroughSavedState() {
        val handle = SavedStateHandle()
        val vm = WatchViewModel(FixtureWatchRepository(), handle, "bunny")
        assertFalse(vm.uiState.value.descriptionExpanded)
        vm.onAction(WatchAction.ToggleDescription)
        assertTrue(vm.uiState.value.descriptionExpanded)
        val restored = WatchViewModel(
            FixtureWatchRepository(),
            SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            "other",
            WatchScenario.Loading,
        )
        assertTrue(restored.uiState.value.descriptionExpanded)
        assertEquals("bunny", restored.uiState.value.videoId)
    }


    @Test fun searchDraftRestoresAndOnlySubmittingEmitsSearch() = runTest {
        val saved = SavedStateHandle()
        val vm = WatchViewModel(FixtureWatchRepository(), saved, "bunny")
        vm.onAction(WatchAction.OpenSearch)
        vm.onAction(WatchAction.QueryChanged("  meadow  "))
        assertTrue(vm.uiState.value.searchOpen)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        val restored = WatchViewModel(FixtureWatchRepository(), SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), "ignored")
        assertEquals("  meadow  ", restored.uiState.value.draftQuery)
        assertTrue(restored.uiState.value.searchOpen)
        restored.onAction(WatchAction.SubmitSearch(restored.uiState.value.draftQuery))
        assertEquals(WatchNavigationRequest.Search("meadow"), restored.navigationRequests.first())
        assertFalse(restored.uiState.value.searchOpen)
        assertEquals("bunny", restored.uiState.value.videoId)
    }

    @Test fun shortWindowsLeaveTheBodyReachable() {
        assertFalse(WatchLayout.pinnedPlayer(320f, 240f))
        assertFalse(WatchLayout.pinnedPlayer(390f, 240f))
        assertFalse(WatchLayout.pinnedPlayer(1280f, 320f))
        assertEquals(WatchRelatedVariant.List, WatchLayout.relatedVariant(528f, 300f))
    }

    @Test fun actionBarGrowthFollowsTheSampledNaturalRow() {
        assertEquals(338f, WatchLayout.actionGrowth(700f, 820f), 0.01f)
        assertEquals(418f, WatchLayout.actionGrowth(780f, 820f), 0.01f)
        assertEquals(313f, WatchLayout.actionGrowth(931f, 744f), 0.01f)
        assertEquals(418f, WatchLayout.actionGrowth(1280f, 725f), 0.01f)
        assertEquals(738f, WatchLayout.actionGrowth(1600f, 915f), 0.01f)
        // Below the natural row the buttons scroll at their natural positions.
        listOf(Triple(320f, 658f, 0f), Triple(390f, 775f, 28f), Triple(411f, 714f, 49f)).forEach { (w, h, expected) ->
            assertEquals(expected, WatchLayout.actionGrowth(w, h), 0.01f)
        }
        assertEquals(362f, WatchLayout.ActionRowNaturalWidth, 0.01f)
        assertEquals(358f, WatchLayout.ActionRowNaturalEnd, 0.01f)
    }

    @Test fun playerAndColumnFollowTheBreakpointMatrix() {
        // Phone / grid / stacked: the player is fixed at the column's 16:9.
        assertEquals(390f, WatchLayout.columnWidth(390f, 775f), 0.01f)
        assertEquals(390f * 9f / 16f, WatchLayout.playerHeight(390f, 775f), 0.01f)
        assertTrue(WatchLayout.pinnedPlayer(390f, 775f))
        assertTrue(WatchLayout.pinnedPlayer(840f, 725f))
        // Wide-short: the player scrolls with the content.
        assertFalse(WatchLayout.pinnedPlayer(844f, 342f))
        assertTrue(WatchLayout.wideShort(844f, 342f))
        assertFalse(WatchLayout.wideShort(415f, 342f))
        assertFalse(WatchLayout.wideShort(931f, 600f))
        // Sidebar needs width ≥931 AND landscape.
        assertTrue(WatchLayout.sidebar(931f, 744f))
        assertTrue(WatchLayout.sidebar(1600f, 915f))
        assertFalse(WatchLayout.sidebar(1000f, 1200f))
        assertFalse(WatchLayout.sidebar(930f, 900f))
        assertEquals(256f, WatchLayout.sidebarWidth(931f), 0.01f)
        assertEquals(256f, WatchLayout.sidebarWidth(1100f), 0.01f)
        assertEquals(500f, WatchLayout.sidebarWidth(1280f), 0.01f)
        assertEquals(675f, WatchLayout.columnWidth(931f, 744f), 0.01f)
        assertEquals(780f, WatchLayout.columnWidth(1280f, 725f), 0.01f)
        assertEquals(1100f, WatchLayout.columnWidth(1600f, 915f), 0.01f)
        assertEquals(675f * 9f / 16f, WatchLayout.playerHeight(931f, 744f), 0.01f)
    }

    @Test fun gridColumnsFollowTheSampledFormula() {
        assertEquals(1, WatchLayout.gridColumns(390f))
        assertEquals(2, WatchLayout.gridColumns(550f))
        assertEquals(2, WatchLayout.gridColumns(750f))
        assertEquals(3, WatchLayout.gridColumns(780f))
        assertEquals(3, WatchLayout.gridColumns(840f))
        assertEquals(267f, WatchLayout.gridCell(550f), 0.01f)
        assertEquals(292f, WatchLayout.gridCell(600f), 0.01f)
        assertEquals(342f, WatchLayout.gridCell(700f), 0.01f)
        assertEquals(367f, WatchLayout.gridCell(750f), 0.01f)
        assertEquals(252f, WatchLayout.gridCell(780f), 0.01f)
        assertEquals(272f, WatchLayout.gridCell(840f), 0.01f)
    }

    @Test fun relatedVariantSelectionMatchesTheZoneMatrix() {
        val expectations = listOf(
            Triple(320f, 658f, WatchRelatedVariant.Phone),
            Triple(390f, 775f, WatchRelatedVariant.Phone),
            Triple(411f, 714f, WatchRelatedVariant.Phone),
            Triple(550f, 828f, WatchRelatedVariant.Grid),
            Triple(840f, 914f, WatchRelatedVariant.Grid),
            Triple(844f, 342f, WatchRelatedVariant.List),
            Triple(840f, 725f, WatchRelatedVariant.Stacked),
            Triple(931f, 744f, WatchRelatedVariant.SidebarCompact),
            Triple(1100f, 725f, WatchRelatedVariant.SidebarCompact),
            Triple(1280f, 725f, WatchRelatedVariant.SidebarWide),
            Triple(1280f, 1104f, WatchRelatedVariant.SidebarWide),
            Triple(1600f, 915f, WatchRelatedVariant.SidebarWide),
        )
        for ((width, height, expected) in expectations) {
            assertEquals("$width × $height", expected, WatchLayout.relatedVariant(width, height))
        }
    }

    @Test fun phoneCoverKeepsTheSampledPlusThirteen() {
        assertEquals(193f, WatchLayout.phoneCoverHeight(320f), 0.01f)
        assertEquals(390f * 9f / 16f + 13f, WatchLayout.phoneCoverHeight(390f), 0.01f)
        assertEquals(411f * 9f / 16f + 13f, WatchLayout.phoneCoverHeight(411f), 0.01f)
        assertEquals(244.1875f, WatchLayout.phoneCoverHeight(411f), 0.01f)
    }

    @Test fun metadataSectionUsesThePinnedMajorityMarkup() {
        assertEquals(48f, WatchLayout.TopBarHeight, 0.01f)
        assertEquals(70f, WatchLayout.InfoBlockHeight, 0.01f)
        assertEquals(118f, WatchLayout.MetadataSectionHeight, 0.01f)
        assertEquals(48f, WatchLayout.ActionBarHeight, 0.01f)
        assertEquals(113f, WatchLayout.CommentsSectionHeight, 0.01f)
        assertEquals(92f, WatchLayout.CommentsCardHeight, 0.01f)
        assertEquals(200f, WatchLayout.ListCoverWidth, 0.01f)
        assertEquals(112f, WatchLayout.ListCoverHeight, 0.01f)
    }

    @Test fun fixtureKeepsEnglishMetadataAndLocalArtwork() {
        val page = FixtureWatchRepository.samplePage("bunny")
        val video = page.video
        assertEquals("110K likes", video.likes)
        assertEquals("5.2K", video.commentCount)
        assertTrue(video.title.isNotBlank() && video.channel.isNotBlank() && video.handle.isNotBlank())
        assertTrue(video.views.endsWith("views"))
        assertTrue(video.description.isNotBlank())
        assertEquals("Maya K.", video.topComment.author)

        val related = FixtureWatchRepository.sampleRelated()
        assertTrue(related.size >= 6)
        assertEquals(related.size, related.map { it.id }.distinct().size)
        assertEquals(listOf("bunny", "dream", "sintel"), related.map { it.artwork }.distinct().sorted())
        assertTrue(related.all { it.title.isNotBlank() && it.channelId.isNotBlank() })
        assertTrue(related.all { it.views.endsWith("views") && it.duration.isNotBlank() })
        assertEquals(listOf("4K"), related.mapNotNull { it.badge })
        assertTrue(related.any { it.progress != null })
        // The page never lists itself as a related video.
        assertTrue(FixtureWatchRepository.samplePage("harbour").related.any { it.id == "bunny" })
        assertTrue(FixtureWatchRepository.samplePage("harbour").related.none { it.id == "harbour" })
    }
}
