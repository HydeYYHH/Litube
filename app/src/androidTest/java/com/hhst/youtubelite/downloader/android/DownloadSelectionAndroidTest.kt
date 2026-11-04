package com.hhst.youtubelite.downloader.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.PreferenceStore
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

class DownloadSelectionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun listSupportsLongPressMultiSelectAndDelete() = checkSelection(false)
    @Test fun batchSupportsLongPressMultiSelectWithoutDeleteBatchButton() = checkSelection(true)

    private fun checkSelection(inBatch: Boolean) {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, DownloaderDatabase::class.java).build()
        val coordinator = DownloadCoordinator(RoomDownloadRepository(db), NoOpTransport, NoOpScheduler,
            NoOpPublisher, ids = SeqIdFactory())
        val vm = DownloadViewModel(coordinator)
        val batch = runBlocking { coordinator.enqueueBatch(
            BatchSnapshot(BatchSource.PLAYLIST, "Selection", listOf(
                request("select-first", "Select first"), request("select-second", "Select second"),
                request("keep-third", "Keep third"))), BatchSelection(setOf(0, 1, 2)), "selection") }
        try {
            compose.setContent { AppTheme {
                DownloadManagerScreen(vm, if (inBatch) batch.batchId else null, onClose = {})
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Select first").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(compose.activity.getString(R.string.download_delete_batch)).assertDoesNotExist()
            compose.onNodeWithText("Select first").performTouchInput { longClick() }
            compose.onNodeWithText("Select second").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.download_selected_count, 2, 3)).assertIsDisplayed()
            val delete = compose.activity.getString(R.string.download_delete)
            compose.onNode(hasText(delete) and hasClickAction()).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.download_delete_local_file)).assertIsDisplayed()
            compose.onNode(hasText(delete) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Select first").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("Select second").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Keep third").assertIsDisplayed()
            assertEquals(listOf("Keep third"), runBlocking { vm.observeDownloads().first() }.map { it.title })
        } finally {
            compose.runOnUiThread { compose.activity.finish() }
            db.close()
        }
    }

    @Test fun settingsPersistAndNewDefaultOverridesRememberedQuality() {
        val store = PreferenceStore.of(compose.activity, "download-ui-preferences-test")
        store.clearAll()
        try {
            val prefs = PreferenceDownloadPrefs(store)
            prefs.setLastConfig(DownloadConfig(videoQuality = "1080p", videoItagHint = 137, includeCover = true))
            prefs.setDefaultQuality("720p")
            prefs.setWifiOnly(true)
            prefs.setMaxConnections(12)
            val restored = PreferenceDownloadPrefs(store)
            assertEquals("720p", restored.defaultQuality())
            assertEquals("720p", restored.lastConfig().videoQuality)
            assertNull(restored.lastConfig().videoItagHint)
            assertTrue(restored.lastConfig().includeCover)
            assertTrue(restored.wifiOnly())
            assertEquals(12, restored.maxConnections())
        } finally { store.clearAll() }
    }
}
