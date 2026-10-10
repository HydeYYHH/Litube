package com.hhst.youtubelite.downloader.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadBatchStatsTest {

    @Test
    fun removingOwnedVideoImmediatelyUpdatesBatchItemsAndTotals() = runTest {
        val h = DownloadHarness()
        val batch = h.coordinator.enqueueBatch(BatchSnapshot(BatchSource.PLAYLIST, "Delete test",
            listOf(request("a"), request("b"))), BatchSelection(setOf(0, 1)), "delete")
        val removedItem = h.coordinator.observeBatch(batch.batchId).first()!!.items.first().item
        h.coordinator.remove(DownloadTarget.Item(removedItem.id), RemoveMode.RECORD_ONLY)
        val remaining = h.coordinator.observeBatch(batch.batchId).first()!!
        assertEquals(listOf(batch.created[1].taskId), remaining.items.map { it.task.task.id })
        assertEquals(1, remaining.stats.total)
        assertTrue(h.repo.transact { getTask(removedItem.taskId) }!!.removed)
        h.coordinator.remove(DownloadTarget.Batch(batch.batchId), RemoveMode.RECORD_ONLY)
        assertTrue(h.coordinator.observeBatch(batch.batchId).first()!!.items.isEmpty())
        assertEquals(0, h.coordinator.observeBatch(batch.batchId).first()!!.stats.total)
    }

    @Test
    fun removingReferenceOnlyHidesThatBatchRowAndKeepsTheOwnedTask() = runTest {
        val h = DownloadHarness()
        val original = h.coordinator.enqueue(request("a"), "original")
        val batch = h.coordinator.enqueueBatch(BatchSnapshot(BatchSource.PLAYLIST, "Reference",
            listOf(request("a"))), BatchSelection(setOf(0)), "reference")
        val reference = h.coordinator.observeBatch(batch.batchId).first()!!.items.single().item
        h.coordinator.remove(DownloadTarget.Item(reference.id), RemoveMode.RECORD_AND_FILES)
        assertTrue(h.coordinator.observeBatch(batch.batchId).first()!!.items.isEmpty())
        assertEquals(1, h.coordinator.observeBatch(original.batchId).first()!!.stats.total)
        assertFalse(h.repo.transact { getTask(reference.taskId) }!!.removed)
    }

    @Test
    fun stats_splitCompletedFailedCancelledSkipped() = runTest {
        val h = DownloadHarness()
        val existing = h.coordinator.enqueue(request("skip"), "s0")
        val batch = h.coordinator.enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.PLAYLIST,
                name = "Mix",
                items = listOf(request("ok"), request("bad"), request("stop"), request("skip")),
            ),
            selection = BatchSelection(setOf(0, 1, 2, 3)),
            submissionId = "s1",
        )
        assertEquals(3, batch.newCount)
        assertEquals(1, batch.skippedCount)

        val ok = batch.created[0].taskId
        val bad = batch.created[1].taskId
        val stop = batch.created[2].taskId

        h.coordinator.reportExecution(ok, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.reportAssetPublished(ok, 0, AssetKind.VIDEO, "file://ok.mp4")
        h.coordinator.reportAssetFailed(bad, 0, AssetKind.VIDEO, "http 403")
        h.coordinator.cancel(DownloadTarget.Task(stop))

        val view = h.coordinator.observeBatch(batch.batchId).first()!!
        assertEquals(1, view.stats.completed)
        assertEquals(1, view.stats.failed)
        assertEquals(1, view.stats.cancelled)
        assertEquals(1, view.stats.skipped)
        assertEquals(4, view.stats.total)
        assertEquals(existing.created.single().taskId, view.items.single { it.skipped }.task.task.id)
    }

    @Test
    fun skipped_isNotCountedAsNewlyCompleted() = runTest {
        val h = DownloadHarness()
        val first = h.coordinator.enqueue(request("a"), "s1")
        h.coordinator.reportAssetPublished(first.created.single().taskId, 0, AssetKind.VIDEO, "file://a.mp4")
        val batch = h.coordinator.enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.PLAYLIST,
                name = "Later",
                items = listOf(request("a"), request("b")),
            ),
            selection = BatchSelection(setOf(0, 1)),
            submissionId = "s2",
        )
        val b = batch.created.single().taskId
        h.coordinator.reportAssetPublished(b, 0, AssetKind.VIDEO, "file://b.mp4")
        val stats = h.coordinator.observeBatch(batch.batchId).first()!!.stats
        assertEquals(1, stats.completed)
        assertEquals(1, stats.skipped)
        assertEquals(0, stats.failed)
    }

    @Test
    fun cancelBatch_doesNotCancelReferencedTasks() = runTest {
        val h = DownloadHarness()
        val first = h.coordinator.enqueue(request("keep"), "s1")
        val keepId = first.created.single().taskId
        val second = h.coordinator.enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.PLAYLIST,
                name = "B",
                items = listOf(request("keep"), request("drop")),
            ),
            selection = BatchSelection(setOf(0, 1)),
            submissionId = "s2",
        )
        val dropId = second.created.single().taskId
        h.coordinator.cancel(DownloadTarget.Batch(second.batchId))

        val keep = h.coordinator.observeDownloads().first().first { it.task.id == keepId }
        val drop = h.repo.transact { snapshot(dropId) }!!
        assertFalse(keep.task.userCancelled)
        assertEquals(DownloadStatus.QUEUED, keep.task.status)
        assertTrue(drop.task.userCancelled)
        assertEquals(DownloadStatus.CANCELLED, drop.task.status)
    }

    @Test
    fun allSkip_newCountZero() = runTest {
        val h = DownloadHarness()
        h.coordinator.enqueue(request("a"), "s1")
        val result = h.coordinator.enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.QUEUE,
                name = "Q",
                items = listOf(request("a")),
            ),
            selection = BatchSelection(setOf(0)),
            submissionId = "s2",
        )
        assertEquals(0, result.newCount)
        assertEquals(1, result.skippedCount)
        val stats = h.coordinator.observeBatch(result.batchId).first()!!.stats
        assertEquals(1, stats.skipped)
        assertEquals(0, stats.completed)
    }
}
