package com.hhst.youtubelite.downloader.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPauseCancelRaceTest {

    @Test
    fun userPause_notOverriddenByLateRunningOrWaitingNetwork() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.pause(DownloadTarget.Task(id))

        assertFalse(h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER))
        assertFalse(
            h.coordinator.reportExecution(id, 0, DownloadStatus.WAITING_NETWORK, DownloadPhase.TRANSFER),
        )
        h.coordinator.onNetworkRestored()
        h.coordinator.onProcessRestore()

        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadStatus.PAUSED, snap.task.status)
        assertTrue(snap.task.userPaused)
        assertFalse(snap.schedule!!.pending)
    }

    @Test
    fun userCancel_notOverriddenByLateCallbacksOrRestore() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        val componentId = h.repo.transact { snapshot(id) }!!.assets.single().components.first().component.id
        h.coordinator.reportChunk(
            id,
            0,
            DownloadChunk("tmp", componentId, 0, 10, 11, verified = true, tempPath = "part.tmp"),
        )
        h.coordinator.cancel(DownloadTarget.Task(id))

        assertFalse(h.coordinator.reportExecution(id, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER))
        assertFalse(
            h.coordinator.reportExecution(id, 0, DownloadStatus.WAITING_NETWORK, DownloadPhase.TRANSFER),
        )
        h.coordinator.onNetworkRestored()
        h.coordinator.onProcessRestore()

        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadStatus.CANCELLED, snap.task.status)
        assertTrue(snap.task.userCancelled)
        assertTrue(snap.assets.single().components.first().chunks.isEmpty())
        assertEquals(PublishPhase.DELETE_INTENT, snap.assets.single().publish!!.phase)
        assertEquals(listOf(id), h.transport.cancelled)
    }

    @Test
    fun cancel_keepsSuccessfullyPublishedFiles() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true)),
            "s1",
        ).created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4")
        h.coordinator.cancel(DownloadTarget.Task(id))
        val snap = h.repo.transact { snapshot(id) }!!
        val video = snap.assets.first { it.asset.kind == AssetKind.VIDEO }
        val sub = snap.assets.first { it.asset.kind == AssetKind.SUBTITLE }
        assertTrue(video.asset.published)
        assertEquals("file://a.mp4", video.asset.publishedUri)
        assertEquals(PublishPhase.PUBLISHED, video.publish!!.phase)
        assertTrue(video.publish!!.keepOnCancel)
        assertEquals(PublishPhase.DELETE_INTENT, sub.publish!!.phase)
        assertEquals(DownloadStatus.CANCELLED, snap.task.status)
    }

    @Test
    fun processRestore_movesRunningToWaitingSystem_butRespectsPause() = runTest {
        val h = DownloadHarness()
        val running = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val paused = h.coordinator.enqueue(request("b"), "s2").created.single().taskId
        h.coordinator.reportExecution(running, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.reportExecution(paused, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.pause(DownloadTarget.Task(paused))
        h.coordinator.onProcessRestore()
        assertEquals(DownloadStatus.WAITING_SYSTEM, h.repo.transact { getTask(running) }!!.status)
        assertEquals(DownloadStatus.PAUSED, h.repo.transact { getTask(paused) }!!.status)
    }
}
