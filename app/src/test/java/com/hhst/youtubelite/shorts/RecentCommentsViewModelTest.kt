package com.hhst.youtubelite.shorts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.shorts.presentation.RecentCommentsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentCommentsViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun panel(id: String) = CommentsViewModel(FixtureCommentsRepository(), SavedStateHandle(), id)

    @Test fun reopeningRetainsTheLoadedModelAndEvictionReleasesTheOldestScope() = runTest {
        val owner = RecentCommentsViewModel()
        val store = ViewModelStore().apply { put("panels", owner) }
        try {
            val first = owner.getOrCreate("one") { panel("one") }
            advanceUntilIdle()
            val content = first.uiState.value.content
            assertSame(first, owner.getOrCreate("one") { error("Unexpected reload") })
            assertSame(content, first.uiState.value.content)
            listOf("two", "three", "four", "five").forEach { id -> owner.getOrCreate(id) { panel(id) } }
            assertEquals(setOf("two", "three", "four", "five"), owner.models.keys)
            assertFalse(requireNotNull(first.viewModelScope.coroutineContext[Job]).isActive)
        } finally { store.clear() }
        assertTrue(owner.models.isEmpty())
    }

    @Test fun unchangedSessionsRetainContentAndChangedSessionsRefreshIt() = runTest {
        val owner = RecentCommentsViewModel()
        val store = ViewModelStore().apply { put("panels", owner) }
        try {
            owner.updateSession(1)
            val model = owner.getOrCreate("one") { panel("one") }
            advanceUntilIdle()
            val content = model.uiState.value.content
            owner.updateSession(1)
            assertSame(content, model.uiState.value.content)
            owner.updateSession(2)
            advanceUntilIdle()
            assertNotSame(content, model.uiState.value.content)
        } finally { store.clear() }
    }
}
