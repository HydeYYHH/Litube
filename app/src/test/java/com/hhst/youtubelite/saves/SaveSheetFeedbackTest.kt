package com.hhst.youtubelite.saves

import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.domain.SaveRepository
import com.hhst.youtubelite.saves.presentation.SaveSheetController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveSheetFeedbackTest {
    private class Saves : SaveRepository {
        var gate = CompletableDeferred<Unit>()
        var fail = false
        var calls = 0
        override suspend fun choices(videoId: String) = listOf(SaveChoice("p", "Playlist", false))
        override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) { gate.await() }
        override suspend fun create(title: String, description: String, videoId: String): SaveChoice {
            calls++
            gate.await()
            if (fail) throw java.io.IOException("offline")
            return SaveChoice("created", title, true)
        }
        override suspend fun saveWatchLater(videoId: String) { calls++; gate.await() }
        override suspend fun savePlaylistToLibrary(playlistId: String) { calls++; gate.await() }
    }

    @Test fun pendingCreationKeepsItsDraftAndCanBeRetriedAfterFailure() = runTest {
        val saves = Saves()
        val notices = mutableListOf<AppNotice>()
        val sheet = SaveSheetController(saves, this, notices::add)
        sheet.open("v")
        runCurrent()
        sheet.openNewPlaylist()
        sheet.draftChanged("My playlist", "Description")
        sheet.submitNewPlaylist()
        assertTrue(sheet.state.value!!.pending)
        sheet.submitNewPlaylist()
        sheet.dismiss()
        sheet.openNewPlaylist()
        sheet.draftChanged("Changed", "")
        runCurrent()
        assertEquals(1, saves.calls)
        assertEquals("My playlist", sheet.state.value!!.title)
        saves.fail = true
        saves.gate.complete(Unit)
        runCurrent()
        assertFalse(sheet.state.value!!.pending)
        assertEquals("My playlist", sheet.state.value!!.title)
        assertEquals(1, notices.size)
        saves.fail = false
        sheet.submitNewPlaylist()
        runCurrent()
        assertFalse(sheet.state.value!!.newPlaylist)
        assertEquals("My playlist", sheet.state.value!!.choices!!.last().title)
    }

    @Test fun menuSavesShowProgressAndIgnoreDoubleSubmission() = runTest {
        val saves = Saves()
        val sheet = SaveSheetController(saves, this) {}
        sheet.saveWatchLater("v")
        assertTrue(sheet.writes.pending.value.isNotEmpty())
        sheet.saveWatchLater("v")
        runCurrent()
        assertEquals(1, saves.calls)
        saves.gate.complete(Unit)
        runCurrent()
        assertTrue(sheet.writes.pending.value.isEmpty())
    }
}
