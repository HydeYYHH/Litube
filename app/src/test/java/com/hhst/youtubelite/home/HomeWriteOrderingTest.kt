package com.hhst.youtubelite.home

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.*
import com.hhst.youtubelite.home.presentation.HomeViewModel
import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.domain.SaveRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeWriteOrderingTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun video(id: String) = FeedItem.Video(id, id, "Author", "", "", "1:00", "channel-$id",
        notInterestedToken = id, feedbackGeneration = 7)
    private open class Saves : SaveRepository {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        override suspend fun choices(videoId: String) = listOf(SaveChoice("p", "Playlist", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun create(title: String, description: String, videoId: String) = SaveChoice("p", title, true)
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
        override suspend fun saveWatchLater(videoId: String) {
            try { gate.await() } catch (error: CancellationException) { cancelled = true; throw error }
        }
    }
    private inner class Feed : HomeRepository {
        val write = CompletableDeferred<Unit>()
        var nextReads = 0
        var removed = false
        override suspend fun loadHome(filter: String, continuation: String?): HomePage {
            if (continuation != null) nextReads++
            return HomePage(if (continuation == null) listOfNotNull(video("a").takeUnless { removed }, video("b"))
                else listOf(video("c")), nextKey = if (continuation == null) "next" else "third")
        }
        override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) { write.await(); removed = true }
    }
    private fun model(feed: HomeRepository, saves: SaveRepository = Saves()) =
        HomeViewModel(feed, SavedStateHandle(), loadOnStart = true, saveRepository = saves).also { it.updateSession(true, 7) }
    private fun HomeViewModel.menu(id: String, action: FeedMenuAction) {
        onAction(HomeAction.OpenMenu(id)); onAction(HomeAction.MenuSelected(action))
    }

    @Test fun appendingWaitsForFeedbackAndKeepsTheConfirmedRemovalAndCursor() = runTest {
        val feed = Feed()
        val vm = model(feed)
        runCurrent()
        vm.menu("a", FeedMenuAction.NotInterested)
        runCurrent()
        vm.onAction(HomeAction.LoadNext)
        runCurrent()
        val readsBeforeConfirmation = feed.nextReads
        feed.write.complete(Unit)
        advanceUntilIdle()
        val page = (vm.uiState.value.content as HomeContent.Feed).page
        assertEquals(0, readsBeforeConfirmation)
        assertEquals(listOf("b", "c"), page.items.map { it.id })
        assertEquals("third", page.nextKey)
    }

    @Test fun openingAnotherSaveSheetDoesNotCancelWatchLater() = runTest {
        val saves = Saves()
        val vm = model(Feed(), saves)
        runCurrent()
        vm.menu("a", FeedMenuAction.WatchLater)
        runCurrent()
        vm.menu("b", FeedMenuAction.SaveToPlaylist)
        runCurrent()
        val cancelledByOpening = saves.cancelled
        val pendingWhileSheetOpen = vm.writes.pending.value.isNotEmpty()
        saves.gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(cancelledByOpening)
        assertTrue(pendingWhileSheetOpen)
        assertEquals("b", (vm.uiState.value.overlay as HomeOverlay.Save).videoId)
        assertTrue(vm.writes.pending.value.isEmpty())
    }

    @Test fun feedbackWaitsForTheEarlierAppendBeforeRemovingTheCard() = runTest {
        val append = CompletableDeferred<Unit>()
        var writes = 0
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?): HomePage {
                if (continuation != null) append.await()
                return HomePage(if (continuation == null) listOf(video("a"), video("b")) else listOf(video("a"), video("c")),
                    nextKey = if (continuation == null) "next" else "third")
            }
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) { writes++ }
        }
        val vm = model(repo)
        runCurrent()
        vm.onAction(HomeAction.LoadNext)
        runCurrent()
        vm.menu("a", FeedMenuAction.NotInterested)
        runCurrent()
        val writesBeforeAppend = writes
        append.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, writesBeforeAppend)
        assertEquals(listOf("b", "c"), (vm.uiState.value.content as HomeContent.Feed).page.items.map { it.id })
    }

    @Test fun changingAccountDoesNotWaitForOrPublishAnOldNonCancellableFailure() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?): HomePage {
                reads++
                return HomePage(listOf(video("a"), video("b")))
            }
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) {
                withContext(NonCancellable) { old.await(); throw com.hhst.youtubelite.watch.domain.SignInRequiredException() }
            }
        }
        val vm = model(repo)
        val notices = mutableListOf<com.hhst.youtubelite.core.AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.toList(notices) }
        runCurrent()
        vm.menu("a", FeedMenuAction.NotInterested)
        runCurrent()
        vm.updateSession(true, 8)
        runCurrent()
        val readsBeforeOldFailure = reads
        val busyBeforeOldFailure = vm.writes.pending.value.isNotEmpty()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, readsBeforeOldFailure)
        assertFalse(busyBeforeOldFailure)
        assertTrue(notices.isEmpty())
        assertEquals(listOf("a", "b"), (vm.uiState.value.content as HomeContent.Feed).page.items.map { it.id })
    }

    @Test fun aFailedQueuedRefreshKeepsTheConfirmedRemoval() = runTest {
        val confirmation = CompletableDeferred<Unit>()
        var reads = 0
        val repo = object : HomeRepository {
            override suspend fun loadHome(filter: String, continuation: String?): HomePage {
                if (++reads > 1) error("offline")
                return HomePage(listOf(video("a"), video("b")))
            }
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) { confirmation.await() }
        }
        val vm = model(repo)
        runCurrent()
        vm.menu("a", FeedMenuAction.NotInterested)
        runCurrent()
        vm.onAction(HomeAction.Refresh)
        runCurrent()
        confirmation.complete(Unit)
        advanceUntilIdle()
        val feed = vm.uiState.value.content as HomeContent.Feed
        assertEquals(listOf("b"), feed.page.items.map { it.id })
        assertTrue(feed.refreshFailed)
        assertFalse(feed.refreshing)
    }

    @Test fun leavingAndReturningToAFilterDoesNotReviveItsOldMutationOwner() = runTest {
        val old = CompletableDeferred<Unit>()
        val repo = object : HomeRepository {
            override val filters = listOf("All", "Music")
            override suspend fun loadHome(filter: String, continuation: String?) = HomePage(listOf(video("a")))
            override suspend fun feedback(video: FeedItem.Video, action: FeedMenuAction) {
                withContext(NonCancellable) { old.await(); throw com.hhst.youtubelite.watch.domain.SignInRequiredException() }
            }
        }
        val vm = model(repo)
        val notices = mutableListOf<com.hhst.youtubelite.core.AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.toList(notices) }
        runCurrent()
        vm.menu("a", FeedMenuAction.NotInterested)
        runCurrent()
        vm.onAction(HomeAction.SelectChip("Music"))
        runCurrent()
        vm.onAction(HomeAction.SelectChip("All"))
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertTrue(notices.isEmpty())
        assertEquals(listOf("a"), (vm.uiState.value.content as HomeContent.Feed).page.items.map { it.id })
    }

    @Test fun recreatedSaveSheetKeepsOnlyAnUnsubmittedPlaylistDraft() = runTest {
        for (success in listOf(true, false)) {
            val handle = SavedStateHandle()
            val saves = object : Saves() {
                override suspend fun create(title: String, description: String, videoId: String): SaveChoice {
                    if (!success) error("offline")
                    return SaveChoice("created", title, true)
                }
            }
            val vm = HomeViewModel(Feed(), handle, loadOnStart = true, saveRepository = saves)
            vm.updateSession(true, 7)
            runCurrent()
            vm.menu("a", FeedMenuAction.SaveToPlaylist)
            runCurrent()
            vm.onAction(HomeAction.OpenNewPlaylist)
            vm.onAction(HomeAction.SaveDraftChanged("New", "Description"))
            vm.onAction(HomeAction.SubmitNewPlaylist)
            advanceUntilIdle()
            val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            val restored = HomeViewModel(Feed(), restoredHandle, saveRepository = saves)
            runCurrent()
            val sheet = restored.uiState.value.overlay as HomeOverlay.Save
            assertEquals(!success, sheet.newPlaylist)
            assertEquals(if (success) "" else "New", sheet.title)
            assertEquals(if (success) "" else "Description", sheet.description)
            assertFalse(sheet.pending)
        }
    }
}
