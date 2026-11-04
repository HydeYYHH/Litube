package com.hhst.youtubelite.saves

import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.saves.domain.*
import com.hhst.youtubelite.saves.presentation.SaveSheetController
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveTaskOwnershipTest {
    private open class Saves : SaveRepository {
        override suspend fun choices(videoId: String) = listOf(SaveChoice("p", "Playlist", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) = Unit
        override suspend fun create(title: String, description: String, videoId: String) = SaveChoice("p", title, true)
        override suspend fun saveWatchLater(videoId: String) = Unit
        override suspend fun savePlaylistToLibrary(playlistId: String) = Unit
    }

    @Test fun openingAndDismissingASheetPreservesAnUnrelatedBackgroundSave() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val notices = mutableListOf<AppNotice>()
        val sheet = SaveSheetController(object : Saves() {
            override suspend fun saveWatchLater(videoId: String) {
                try { gate.await() } catch (error: CancellationException) { cancelled = true; throw error }
            }
        }, this, notices::add)
        sheet.saveWatchLater("a")
        runCurrent()
        sheet.open("b")
        runCurrent()
        sheet.dismiss()
        runCurrent()
        val pendingBeforeCompletion = sheet.writes.pending.value.isNotEmpty()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(cancelled)
        assertTrue(pendingBeforeCompletion)
        assertEquals(listOf(AppNotice.Message("Saved to Watch later")), notices)
    }

    @Test fun anOldSheetAuthorizationFailureCannotCloseAReopenedSheetForTheSameVideo() = runTest {
        val old = CompletableDeferred<Unit>()
        var reads = 0
        val notices = mutableListOf<AppNotice>()
        val sheet = SaveSheetController(object : Saves() {
            override suspend fun choices(videoId: String): List<SaveChoice> {
                if (++reads == 1) withContext(NonCancellable) { old.await(); throw SignInRequiredException() }
                return super.choices(videoId)
            }
        }, this, notices::add)
        sheet.open("same")
        runCurrent()
        sheet.close()
        sheet.open("same")
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals("same", sheet.state.value?.videoId)
        assertEquals("p", sheet.state.value?.choices?.single()?.id)
        assertTrue(notices.isEmpty())
    }

    @Test fun resetSessionRetiresBothKindsOfWorkWithoutWaitingForTheOldAccount() = runTest {
        val old = CompletableDeferred<Unit>()
        val notices = mutableListOf<AppNotice>()
        var reads = 0
        val sheet = SaveSheetController(object : Saves() {
            override suspend fun choices(videoId: String): List<SaveChoice> {
                if (++reads == 1) withContext(NonCancellable) { old.await(); throw SignInRequiredException() }
                return super.choices(videoId)
            }
            override suspend fun saveWatchLater(videoId: String) {
                withContext(NonCancellable) { old.await(); throw SignInRequiredException() }
            }
        }, this, notices::add)
        sheet.saveWatchLater("old")
        sheet.open("old")
        runCurrent()
        sheet.resetSession()
        sheet.open("new")
        runCurrent()
        assertEquals("p", sheet.state.value?.choices?.single()?.id)
        assertTrue(sheet.writes.pending.value.isEmpty())
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", sheet.state.value?.videoId)
        assertTrue(notices.isEmpty())
    }

    @Test fun creatingWhileChoicesLoadOwnsPendingUntilTheWriteFinishes() = runTest {
        val choices = CompletableDeferred<Unit>()
        val creation = CompletableDeferred<Unit>()
        val sheet = SaveSheetController(object : Saves() {
            override suspend fun choices(videoId: String): List<SaveChoice> {
                withContext(NonCancellable) { choices.await() }
                return super.choices(videoId)
            }
            override suspend fun create(title: String, description: String, videoId: String): SaveChoice {
                creation.await()
                return SaveChoice("created", title, true)
            }
        }, this, {})
        sheet.open("a")
        runCurrent()
        sheet.openNewPlaylist()
        sheet.draftChanged("New", "")
        sheet.submitNewPlaylist()
        runCurrent()
        choices.complete(Unit)
        runCurrent()
        assertTrue(sheet.state.value?.pending == true)
        creation.complete(Unit)
        advanceUntilIdle()
        assertEquals("created", sheet.state.value?.choices?.single()?.id)
        assertFalse(sheet.state.value?.pending == true)
    }
}
