package com.hhst.youtubelite.subscriptions

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.library.data.SignedOutPrompts
import com.hhst.youtubelite.library.domain.SignInPrompt
import com.hhst.youtubelite.subscriptions.data.*
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsViewModel
import com.hhst.youtubelite.ui.components.MainTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun repository(block: suspend (SubscriptionsFilter, String?) -> SubscriptionsPage) = object : SubscriptionsRepository {
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) = block(filter, continuation)
    }

    private class ChannelFeedRepository : SubscriptionsRepository {
        val reads = mutableListOf<Pair<String?, String?>>()
        var channels = FixtureSubscriptionsRepository.channels()
        var delayBlender = false
        private val video = FixtureSubscriptionsRepository.sampleVideos().first()
        override suspend fun subscribedChannelsPage(cursor: String?) = ChannelDirectoryPage(channels)
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?): SubscriptionsPage {
            reads += null to continuation
            return SubscriptionsPage(listOf(video.copy(id = "combined")), emptyList())
        }
        override suspend fun channelSubscriptions(channel: SubscribedChannel, filter: SubscriptionsFilter,
                                                 continuation: String?): SubscriptionsPage {
            reads += channel.channelId to continuation
            if (delayBlender && channel.channelId == "blender") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(200)
            }
            return SubscriptionsPage(listOf(video.copy(id = "${channel.channelId}-${continuation ?: "first"}",
                channelId = channel.channelId)), emptyList(), if (continuation == null) "${channel.channelId}-next" else null)
        }
    }

    @Test fun selectingAnAvatarLoadsItsFeedAndClearsOnlyItsBadgeWithoutNavigating() = runTest {
        val repo = ChannelFeedRepository()
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChannel("blender"))
        assertEquals("blender", vm.uiState.value.selectedChannel?.channelId)
        val channels = (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels
        assertFalse(channels.first { it.channelId == "blender" }.hasNewContent)
        assertTrue(channels.first { it.channelId == "studio" }.hasNewContent)
        advanceUntilIdle()
        assertEquals(listOf(null to null, "blender" to null), repo.reads)
        assertEquals("blender", (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.single().channelId)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(SubscriptionsAction.OpenChannel("blender"))
        assertEquals(SubscriptionsNavigationRequest.Channel("blender"), vm.navigationRequests.first())
    }

    @Test fun selectedChannelPaginationAndBackKeepTheirOwnFeedScopes() = runTest {
        val repo = ChannelFeedRepository()
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChannel("studio"))
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        assertEquals("studio" to "studio-next", repo.reads.last())
        assertEquals(2, (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.size)
        vm.onAction(SubscriptionsAction.SelectChannel(null))
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedChannel)
        assertEquals(null to null, repo.reads.last())
        assertEquals("combined", (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.single().id)
        assertFalse((vm.uiState.value.channels as SubscriptionsChannels.Ready).channels.first { it.channelId == "studio" }.hasNewContent)
    }

    @Test fun aSlowSelectedChannelCannotReplaceTheNextSelection() = runTest {
        val repo = ChannelFeedRepository().apply { delayBlender = true }
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChannel("blender"))
        runCurrent()
        vm.onAction(SubscriptionsAction.SelectChannel("studio"))
        advanceUntilIdle()
        assertEquals("studio", vm.uiState.value.selectedChannel?.channelId)
        assertEquals("studio-first", (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.single().id)
    }

    @Test fun consumedBadgesSurviveStaleRefreshButFutureUpdatesCanBadgeAgain() = runTest {
        var now = 0L
        val repo = ChannelFeedRepository()
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true, clock = { now })
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChannel("blender"))
        advanceUntilIdle()
        fun hasBadge() = (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels.first { it.channelId == "blender" }.hasNewContent
        now += 60_000
        vm.onForeground()
        advanceUntilIdle()
        assertFalse(hasBadge()) // A stale true flag cannot undo the user's read.
        repo.channels = repo.channels.map { it.copy(hasNewContent = false) }
        now += 60_000
        vm.onForeground()
        advanceUntilIdle()
        repo.channels = repo.channels.map { it.copy(hasNewContent = true) }
        now += 60_000
        vm.onForeground()
        advanceUntilIdle()
        assertTrue(hasBadge()) // A fresh server unread cycle is not suppressed forever.
    }

    @Test fun selectionAndConsumedBadgesRestoreButNeverLeakAcrossAccounts() = runTest {
        val handle = SavedStateHandle()
        val repo = ChannelFeedRepository().apply {
            channels = channels.map { it.copy(subscriptionParams = "params-${it.channelId}") }
        }
        val vm = SubscriptionsViewModel(repo, handle, SubscriptionsScenario.Content, true)
        vm.updateSession(true, 1)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChannel("blender"))
        advanceUntilIdle()
        val restored = SubscriptionsViewModel(repo, handle, SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertEquals("params-blender", restored.uiState.value.selectedChannel?.subscriptionParams)
        assertEquals("blender" to null, repo.reads.last())
        assertFalse((restored.uiState.value.channels as SubscriptionsChannels.Ready).channels.first { it.channelId == "blender" }.hasNewContent)
        restored.updateSession(true, 2)
        advanceUntilIdle()
        assertNull(restored.uiState.value.selectedChannel)
        assertTrue((restored.uiState.value.channels as SubscriptionsChannels.Ready).channels.first { it.channelId == "blender" }.hasNewContent)
        assertEquals(null to null, repo.reads.last())
    }

    @Test fun directoryRefreshKeepsContentAndReordersUpdatedChannels() = runTest {
        var now = 0L
        var reads = 0
        val first = SubscribedChannel("one", "One", "", "", true)
        val second = SubscribedChannel("two", "Two", "", "")
        val repository = object : SubscriptionsRepository {
            override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) =
                SubscriptionsPage(emptyList(), emptyList())
            override suspend fun subscribedChannelsPage(cursor: String?): ChannelDirectoryPage {
                reads++
                return ChannelDirectoryPage(if (reads == 1) listOf(first, second)
                    else listOf(first.copy(hasNewContent = false), second.copy(hasNewContent = true)))
            }
        }
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content,
            signedIn = true, clock = { now })
        advanceUntilIdle()
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, reads)
        now = 60_000
        vm.onForeground()
        assertTrue(vm.uiState.value.channels is SubscriptionsChannels.Ready)
        advanceUntilIdle()
        val channels = (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels
        assertEquals(listOf("two", "one"), channels.map { it.channelId })
        assertFalse(channels.last().hasNewContent)
        assertEquals(2, reads)
    }

    @Test fun directoryRefreshFailureKeepsThePreviousDirectory() = runTest {
        var now = 0L
        var fail = false
        val expected = FixtureSubscriptionsRepository.channels()
        val repository = object : SubscriptionsRepository {
            override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) =
                SubscriptionsPage(emptyList(), emptyList())
            override suspend fun subscribedChannelsPage(cursor: String?): ChannelDirectoryPage {
                if (fail) error("offline")
                return ChannelDirectoryPage(expected)
            }
        }
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content,
            signedIn = true, clock = { now })
        advanceUntilIdle()
        val previous = vm.uiState.value.channels
        fail = true
        now = 60_000
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(previous, vm.uiState.value.channels)
    }

    @Test fun signedOutFeedShowsThePromptWithoutLoading() = runTest {
        val vm = SubscriptionsViewModel(repository { _, _ -> error("unused") }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = false)
        advanceUntilIdle()
        val content = vm.uiState.value.content
        assertTrue(content is SubscriptionsContent.SignedOut)
        assertEquals(SignedOutPrompts.forContent("subscriptions"), (content as SubscriptionsContent.SignedOut).prompt)
    }

    @Test fun chipSelectionReloadsWithTheMatchingFilter() = runTest {
        val filters = mutableListOf<SubscriptionsFilter>()
        val vm = SubscriptionsViewModel(repository { filter, _ ->
            filters += filter
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos().filter { it.live || filter != SubscriptionsFilter.Live }, FixtureSubscriptionsRepository.channels())
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertEquals(listOf(SubscriptionsFilter.All), filters)
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        advanceUntilIdle()
        assertEquals(listOf(SubscriptionsFilter.All, SubscriptionsFilter.Live), filters)
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertTrue(feed.page.items.all { it.live })
        assertEquals(SubscriptionsChip.Live, vm.uiState.value.selectedChip)
    }

    @Test fun selectingTheCurrentChipKeepsTheActiveReadAndLoadedFeed() = runTest {
        var calls = 0
        val response = kotlinx.coroutines.CompletableDeferred<SubscriptionsPage>()
        val vm = SubscriptionsViewModel(repository { _, _ ->
            calls++
            response.await()
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        runCurrent()
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.All))
        runCurrent()
        assertEquals(1, calls)

        response.complete(SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), emptyList()))
        advanceUntilIdle()
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.All))
        assertSame(feed, vm.uiState.value.content)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun postsChipKeepsTheEmptyStateInsteadOfInventingPosts() = runTest {
        val vm = SubscriptionsViewModel(repository { filter, _ ->
            SubscriptionsPage(if (filter == SubscriptionsFilter.Posts) emptyList() else FixtureSubscriptionsRepository.sampleVideos(),
                FixtureSubscriptionsRepository.channels())
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Posts))
        advanceUntilIdle()
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertTrue(feed.page.items.isEmpty())
    }

    @Test fun emptyFilteredPagesAutoContinueWithinTheBoundThenStop() = runTest {
        var calls = 0
        val vm = SubscriptionsViewModel(repository { _, _ ->
            calls++
            SubscriptionsPage(emptyList(), emptyList(), nextKey = "page-${calls + 1}")
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertEquals(4, calls) // the first page plus three bounded automatic continuations
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertTrue(feed.page.items.isEmpty())
        assertEquals(3, feed.emptyAutoLoads)
        assertEquals("page-5", feed.page.nextKey)
        advanceUntilIdle()
        assertEquals(4, calls) // nothing pages on its own past the bound
    }

    @Test fun emptyFilteredPagesResumeTheFeedWhenResultsArrive() = runTest {
        var calls = 0
        val video = FixtureSubscriptionsRepository.sampleVideos().first()
        val vm = SubscriptionsViewModel(repository { _, continuation ->
            calls++
            when (continuation) {
                null -> SubscriptionsPage(emptyList(), emptyList(), nextKey = "page-2")
                "page-2" -> SubscriptionsPage(listOf(video.copy(id = "${video.id}-3")), emptyList())
                else -> error("unexpected continuation $continuation")
            }
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertEquals(2, calls)
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(listOf("${video.id}-3"), feed.page.items.map { it.id })
        assertEquals(0, feed.emptyAutoLoads)
        assertNull(feed.page.nextKey)
    }

    @Test fun loadMoreAfterTheBoundFetchesOnePagePerTap() = runTest {
        var calls = 0
        val vm = SubscriptionsViewModel(repository { _, _ ->
            calls++
            SubscriptionsPage(emptyList(), emptyList(), nextKey = "page-${calls + 1}")
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val settled = calls
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        assertEquals(settled + 1, calls)
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertTrue(feed.page.items.isEmpty())
        assertEquals("page-${settled + 2}", feed.page.nextKey)
    }

    @Test fun paginationAppendsAndFailureKeepsTheLoadedFeed() = runTest {
        var failAppend = false
        val vm = SubscriptionsViewModel(repository { _, continuation ->
            if (continuation != null && failAppend) error("offline")
            val base = FixtureSubscriptionsRepository.sampleVideos()
            SubscriptionsPage(if (continuation == null) base else base.map { it.copy(id = it.id + "-2") },
                FixtureSubscriptionsRepository.channels(), nextKey = if (continuation == null) "page-2" else "page-3")
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val initialSize = (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.size
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        var feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(initialSize * 2, feed.page.items.size)
        assertEquals(AppendState.Idle, feed.append)
        failAppend = true
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(initialSize * 2, feed.page.items.size)
        assertEquals(AppendState.Failed, feed.append)
    }

    @Test fun trafficChallengeShowsTheBannerAndCompletionRetriesOnce() = runTest {
        val challenge = "https://www.google.com/sorry/index?q=E-ACAAQD"
        var calls = 0
        val vm = SubscriptionsViewModel(repository { _, continuation ->
            calls++
            if (continuation == null && calls == 1) throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION, "verification", detail = challenge))
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), FixtureSubscriptionsRepository.channels(), nextKey = null)
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SubscriptionsContent.Failed)
        assertEquals(challenge, vm.uiState.value.verification?.challengeUrl)
        vm.verificationCompleted(challenge)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SubscriptionsContent.Feed)
        assertNull(vm.uiState.value.verification)
        assertEquals(2, calls)
        // A repeated completion must not form a retry loop.
        vm.verificationCompleted(challenge)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun mismatchedChallengeUrlKeepsTheBannerWithoutRetrying() = runTest {
        val challenge = "https://www.google.com/sorry/index?q=E-ACAAQD"
        var calls = 0
        val vm = SubscriptionsViewModel(repository { _, continuation ->
            calls++
            if (continuation == null && calls == 1) throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION, "verification", detail = challenge))
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), FixtureSubscriptionsRepository.channels(), nextKey = null)
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertEquals(challenge, vm.uiState.value.verification?.challengeUrl)
        vm.verificationCompleted(null)
        advanceUntilIdle()
        vm.verificationCompleted("https://www.google.com/sorry/other")
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(challenge, vm.uiState.value.verification?.challengeUrl)
    }

    @Test fun plainFailureLeavesNoVerificationToSolve() = runTest {
        var calls = 0
        val vm = SubscriptionsViewModel(repository { _, _ ->
            calls++
            throw com.hhst.youtubelite.data.media.MediaException(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.HTTP, "503"))
        }, SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SubscriptionsContent.Failed)
        assertNull(vm.uiState.value.verification)
        vm.verificationCompleted(null)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun menuMirrorsTheHomeVideoActionsAndEmitsIdentity() = runTest {
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val requests = mutableListOf<SubscriptionsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(SubscriptionsAction.OpenMenu("bunny"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(listOf(SubscriptionsNavigationRequest.MenuAction("bunny", FeedMenuAction.WatchLater)), requests)
        assertEquals(SubscriptionsOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun selectingTheSubscriptionsTabItselfDoesNotNavigate() = runTest {
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), SavedStateHandle(), SubscriptionsScenario.Content, signedIn = true)
        advanceUntilIdle()
        val requests = mutableListOf<SubscriptionsNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(SubscriptionsAction.Destination(MainTab.Subscriptions))
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test fun scenarioInitialContentCoversLoadingErrorAndAppendStates() {
        val prompt = SignInPrompt("Don't miss new videos", "Sign in to see updates from your favorite YouTube channels", "Sign in")
        assertEquals(SubscriptionsContent.Loading, SubscriptionsScenario.Loading.initialContent(prompt))
        assertEquals(SubscriptionsContent.Failed, SubscriptionsScenario.Error.initialContent(prompt))
        assertTrue(SubscriptionsScenario.PaginationLoading.initialContent(prompt) is SubscriptionsContent.Feed)
        val append = (SubscriptionsScenario.PaginationError.initialContent(prompt) as SubscriptionsContent.Feed).append
        assertEquals(AppendState.Failed, append)
    }

    @Test fun staleFilterCompletionCannotOverwriteTheNewFilter() = runTest {
        val vm = SubscriptionsViewModel(repository { filter, _ ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(if (filter == SubscriptionsFilter.All) 200 else 10)
            }
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos().take(if (filter == SubscriptionsFilter.All) 2 else 1), emptyList())
        }, SavedStateHandle(), SubscriptionsScenario.Content, true)
        runCurrent()
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        advanceUntilIdle()
        assertEquals(1, (vm.uiState.value.content as SubscriptionsContent.Feed).page.items.size)
        assertEquals(SubscriptionsChip.Live, vm.uiState.value.selectedChip)
    }

    @Test fun failedPaginationCanRetryAndDuplicateIdsAreMerged() = runTest {
        var attempts = 0
        val page = FixtureSubscriptionsRepository.sampleVideos()
        val vm = SubscriptionsViewModel(repository { _, cursor ->
            if (cursor != null && attempts++ == 0) error("offline")
            SubscriptionsPage(page, emptyList(), if (cursor == null) "next" else null)
        }, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        assertEquals(AppendState.Failed, (vm.uiState.value.content as SubscriptionsContent.Feed).append)
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as SubscriptionsContent.Feed
        assertEquals(page.size, feed.page.items.size)
        assertEquals(AppendState.Idle, feed.append)
        assertNull(feed.page.nextKey)
    }

    @Test fun filterSearchAndOverlayRestoreTogether() = runTest {
        val handle = SavedStateHandle()
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), handle, SubscriptionsScenario.Content, true)
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        vm.onAction(SubscriptionsAction.QueryChanged("films"))
        vm.onAction(SubscriptionsAction.OpenSearch)
        val restored = SubscriptionsViewModel(FixtureSubscriptionsRepository(), handle, SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertEquals(SubscriptionsChip.Live, restored.uiState.value.selectedChip)
        assertEquals("films", restored.uiState.value.query)
        assertEquals(SubscriptionsOverlay.Search, restored.uiState.value.overlay)
    }

    private fun dualRepository(
        feed: suspend (SubscriptionsFilter, String?) -> SubscriptionsPage,
        channels: suspend () -> List<SubscribedChannel>,
    ) = object : SubscriptionsRepository {
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) = feed(filter, continuation)
        override suspend fun subscribedChannels() = channels()
    }

    @Test fun unreadChannelsComeFirstWithoutReorderingEitherGroup() = runTest {
        val channels = listOf(
            SubscribedChannel("seen-a", "Seen A", "", ""),
            SubscribedChannel("new-a", "New A", "", "", true),
            SubscribedChannel("seen-b", "Seen B", "", ""),
            SubscribedChannel("new-b", "New B", "", "", true),
        )
        val vm = SubscriptionsViewModel(dualRepository({ _, _ -> SubscriptionsPage(emptyList(), emptyList()) }, {
            channels + channels.first()
        }), SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertEquals(listOf("new-a", "new-b", "seen-a", "seen-b"),
            (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels.map { it.channelId })
        vm.onAction(SubscriptionsAction.ShowChannels)
        vm.onAction(SubscriptionsAction.CloseChannels)
        assertEquals(listOf(true, true, false, false),
            (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels.map { it.hasNewContent })
    }

    @Test fun openingTheChannelDirectoryReusesTheIndependentGuide() = runTest {
        var reads = 0
        val vm = SubscriptionsViewModel(dualRepository({ _, _ -> error("Feed unavailable") }, {
            reads++
            FixtureSubscriptionsRepository.channels()
        }), SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        repeat(3) {
            vm.onAction(SubscriptionsAction.ShowChannels)
            vm.onAction(SubscriptionsAction.CloseChannels)
        }
        advanceUntilIdle()
        assertEquals(1, reads)
        assertTrue(vm.uiState.value.channels is SubscriptionsChannels.Ready)
        assertEquals(SubscriptionsContent.Failed, vm.uiState.value.content)
    }

    @Test fun failedFeedKeepsTheLoadedChannelGuide() = runTest {
        val vm = SubscriptionsViewModel(dualRepository({ _, _ -> error("offline") }, {
            FixtureSubscriptionsRepository.channels()
        }), SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SubscriptionsContent.Failed)
        assertEquals(SubscriptionsChannels.Ready(FixtureSubscriptionsRepository.channels()), vm.uiState.value.channels)
    }

    @Test fun failedChannelGuideDoesNotEraseTheFeedAndCanRetry() = runTest {
        var failChannels = true
        val vm = SubscriptionsViewModel(dualRepository({ _, _ ->
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), emptyList())
        }, {
            if (failChannels) error("guide offline")
            FixtureSubscriptionsRepository.channels()
        }), SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is SubscriptionsContent.Feed)
        assertEquals(SubscriptionsChannels.Failed, vm.uiState.value.channels)
        failChannels = false
        vm.onAction(SubscriptionsAction.RetryChannels)
        advanceUntilIdle()
        assertEquals(SubscriptionsChannels.Ready(FixtureSubscriptionsRepository.channels()), vm.uiState.value.channels)
    }

    @Test fun feedPaginationNeverInvalidatesTheChannelGuide() = runTest {
        var channelLoads = 0
        val vm = SubscriptionsViewModel(dualRepository({ _, continuation ->
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), emptyList(),
                if (continuation == null) "next" else null)
        }, {
            channelLoads++
            FixtureSubscriptionsRepository.channels()
        }), SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertEquals(1, channelLoads)
        vm.onAction(SubscriptionsAction.LoadNext)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.SelectChip(SubscriptionsChip.Live))
        advanceUntilIdle()
        assertEquals(1, channelLoads)
        assertEquals(SubscriptionsChannels.Ready(FixtureSubscriptionsRepository.channels()), vm.uiState.value.channels)
    }

    @Test fun accountSwitchResetsAndReloadsTheChannelGuide() = runTest {
        var generations = 0
        val vm = SubscriptionsViewModel(dualRepository({ _, _ ->
            SubscriptionsPage(FixtureSubscriptionsRepository.sampleVideos(), emptyList())
        }, {
            generations++
            FixtureSubscriptionsRepository.channels()
        }), SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        assertEquals(1, generations)
        vm.updateSession(true, 1) // First snapshot only establishes the key.
        advanceUntilIdle()
        assertEquals(1, generations)
        vm.updateSession(true, 2) // A moved generation means a different account.
        advanceUntilIdle()
        assertEquals(2, generations)
        assertEquals(SubscriptionsChannels.Ready(FixtureSubscriptionsRepository.channels()), vm.uiState.value.channels)
    }

    private class FakeSaveRepository : com.hhst.youtubelite.saves.domain.SaveRepository {
        var watchLaterCalls = 0
        override suspend fun choices(videoId: String) =
            listOf(com.hhst.youtubelite.saves.domain.SaveChoice("WL", "Watch later", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun saveWatchLater(videoId: String) { watchLaterCalls++ }
        override suspend fun create(title: String, description: String, videoId: String) =
            com.hhst.youtubelite.saves.domain.SaveChoice("PLnew", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }

    @Test fun liveMenuWritesThroughTheSaveSheetAndDropsFeedbackRows() = runTest {
        val saves = FakeSaveRepository()
        val vm = SubscriptionsViewModel(FixtureSubscriptionsRepository(), SavedStateHandle(), SubscriptionsScenario.Content, true,
            saveRepository = saves)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.live)
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.WatchLater))
        advanceUntilIdle()
        assertEquals(1, saves.watchLaterCalls)
        assertEquals(SubscriptionsOverlay.None, vm.uiState.value.overlay)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.SaveToPlaylist))
        advanceUntilIdle()
        assertEquals("harbour", vm.saveSheet?.state?.value?.videoId)
        vm.saveSheet?.close()
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.NotInterested))
        advanceUntilIdle()
        assertNull(vm.saveSheet?.state?.value)
        assertNull(withTimeoutOrNull(1) { vm.navigationRequests.first() })
    }

    /** Live repository with deferred-menu reads; records get_panel and feedback operations. */
    private class MenuRepository(val cards: List<com.hhst.youtubelite.home.domain.FeedItem.Video>) : SubscriptionsRepository {
        var menuCalls = 0
        var menuError: Exception? = null
        val feedback = mutableListOf<Pair<com.hhst.youtubelite.home.domain.FeedItem.Video, FeedMenuAction>>()
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) =
            SubscriptionsPage(cards, emptyList())
        override suspend fun menu(video: com.hhst.youtubelite.home.domain.FeedItem.Video): com.hhst.youtubelite.home.domain.FeedItem.Video {
            menuCalls++
            menuError?.let { throw it }
            return video.copy(notInterestedToken = "token-${video.id}", dontRecommendToken = "chan-${video.id}")
        }
        override suspend fun feedback(video: com.hhst.youtubelite.home.domain.FeedItem.Video, action: FeedMenuAction) {
            feedback += video to action
        }
    }

    private fun menuCard(id: String, channelId: String = "northlight") = com.hhst.youtubelite.home.domain.FeedItem.Video(
        id, "Title $id", "Author", "", "", "", channelId,
        menuEndpoint = com.hhst.youtubelite.home.domain.FeedMenuEndpoint("menu", "params-$id"), feedbackGeneration = 2)

    @Test fun liveMenuReadsTheCardsOwnEndpointAndWritesBackItsCapabilities() = runTest {
        val repository = MenuRepository(listOf(menuCard("harbour"), menuCard("studio", channelId = "other")))
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content, true, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        advanceUntilIdle()
        assertEquals(1, repository.menuCalls)
        assertFalse(vm.uiState.value.menuLoading)
        assertFalse(vm.uiState.value.menuFailed)
        val page = (vm.uiState.value.content as SubscriptionsContent.Feed).page
        assertEquals("token-harbour", page.items.first { it.id == "harbour" }.notInterestedToken)
        // Untouched cards stay untouched: no preload for menus never opened.
        assertNull(page.items.first { it.id == "studio" }.notInterestedToken)
        assertEquals(1, repository.menuCalls)
    }

    @Test fun failedMenuReadOffersRetryAndRecoveryClearsIt() = runTest {
        val repository = MenuRepository(listOf(menuCard("harbour"))).apply { menuError = java.io.IOException("offline") }
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content, true, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.menuFailed)
        repository.menuError = null
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.menuFailed)
        val page = (vm.uiState.value.content as SubscriptionsContent.Feed).page
        assertEquals("token-harbour", page.items.first { it.id == "harbour" }.notInterestedToken)
    }

    @Test fun liveFeedbackRemovesTheCardAndOnlyItsOwnChannelCards() = runTest {
        val repository = MenuRepository(listOf(menuCard("harbour"), menuCard("studio"), menuCard("meadow", channelId = "openfield")))
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content, true, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.updateSession(true, 2L)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.DontRecommend))
        advanceUntilIdle()
        val page = (vm.uiState.value.content as SubscriptionsContent.Feed).page
        assertEquals(listOf("meadow"), page.items.map { it.id })
        assertEquals(1, repository.feedback.size)
        assertEquals("harbour", repository.feedback.single().first.id)
        assertEquals(FeedMenuAction.DontRecommend, repository.feedback.single().second)
        assertEquals(com.hhst.youtubelite.core.AppNotice.Message("Removed from recommendations"), vm.actionNotices.first())
        assertEquals(SubscriptionsOverlay.None, vm.uiState.value.overlay)
    }

    @Test fun aMenuCapturedByAnotherSessionIsNeverReadOrWritten() = runTest {
        val repository = MenuRepository(listOf(menuCard("harbour").copy(feedbackGeneration = 9)))
        val vm = SubscriptionsViewModel(repository, SavedStateHandle(), SubscriptionsScenario.Content, true, saveRepository = FakeSaveRepository())
        advanceUntilIdle()
        vm.updateSession(true, 2L) // Session generation 2, card captured for 9.
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        advanceUntilIdle()
        assertEquals(0, repository.menuCalls)
        assertFalse(vm.uiState.value.menuLoading)
        vm.onAction(SubscriptionsAction.OpenMenu("harbour"))
        vm.onAction(SubscriptionsAction.MenuSelected(FeedMenuAction.NotInterested))
        advanceUntilIdle()
        assertTrue(repository.feedback.isEmpty())
    }

    private fun directoryRepository(pages: Map<String, ChannelDirectoryPage>, first: ChannelDirectoryPage,
                                    vararg failures: String) = object : SubscriptionsRepository {
        var failed = 0
        override suspend fun subscriptions(filter: SubscriptionsFilter, continuation: String?) =
            SubscriptionsPage(emptyList(), emptyList())
        override suspend fun subscribedChannelsPage(cursor: String?): ChannelDirectoryPage {
            if (cursor == null) return first
            if (cursor in failures && failed < failures.size) {
                failed++
                throw java.io.IOException("directory page failed")
            }
            return pages.getValue(cursor)
        }
    }

    @Test fun directoryAppendsPagesMergesUnreadFlagsAndStopsAtTheHonestEnd() = runTest {
        val repo = directoryRepository(
            first = ChannelDirectoryPage(listOf(
                SubscribedChannel("a", "A", "", ""), SubscribedChannel("b", "B", "", "", true)), "p2"),
            pages = mapOf(
                "p2" to ChannelDirectoryPage(listOf(
                    SubscribedChannel("b", "B", "", "", false), SubscribedChannel("c", "C", "", "")), "p3"),
                "p3" to ChannelDirectoryPage(listOf(SubscribedChannel("d", "D", "", "")), null),
            ),
        )
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        var ready = vm.uiState.value.channels as SubscriptionsChannels.Ready
        assertEquals(listOf("b", "a", "c"), ready.channels.map { it.channelId })
        assertEquals("p3", ready.nextKey)
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        ready = vm.uiState.value.channels as SubscriptionsChannels.Ready
        assertEquals(listOf("b", "a", "c", "d"), ready.channels.map { it.channelId })
        assertNull(ready.nextKey)
        assertEquals(AppendState.Idle, ready.append)
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        assertEquals(listOf("b", "a", "c", "d"),
            (vm.uiState.value.channels as SubscriptionsChannels.Ready).channels.map { it.channelId })
    }

    @Test fun failedDirectoryPageKeepsLoadedChannelsAndCanRetry() = runTest {
        val repo = directoryRepository(
            first = ChannelDirectoryPage(listOf(SubscribedChannel("a", "A", "", "")), "p2"),
            pages = mapOf("p2" to ChannelDirectoryPage(listOf(SubscribedChannel("c", "C", "", "")), null)),
            failures = arrayOf("p2"),
        )
        val vm = SubscriptionsViewModel(repo, SavedStateHandle(), SubscriptionsScenario.Content, true)
        advanceUntilIdle()
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        val failed = vm.uiState.value.channels as SubscriptionsChannels.Ready
        assertEquals(listOf("a"), failed.channels.map { it.channelId })
        assertEquals(AppendState.Failed, failed.append)
        vm.onAction(SubscriptionsAction.LoadMoreChannels)
        advanceUntilIdle()
        val retried = vm.uiState.value.channels as SubscriptionsChannels.Ready
        assertEquals(listOf("a", "c"), retried.channels.map { it.channelId })
        assertNull(retried.nextKey)
    }

}
