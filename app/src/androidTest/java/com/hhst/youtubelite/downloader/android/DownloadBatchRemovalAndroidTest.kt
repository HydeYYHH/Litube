package com.hhst.youtubelite.downloader.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.downloader.core.*
import com.hhst.youtubelite.downloader.data.DownloaderDatabase
import com.hhst.youtubelite.downloader.data.RoomDownloadRepository
import com.hhst.youtubelite.downloader.ui.DownloadManagerScreen
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises production Room invalidation and the open batch screen without navigating away. */
class DownloadBatchRemovalAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun deletingVideosImmediatelyRemovesRowsAndUpdatesTotalsInTheOpenBatch() {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, DownloaderDatabase::class.java).build()
        val coordinator = DownloadCoordinator(RoomDownloadRepository(db), NoOpTransport, NoOpScheduler,
            NoOpPublisher, ids = SeqIdFactory())
        val vm = DownloadViewModel(coordinator)
        val batch = runBlocking { coordinator.enqueueBatch(
            BatchSnapshot(BatchSource.PLAYLIST, "Batch removal", listOf(
                request("delete-one", "Delete first"), request("delete-two", "Keep second"))),
            BatchSelection(setOf(0, 1)), "batch-removal") }
        try {
            compose.setContent { AppTheme(darkTheme = true) {
                DownloadManagerScreen(vm, batch.batchId, {})
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete first").fetchSemanticsNodes().isNotEmpty() }
            for ((index, title) in listOf("Delete first", "Keep second").withIndex()) {
                compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.download_more_actions))[0].performClick()
                val delete = compose.activity.getString(R.string.download_delete)
                compose.onNode(hasText(delete) and hasClickAction()).performClick()
                if (index == 1) compose.onNodeWithText(compose.activity.getString(R.string.download_delete_local_file)).performClick()
                val start = SystemClock.elapsedRealtime()
                compose.onNode(hasText(delete) and hasClickAction()).performClick()
                compose.waitUntil(2_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
                val remaining = runBlocking { vm.observeBatch(batch.batchId).first() }!!
                assertEquals(1 - index, remaining.items.size)
                assertEquals(1 - index, remaining.stats.total)
                assertTrue("Removed rows must stay gone without reopening", remaining.items.none { it.title == title })
                DeviceEvidence.writeJson("batch-delete-$index.json",
                    """{"remaining":${remaining.items.size},"total":${remaining.stats.total},"rowRemovalMs":${SystemClock.elapsedRealtime() - start}}""")
                if (index == 0) compose.onNodeWithText("Keep second").assertIsDisplayed()
            }
            compose.onNodeWithText(compose.activity.getString(R.string.download_empty)).assertIsDisplayed()
            DeviceEvidence.captureScene("youtube-batch-after-deletion")
        } finally {
            compose.runOnUiThread { compose.activity.finish() }
            db.close()
        }
    }
}
