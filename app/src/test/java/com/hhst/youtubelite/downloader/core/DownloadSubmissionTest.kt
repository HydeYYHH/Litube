package com.hhst.youtubelite.downloader.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSubmissionTest {

    @Test
    fun submissionId_replaysOriginalResult() = runTest {
        val h = DownloadHarness()
        val first = h.coordinator.enqueue(request("a"), "sub-1")
        val second = h.coordinator.enqueue(request("a"), "sub-1")
        assertEquals(1, first.newCount)
        assertEquals(0, first.skippedCount)
        assertEquals(first, second)
        val tasks = h.coordinator.observeDownloads().first()
        assertEquals(1, tasks.size)
    }

    @Test
    fun fingerprint_skipsIdenticalRequest() = runTest {
        val h = DownloadHarness()
        val first = h.coordinator.enqueue(request("a"), "sub-1")
        val second = h.coordinator.enqueue(request("a"), "sub-2")
        assertEquals(1, first.newCount)
        assertEquals(0, second.newCount)
        assertEquals(1, second.skippedCount)
        assertEquals(first.created.single().taskId, second.existing.single().taskId)
        assertEquals(false, second.existing.single().owned)
        assertEquals(1, h.coordinator.observeDownloads().first().size)
    }

    @Test
    fun fingerprint_doesNotSkipDifferentQuality() = runTest {
        val h = DownloadHarness()
        h.coordinator.enqueue(request("a", config = DownloadConfig(videoQuality = "720p")), "s1")
        val second = h.coordinator.enqueue(
            request("a", config = DownloadConfig(videoQuality = "1080p")),
            "s2",
        )
        assertEquals(1, second.newCount)
        assertEquals(0, second.skippedCount)
        assertEquals(2, h.coordinator.observeDownloads().first().size)
    }

    @Test
    fun cancelledTask_isNotReusedByFingerprint() = runTest {
        val h = DownloadHarness()
        val first = h.coordinator.enqueue(request("a"), "s1")
        h.coordinator.cancel(DownloadTarget.Task(first.created.single().taskId))
        val second = h.coordinator.enqueue(request("a"), "s2")
        assertEquals(1, second.newCount)
        assertNotEquals(first.created.single().taskId, second.created.single().taskId)
    }

    @Test
    fun allSkipped_isNoNewTasks() = runTest {
        val h = DownloadHarness()
        h.coordinator.enqueue(request("a"), "s1")
        h.coordinator.enqueue(request("b"), "s2")
        val batch = h.coordinator.enqueueBatch(
            snapshot = BatchSnapshot(
                source = BatchSource.PLAYLIST,
                name = "P",
                items = listOf(request("a"), request("b")),
            ),
            selection = BatchSelection(setOf(0, 1)),
            submissionId = "s3",
        )
        assertEquals(0, batch.newCount)
        assertEquals(2, batch.skippedCount)
        assertTrue(batch.created.isEmpty())
        assertEquals(2, batch.existing.size)
    }
}
