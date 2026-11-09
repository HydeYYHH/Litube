package com.hhst.youtubelite.playlist

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.domain.AppendState
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.PlaylistAction
import com.hhst.youtubelite.playlist.domain.PlaylistAppendPage
import com.hhst.youtubelite.playlist.domain.PlaylistContent
import com.hhst.youtubelite.playlist.domain.PlaylistEntry
import com.hhst.youtubelite.playlist.domain.PlaylistEnumeration
import com.hhst.youtubelite.playlist.domain.PlaylistNavigationRequest
import com.hhst.youtubelite.playlist.domain.PlaylistRepository
import com.hhst.youtubelite.playlist.presentation.PlaylistViewModel
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistReadOwnershipTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun enumerationRejectsLateAppendSuccessEvenWhenTheRepositoryIgnoresCancellation() = runTest {
        for (nonCancellable in listOf(false, true)) {
            val held = HeldPage(nonCancellable)
            val repository = PagedRepository(held)
            val model = model(repository)
            advanceUntilIdle()
            model.onAction(PlaylistAction.LoadNext)
            runCurrent()
            assertTrue(held.started)
            assertEquals(AppendState.Loading, model.uiState.value.append)

            model.onAction(PlaylistAction.PlayAll)
            advanceUntilIdle()
            val completed = model.uiState.value
            assertEquals(listOf("initial", "A", "B"), model.page().entries.map { it.id })
            assertNull(model.page().nextKey)
            assertEquals(AppendState.Idle, completed.append)
            assertEquals(PlaylistEnumeration.Idle, completed.enumeration)
            assertEquals(PlaylistNavigationRequest.Queue(FixturePlaylistRepository.MOVIES,
                listOf("video-initial", "video-A", "video-B"), false), model.navigationRequests.first())

            held.complete(PlaylistAppendPage(listOf(entry("late")), "B"))
            advanceUntilIdle()
            assertEquals(completed, model.uiState.value)
            assertEquals(listOf("A", "A", "B"), repository.calls)
        }
    }

    @Test fun enumerationRejectsLateAppendFailuresInsteadOfReplacingTheCompletedState() = runTest {
        for (nonCancellable in listOf(false, true)) {
            val held = HeldPage(nonCancellable)
            val model = model(PagedRepository(held))
            advanceUntilIdle()
            model.onAction(PlaylistAction.LoadNext)
            runCurrent()
            assertTrue(held.started)
            model.onAction(PlaylistAction.PlayAll)
            advanceUntilIdle()
            val completed = model.uiState.value
            assertEquals(AppendState.Idle, completed.append)
            assertTrue(model.navigationRequests.first() is PlaylistNavigationRequest.Queue)

            held.fail(IOException("late append failed"))
            advanceUntilIdle()
            assertEquals(completed, model.uiState.value)
        }
    }

    @Test fun cancelledEnumerationCannotOverwriteANewerAppendOrPublishABatchAction() = runTest {
        for (nonCancellable in listOf(false, true)) {
            for (fail in listOf(false, true)) {
                val held = HeldPage(nonCancellable)
                val model = model(PagedRepository(held))
                advanceUntilIdle()
                model.onAction(PlaylistAction.PlayAll)
                runCurrent()
                assertTrue(held.started)
                assertTrue(model.uiState.value.enumeration is PlaylistEnumeration.Running)
                model.onAction(PlaylistAction.CancelEnumeration)
                model.onAction(PlaylistAction.LoadNext)
                advanceUntilIdle()
                val current = model.uiState.value
                assertEquals(listOf("initial", "A"), model.page().entries.map { it.id })
                assertEquals("B", model.page().nextKey)
                assertEquals(PlaylistEnumeration.Idle, current.enumeration)

                if (fail) held.fail(IOException("late enumeration failed"))
                else held.complete(PlaylistAppendPage(listOf(entry("late")), null))
                advanceUntilIdle()
                assertEquals(current, model.uiState.value)
                assertNull(withTimeoutOrNull(1) { model.navigationRequests.first() })
            }
        }
    }

    private fun model(repository: PlaylistRepository) = PlaylistViewModel(
        repository, SavedStateHandle(), FixturePlaylistRepository.MOVIES,
    )
    private fun PlaylistViewModel.page() = (uiState.value.content as PlaylistContent.Ready).page

    /** Both bridges deliberately finish after their calling Job has been cancelled. */
    private class HeldPage(private val nonCancellable: Boolean) {
        var started = false
        private val deferred = CompletableDeferred<PlaylistAppendPage>()
        private var continuation: Continuation<PlaylistAppendPage>? = null
        suspend fun await(): PlaylistAppendPage {
            started = true
            return if (nonCancellable) withContext(NonCancellable) { deferred.await() }
            else suspendCoroutine { continuation = it }
        }
        fun complete(page: PlaylistAppendPage) {
            if (nonCancellable) deferred.complete(page) else checkNotNull(continuation).resume(page)
        }
        fun fail(failure: IOException) {
            if (nonCancellable) deferred.completeExceptionally(failure)
            else checkNotNull(continuation).resumeWithException(failure)
        }
    }

    private class PagedRepository(private val held: HeldPage) : PlaylistRepository {
        val calls = mutableListOf<String>()
        override suspend fun playlist(id: String) = FixturePlaylistRepository().playlist(id)!!.copy(
            entries = listOf(entry("initial")), nextKey = "A",
        )
        override suspend fun playlistNext(id: String, cursor: String): PlaylistAppendPage {
            calls += cursor
            if (calls.size == 1) return held.await()
            return PlaylistAppendPage(listOf(entry(cursor)), if (cursor == "A") "B" else null)
        }
    }

    private companion object {
        fun entry(id: String) = PlaylistEntry(id, "video-$id", id, "Owner", "channel", "art", "1:00", "", "")
    }
}
