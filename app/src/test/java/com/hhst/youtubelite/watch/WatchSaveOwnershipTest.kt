package com.hhst.youtubelite.watch

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.saves.domain.*
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.*
import com.hhst.youtubelite.watch.presentation.WatchViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSaveOwnershipTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private open class Saves : SaveRepository {
        override suspend fun choices(videoId: String) = listOf(SaveChoice("p", "Playlist", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun create(title: String, description: String, videoId: String) = SaveChoice("p", title, true)
        override suspend fun saveWatchLater(videoId: String) = Unit
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }
    private fun model(saves: SaveRepository) = WatchViewModel(object : WatchRepository {
        override suspend fun watch(videoId: String) = FixtureWatchRepository.samplePage(videoId)
    }, SavedStateHandle(), "bunny", saveRepository = saves)

    @Test fun watchLaterContinuesAcrossSheetDismissalAndVideoNavigation() = runTest {
        val write = CompletableDeferred<Unit>()
        var cancelled = false
        val vm = model(object : Saves() {
            override suspend fun saveWatchLater(videoId: String) {
                try { write.await() } catch (error: CancellationException) { cancelled = true; throw error }
            }
        })
        val notices = mutableListOf<AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.toList(notices) }
        runCurrent()
        vm.onAction(WatchAction.OpenItemMenu("bunny"))
        vm.onAction(WatchAction.MenuAction(WatchVideoMenuAction.WatchLater))
        runCurrent()
        vm.onAction(WatchAction.Save)
        runCurrent()
        vm.onAction(WatchAction.CloseOverlay)
        vm.selectVideo("second")
        runCurrent()
        assertFalse(cancelled)
        assertTrue(vm.writes.pending.value.isNotEmpty())
        write.complete(Unit)
        advanceUntilIdle()
        assertEquals("second", vm.uiState.value.videoId)
        assertEquals(listOf(AppNotice.Message("Saved to Watch later")), notices)
        assertTrue(vm.writes.pending.value.isEmpty())
    }

    @Test fun anOldChoicesFailureCannotCloseTheReopenedSaveSheet() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val vm = model(object : Saves() {
            override suspend fun choices(videoId: String): List<SaveChoice> {
                if (++reads == 1) withContext(NonCancellable) { old.await(); throw SignInRequiredException() }
                return super.choices(videoId)
            }
        })
        val notices = mutableListOf<AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.toList(notices) }
        runCurrent()
        vm.onAction(WatchAction.Save)
        runCurrent()
        vm.onAction(WatchAction.CloseOverlay)
        vm.onAction(WatchAction.Save)
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals("p", (vm.uiState.value.overlay as WatchOverlay.Save).choices?.single()?.id)
        assertTrue(notices.isEmpty())
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
            val repo = object : WatchRepository {
                override suspend fun watch(videoId: String) = FixtureWatchRepository.samplePage(videoId)
            }
            val vm = WatchViewModel(repo, handle, "bunny", saveRepository = saves)
            runCurrent()
            vm.onAction(WatchAction.Save)
            runCurrent()
            vm.onAction(WatchAction.OpenNewPlaylist)
            vm.onAction(WatchAction.SaveDraftChanged("New", "Description"))
            vm.onAction(WatchAction.SubmitNewPlaylist)
            advanceUntilIdle()
            val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            val restored = WatchViewModel(repo, restoredHandle, "bunny", saveRepository = saves)
            runCurrent()
            val sheet = restored.uiState.value.overlay as WatchOverlay.Save
            assertEquals(!success, sheet.newPlaylist)
            assertEquals(if (success) "" else "New", sheet.title)
            assertEquals(if (success) "" else "Description", sheet.description)
            assertFalse(sheet.pending)
        }
    }
}
