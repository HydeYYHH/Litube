package com.hhst.youtubelite.downloader.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.BatchView
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.NoOpPublisher
import com.hhst.youtubelite.downloader.core.NoOpScheduler
import com.hhst.youtubelite.downloader.core.NoOpTransport
import com.hhst.youtubelite.downloader.core.TaskSnapshot
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.ui.DownloadManagerScreen
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DownloadContinuityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun reset() { resetOverlayState() }

    @Test fun firstReadAndReturningFromDetailsNeverFlashAnEmptyList() {
        val harness = DownloadAndroidHarness()
        runBlocking { harness.seedList() }
        val firstRead = CompletableDeferred<Unit>()
        val anotherRead = CompletableDeferred<Unit>()
        val batchRead = CompletableDeferred<Unit>()
        var subscriptions = 0
        val repository = object : DownloadRepository by harness.repo {
            override fun observeDownloads(filter: DownloadFilter): Flow<List<TaskSnapshot>> = flow {
                subscriptions++
                if (subscriptions == 1) firstRead.await() else anotherRead.await()
                emitAll(harness.repo.observeDownloads(filter))
            }
            override fun observeBatch(batchId: String): Flow<BatchView?> = flow {
                batchRead.await()
                emitAll(harness.repo.observeBatch(batchId))
            }
        }
        val viewModel = DownloadViewModel(DownloadCoordinator(repository, NoOpTransport, NoOpScheduler, NoOpPublisher))
        val empty = compose.activity.getString(R.string.download_empty)
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                DownloadManagerScreen(viewModel, initialBatchId = null, onClose = {})
            }
        }
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { firstRead.complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Running clip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Running clip").assertIsDisplayed().performClick()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(empty).assertDoesNotExist()
        }
        compose.runOnIdle { batchRead.complete(Unit) }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Running clip").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(empty).assertDoesNotExist()
            compose.onNodeWithText("Running clip").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals("The manager keeps its list subscription while details are open", 1, subscriptions) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun aConfirmedMissingBatchReachesTheEmptyStateAfterItsFirstRead() {
        val harness = DownloadAndroidHarness()
        val batchRead = CompletableDeferred<Unit>()
        val repository = object : DownloadRepository by harness.repo {
            override fun observeBatch(batchId: String): Flow<BatchView?> = flow {
                batchRead.await()
                emit(null)
            }
        }
        val viewModel = DownloadViewModel(DownloadCoordinator(repository))
        val empty = compose.activity.getString(R.string.download_empty)
        compose.setContent {
            AppTheme(darkTheme = true, dynamicColor = false) {
                DownloadManagerScreen(viewModel, initialBatchId = "missing", onClose = {})
            }
        }
        compose.onNodeWithText(empty).assertDoesNotExist()
        compose.runOnIdle { batchRead.complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(empty).assertIsDisplayed()
    }
}
