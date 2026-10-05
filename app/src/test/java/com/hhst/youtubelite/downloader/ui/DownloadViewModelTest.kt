package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.ResolvedComponentUpdate
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.RemoveMode
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
        vm.remove(DownloadTarget.Task(id), RemoveMode.RECORD_ONLY).join()
        assertTrue(vm.observeDownloads().first().isEmpty())
        assertEquals(
            1,
            vm.observeDownloads(DownloadFilter(includeRemoved = true)).first().size,
        )
    }

    @Test
    fun progress_ignoresUnusedMuxedInputAndUnknownSidecars() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val id = vm.enqueue(request("a", config = DownloadConfig(includeCover = true)), "s1").created.single().taskId
        h.coordinator.reportResolved(id, 0, listOf(ResolvedComponentUpdate(
            AssetKind.VIDEO, InputComponentKind.VIDEO, mimeType = "video/mp4", expectedBytes = 1024)))
        val component = h.repo.transact { snapshot(id) }!!.assets.first { it.asset.kind == AssetKind.VIDEO }
            .components.first { it.component.kind == InputComponentKind.VIDEO }.component
        val chunk = DownloadChunk("${component.id}:0", component.id, 0, 511, 512, false)
        h.coordinator.reportChunk(id, 0, chunk)
        val progress = vm.observeDownloads().first().single()
        assertEquals(512L, progress.progressBytes)
        assertEquals(1024L, progress.expectedBytes)
        assertEquals(0.5f, DownloadPresentation.progressFraction(progress.progressBytes, progress.expectedBytes)!!, 0f)

        // Starting a full fetch discards the old ranges, then promotes the
        // server's trusted length without double-counting live bytes.
        h.coordinator.reportChunk(id, 0, chunk.copy(receivedBytes = 0, endByte = 0), 2048)
        h.coordinator.reportChunk(id, 0, chunk.copy(receivedBytes = 256, endByte = 255), 2048)
        val restarted = vm.observeDownloads().first().single()
        assertEquals(256L, restarted.progressBytes)
        assertEquals(2048L, restarted.expectedBytes)
    }

    @Test
    fun failedAsset_exposesReasonEvenWhenVideoWasPublished() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val id = vm.enqueue(request("a", config = DownloadConfig(includeSubtitle = true)), "s1").created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4")
        h.coordinator.reportAssetFailed(id, 0, AssetKind.SUBTITLE, "http-404")
        val item = vm.observeDownloads().first().single()
        assertEquals("http-404", DownloadPresentation.failureReason(item))
        vm.retryFailed(DownloadTarget.Task(id)).join()
        assertEquals(null, DownloadPresentation.failureReason(vm.observeDownloads().first().single()))
    }
}
