package com.hhst.youtubelite.downloader.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadGenerationTest {

    @Test
    fun staleGeneration_runningCallbackIgnored() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.pause(DownloadTarget.Task(id))
        val genAfterPause = h.repo.transact { getTask(id) }!!.executionGeneration
        assertTrue(genAfterPause > 0)
        assertFalse(
            h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER),
        )
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadStatus.PAUSED, snap.task.status)
        assertTrue(snap.task.userPaused)
        assertEquals(DownloadPhase.TRANSFER, snap.task.phase)
    }

    @Test
    fun staleGeneration_chunkAndPublishIgnored() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.pause(DownloadTarget.Task(id))
        val componentId = h.repo.transact { snapshot(id) }!!.assets.single().components.first().component.id
        assertFalse(
            h.coordinator.reportChunk(
                id,
                0,
                DownloadChunk("late", componentId, 0, 1, 2, verified = true),
            ),
        )
        assertFalse(h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://late.mp4"))
        val snap = h.repo.transact { snapshot(id) }!!
        assertTrue(snap.assets.single().components.first().chunks.isEmpty())
        assertFalse(snap.assets.single().asset.published)
        assertEquals(CompletionKind.NONE, snap.task.completion)
    }

    @Test
    fun currentGeneration_isApplied() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        assertTrue(h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER))
        assertEquals(DownloadStatus.RUNNING, h.repo.transact { getTask(id) }!!.status)
    }
}
