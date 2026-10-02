package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.core.vid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadViewModelTest {

    @Test
    fun observeApis_mapPersistedState() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val result = vm.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true)),
            "s1",
        )
        val taskId = result.created.single().taskId

        val listed = vm.observeDownloads().first()
        assertEquals(1, listed.size)
        assertEquals(taskId, listed.single().taskId)
        assertFalse(listed.single().fullyDownloaded)

        h.coordinator.reportAssetPublished(taskId, 0, AssetKind.VIDEO, "file://a.mp4")
        val video = vm.observeVideo(vid("a")).first()
        assertFalse(video.watchPageDownloaded)

        h.coordinator.reportAssetFailed(taskId, 0, AssetKind.SUBTITLE, "no")
        val partial = vm.observeVideo(vid("a")).first()
        assertFalse(partial.watchPageDownloaded)
        assertEquals(listOf(AssetKind.SUBTITLE), partial.tasks.single().failedKinds)

        h.coordinator.retryFailed(DownloadTarget.Task(taskId))
        val gen = h.repo.transact { getTask(taskId) }!!.executionGeneration
        h.coordinator.reportAssetPublished(taskId, gen, AssetKind.SUBTITLE, "file://a.en.srt")
        val done = vm.observeVideo(vid("a")).first()
        assertTrue(done.watchPageDownloaded)
        assertTrue(done.tasks.single().fullyDownloaded)

        val batch = vm.observeBatch(result.batchId).first()!!
        assertEquals(1, batch.stats.completed)
        assertEquals(taskId, batch.items.single().taskId)
    }

    @Test
    fun observeDownloads_hidesRemoved() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val id = vm.enqueue(request("a"), "s1").created.single().taskId
        vm.remove(DownloadTarget.Task(id), com.hhst.youtubelite.downloader.core.RemoveMode.RECORD_ONLY)
        assertTrue(vm.observeDownloads().first().isEmpty())
        assertEquals(
            1,
            vm.observeDownloads(DownloadFilter(includeRemoved = true)).first().size,
        )
    }
}
