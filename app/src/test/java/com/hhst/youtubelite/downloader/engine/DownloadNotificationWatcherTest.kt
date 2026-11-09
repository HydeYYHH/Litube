package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.*
import com.hhst.youtubelite.downloader.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadNotificationWatcherTest {
    @Test fun manyTasksUseOneViewReadPerBatchWithoutTaskToBatchLookups() = runTest {
        val h = SchedulerHarness(33)
        val inputs = (0 until 40).map { request("v$it") }
        val batch = h.wired.enqueueBatch(BatchSnapshot(BatchSource.PLAYLIST, "Playlist", inputs),
            BatchSelection(inputs.indices.toSet()), "playlist")
        val other = h.wired.enqueue(request("other"), "other")
        var taskLookups = 0
        var snapshotEmissions = 0
        val batchReads = mutableListOf<String>()
        val counted = object : DownloadRepository by h.repo {
            override fun observeDownloads(filter: DownloadFilter) =
                h.repo.observeDownloads(filter).onEach { snapshotEmissions++ }

            override suspend fun <T> transact(block: suspend DownloadSession.() -> T): T = h.repo.transact {
                val actual = this
                block(object : DownloadSession by actual {
                    override suspend fun itemsForTask(taskId: String): List<DownloadItem> {
                        taskLookups++
                        return actual.itemsForTask(taskId)
                    }
                    override suspend fun batchView(batchId: String): BatchView? {
                        batchReads += batchId
                        return actual.batchView(batchId)
                    }
                })
            }
        }
        val coordinator = DownloadCoordinator(counted)
        val port = RecordingNotificationPort()
        val watcher = DownloadNotificationWatcher(coordinator, counted, DownloadNotificationController(port), backgroundScope)
        watcher.start()
        watcher.start()
        runCurrent()
        assertEquals(0, taskLookups)
        assertEquals(setOf(batch.batchId, other.batchId), batchReads.toSet())
        assertEquals(2, batchReads.size)
        assertEquals(2, port.posted.size)
        batchReads.clear()
        snapshotEmissions = 0
        h.wired.pause(DownloadTarget.Batch(batch.batchId))
        runCurrent()
        assertEquals(0, taskLookups)
        // Scheduling updates may emit intermediate snapshots; each emission reads each distinct batch exactly once.
        assertTrue(snapshotEmissions > 0)
        assertEquals(2 * snapshotEmissions, batchReads.size)
        assertEquals(snapshotEmissions, batchReads.count { it == batch.batchId })
        assertEquals(snapshotEmissions, batchReads.count { it == other.batchId })
        assertTrue(port.posted.last { it.second.batchId == batch.batchId }.second.showResume)
    }
}
