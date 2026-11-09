package com.hhst.youtubelite.home

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.home.data.FixtureHomeRepository
import com.hhst.youtubelite.home.domain.HomeRepository
import com.hhst.youtubelite.home.domain.HomeAction
import com.hhst.youtubelite.home.domain.HomeContent
import com.hhst.youtubelite.home.domain.HomePage
import com.hhst.youtubelite.home.presentation.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeForegroundTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private var loads = 0
    private var signal = 0L
    private var nowMs = 1_000_000L

    private fun repository(block: suspend (String, String?) -> HomePage) = object : HomeRepository {
        override suspend fun loadHome(filter: String, continuation: String?) = block(filter, continuation)
    }

    private fun viewModel(): HomeViewModel = HomeViewModel(
        repository { _, _ -> loads++; FixtureHomeRepository.samplePage() },
        SavedStateHandle(),
        reportSignal = { signal },
        now = { nowMs },
    )

    private fun loadFeed(vm: HomeViewModel) {
        vm.onAction(HomeAction.Refresh)
    }

    @Test fun foregroundWithoutSignalOrStalenessDoesNothing() = runTest {
        val vm = viewModel()
        loadFeed(vm)
        advanceUntilIdle()
        assertEquals(1, loads)
        vm.onForeground()
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, loads)
    }

    @Test fun aReportSignalTriggersOneQuietRefresh() = runTest {
        val vm = viewModel()
        loadFeed(vm)
        advanceUntilIdle()
        vm.onForeground() // adopts the current signal
        advanceUntilIdle()
        assertEquals(1, loads)
        signal += 1
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, loads)
        // The feed stayed painted the whole time: no full-page Loading flash.
        assertTrue(vm.uiState.value.content is HomeContent.Feed)
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, loads)
    }

    @Test fun stalenessTriggersOneQuietRefreshPerWindow() = runTest {
        val vm = viewModel()
        loadFeed(vm)
        advanceUntilIdle()
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, loads)
        nowMs += 16 * 60 * 1000L
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, loads)
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, loads)
    }

    @Test fun anEmptyHomeQuietRefreshesOnAReportSignal() = runTest {
        val vm = HomeViewModel(repository { _, _ -> loads++; HomePage(emptyList()) },
            SavedStateHandle(), reportSignal = { signal }, now = { nowMs })
        loadFeed(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, loads)
        signal += 1
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, loads)
        // The empty card stayed painted the whole time (quiet re-check).
        assertTrue(vm.uiState.value.content is HomeContent.Empty)
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(2, loads)
    }

    @Test fun aFailedHomeKeepsItsOwnRetryPath() = runTest {
        val vm = HomeViewModel(repository { _, _ -> loads++; throw java.io.IOException("offline") },
            SavedStateHandle(), reportSignal = { signal }, now = { nowMs })
        loadFeed(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is HomeContent.Failed)
        signal += 1
        vm.onForeground()
        advanceUntilIdle()
        assertEquals(1, loads)
    }

    @Test fun anActiveRefreshBlocksAnother() = runTest {
        var gated = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = HomeViewModel(repository { _, _ ->
            loads++
            if (gated) gate.await()
            FixtureHomeRepository.samplePage()
        }, SavedStateHandle(), reportSignal = { signal }, now = { nowMs })
        loadFeed(vm)
        advanceUntilIdle()
        assertEquals(1, loads)
        vm.onForeground()
        advanceUntilIdle()
        gated = true
        signal += 1
        vm.onForeground()
        runCurrent()
        assertEquals(2, loads)
        vm.onForeground()
        signal += 1
        vm.onForeground()
        runCurrent()
        assertEquals(2, loads)
        gate.complete(Unit)
        advanceUntilIdle()
        // Signals arriving mid-load merge into one pending re-check once the busy request finishes.
        assertEquals(3, loads)
        assertTrue(vm.uiState.value.content is HomeContent.Feed)
    }
}
