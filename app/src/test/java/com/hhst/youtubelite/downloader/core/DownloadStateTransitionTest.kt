package com.hhst.youtubelite.downloader.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTransitionTest {

    @Test
    fun enqueue_startsQueuedResolve() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadPhase.RESOLVE, snap.task.phase)
        assertEquals(DownloadStatus.QUEUED, snap.task.status)
        assertEquals(CompletionKind.NONE, snap.task.completion)
        assertEquals(FileAvailability.MISSING, snap.task.fileAvailability)
        assertEquals(1, snap.assets.size)
        assertEquals(AssetKind.VIDEO, snap.assets.single().asset.kind)
        assertEquals(2, snap.assets.single().components.size)
        assertTrue(snap.schedule!!.pending)
    }

    @Test
    fun runningTransfer_thenCompleteKeepsPhaseCompleteWhenFileMissing() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        assertTrue(h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER))
        assertTrue(h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4"))
        assertTrue(
            h.coordinator.reportFileAvailability(id, 0, AssetKind.VIDEO, FileAvailability.MISSING),
        )
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadPhase.COMPLETE, snap.task.phase)
        assertEquals(CompletionKind.FULL, snap.task.completion)
        assertEquals(FileAvailability.MISSING, snap.task.fileAvailability)
        assertTrue(DownloadStateMachine.fullyDownloaded(snap.assets.map { it.asset }))
    }

    @Test
    fun pauseDuringTransfer_dropsUnverifiedKeepsValidChunks() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        val componentId = h.repo.transact { snapshot(id) }!!.assets.single().components.first().component.id
        h.coordinator.reportChunk(
            id,
            0,
            DownloadChunk("good", componentId, 0, 99, 100, verified = true, tempPath = "good.tmp"),
        )
        h.coordinator.reportChunk(
            id,
            0,
            DownloadChunk("bad", componentId, 100, 199, 20, verified = false, tempPath = "bad.tmp"),
        )
        h.coordinator.pause(DownloadTarget.Task(id))
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadStatus.PAUSED, snap.task.status)
        assertTrue(snap.task.userPaused)
        assertEquals(DownloadPhase.TRANSFER, snap.task.phase)
        val chunks = snap.assets.single().components.first().chunks
        assertEquals(listOf("good"), chunks.map { it.id })
        assertEquals(listOf(id), h.transport.cancelled)
    }

    @Test
    fun pauseDuringMerge_keepsInputsAndResumeRedoesPhase() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.MERGE_VERIFY)
        val componentId = h.repo.transact { snapshot(id) }!!.assets.single().components.first().component.id
        h.coordinator.reportChunk(
            id,
            0,
            DownloadChunk("in", componentId, 0, 9, 10, verified = true, tempPath = "in.tmp"),
        )
        h.coordinator.pause(DownloadTarget.Task(id))
        val paused = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadPhase.MERGE_VERIFY, paused.task.phase)
        assertEquals(DownloadStatus.PAUSED, paused.task.status)
        assertEquals(1, paused.assets.single().components.first().chunks.size)
        assertTrue(h.transport.cancelled.isEmpty())

        h.coordinator.resume(DownloadTarget.Task(id))
        val resumed = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadPhase.MERGE_VERIFY, resumed.task.phase)
        assertEquals(DownloadStatus.QUEUED, resumed.task.status)
        assertFalse(resumed.task.userPaused)
        assertEquals(2L, resumed.task.executionGeneration)
        assertEquals(1, resumed.assets.single().components.first().chunks.size)
    }
}
