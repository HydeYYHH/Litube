package com.hhst.youtubelite.downloader.core

import kotlinx.coroutines.flow.first
import com.hhst.youtubelite.downloader.ui.DownloadUiMapper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPartialRetryTest {

    @Test
    fun videoSuccessAttachmentFailure_isPartialNotFullyDownloaded() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true, includeCover = true)),
            "s1",
        ).created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4")
        h.coordinator.reportAssetFailed(id, 0, AssetKind.SUBTITLE, "missing caption")
        h.coordinator.reportAssetFailed(id, 0, AssetKind.COVER, "404")
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(CompletionKind.PARTIAL, snap.task.completion)
        assertEquals(DownloadPhase.COMPLETE, snap.task.phase)
        assertEquals(DownloadStatus.FAILED, snap.task.status)
        assertFalse(DownloadStateMachine.fullyDownloaded(snap.assets.map { it.asset }))
        val ui = DownloadUiMapper.video(
            snap.task.videoId,
            listOf(snap),
        )
        assertFalse(ui.watchPageDownloaded)
    }

    @Test
    fun attachmentsOnlySuccess_isNotFullyDownloaded() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeCover = true)),
            "s1",
        ).created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.COVER, "file://a.jpg")
        h.coordinator.reportAssetFailed(id, 0, AssetKind.VIDEO, "mux fail")
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(CompletionKind.PARTIAL, snap.task.completion)
        assertFalse(DownloadStateMachine.fullyDownloaded(snap.assets.map { it.asset }))
    }

    @Test
    fun retryFailed_onlyResetsFailedParts() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true)),
            "s1",
        ).created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4")
        h.coordinator.reportAssetFailed(id, 0, AssetKind.SUBTITLE, "boom")
        h.coordinator.retryFailed(DownloadTarget.Task(id))
        val snap = h.repo.transact { snapshot(id) }!!
        val video = snap.assets.first { it.asset.kind == AssetKind.VIDEO }.asset
        val sub = snap.assets.first { it.asset.kind == AssetKind.SUBTITLE }.asset
        assertTrue(video.published)
        assertEquals("file://a.mp4", video.publishedUri)
        assertFalse(sub.failed)
        assertFalse(sub.published)
        assertEquals(DownloadStatus.QUEUED, sub.status)
        assertEquals(DownloadStatus.QUEUED, snap.task.status)
        assertEquals(CompletionKind.PARTIAL, snap.task.completion)
        assertTrue(snap.task.executionGeneration > 0)
    }

    @Test
    fun redownload_createsNewTaskAndKeepsOriginal() = runTest {
        val h = DownloadHarness()
        val first = h.coordinator.enqueue(request("a"), "s1")
        val originalId = first.created.single().taskId
        h.coordinator.reportAssetPublished(originalId, 0, AssetKind.VIDEO, "file://a.mp4")
        val redo = h.coordinator.redownload(DownloadTarget.Task(originalId))
        assertEquals(1, redo.newCount)
        val newId = redo.created.single().taskId
        assertNotEquals(originalId, newId)
        val original = h.repo.transact { snapshot(originalId) }!!
        val copy = h.repo.transact { snapshot(newId) }!!
        assertEquals(CompletionKind.FULL, original.task.completion)
        assertEquals("file://a.mp4", original.assets.single().asset.publishedUri)
        assertEquals(CompletionKind.NONE, copy.task.completion)
        assertFalse(copy.assets.single().asset.published)
        val all = h.coordinator.observeVideo(vid("a")).first()
        assertEquals(2, all.size)
    }
}
