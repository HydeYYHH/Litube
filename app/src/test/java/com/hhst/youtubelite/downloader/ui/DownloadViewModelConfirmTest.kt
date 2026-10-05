package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.AssetKind
import kotlinx.coroutines.CancellationException
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.core.vid
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadUnavailableReason
import com.hhst.youtubelite.downloader.resolve.audioFormat
import com.hhst.youtubelite.downloader.resolve.catalog
import com.hhst.youtubelite.downloader.resolve.subtitle
import com.hhst.youtubelite.downloader.resolve.videoFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadViewModelConfirmTest {

    @Test fun localPreviewsDoNotReloadCatalogAndCancellationPropagates() = runTest {
        val h = DownloadHarness()
        var loads = 0
        val source = object : DownloadCatalogSource {
            override suspend fun catalog(videoId: String): DownloadCatalog {
                loads++
                return catalog(listOf(videoFormat(720), videoFormat(1080), audioFormat()))
            }
            override suspend fun refresh(videoId: String): DownloadCatalog = throw CancellationException()
        }
        val vm = DownloadViewModel(h.coordinator, source)
        val loaded = (vm.loadCatalog(vid("a")) as DownloadViewModel.CatalogState.Ready).catalog
        val small = vm.preview(loaded, DownloadConfig(videoQuality = "720p")) as DownloadViewModel.PreviewState.Ready
        val large = vm.preview(loaded, DownloadConfig(videoQuality = "1080p")) as DownloadViewModel.PreviewState.Ready
        assertEquals(720, small.plan.video!!.format.height)
        assertEquals(1080, large.plan.video!!.format.height)
        assertEquals(1, loads)
        try { vm.loadCatalog(vid("a"), fresh = true); throw AssertionError("cancel must propagate") }
        catch (_: CancellationException) { }
    }

    @Test
    fun preview_doesNotEnqueue() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator, FakeCatalogs())
        vm.preview(vid("a"), DownloadConfig())
        assertTrue(vm.observeDownloads().first().isEmpty())
    }

    @Test
    fun confirm_enqueuesOnce_processDeathDoesNotReplay() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator, FakeCatalogs())
        val restored = DownloadViewModel(h.coordinator, FakeCatalogs())
        restored.preview(vid("a"), DownloadConfig())
        assertTrue(h.coordinator.observeDownloads().first().isEmpty())
        vm.confirmSingle(request("a"))
        assertEquals(1, h.coordinator.observeDownloads().first().size)
        restored.preview(vid("a"), DownloadConfig())
        assertEquals(1, h.coordinator.observeDownloads().first().size)
    }

    @Test
    fun attachmentsOnly_notWatchPageDownloaded() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val result = vm.enqueue(
            request(
                "a",
                config = DownloadConfig(
                    attachmentsOnly = true,
                    includeSubtitle = true,
                    subtitleLanguage = "en",
                ),
            ),
            "s-att",
        )
        val taskId = result.created.single().taskId
        h.coordinator.reportAssetPublished(taskId, 0, AssetKind.SUBTITLE, "file://a.en.vtt")
        val video = vm.observeVideo(vid("a")).first()
        assertTrue(video.tasks.single().fullyDownloaded)
        assertFalse(video.watchPageDownloaded)
        assertFalse(video.tasks.single().watchPageDownloaded)
    }

    @Test
    fun fileMissing_mapsOnCompletedTask() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val id = vm.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "file://a.mp4")
        h.coordinator.reportFileAvailability(id, 0, AssetKind.VIDEO, FileAvailability.MISSING)
        val item = vm.observeDownloads().first().single()
        assertTrue(item.fileMissing || item.fileAvailability == FileAvailability.MISSING)
        val phase = DownloadPresentation.phaseCopy(item.copy(fileMissing = true, phase = DownloadPhase.COMPLETE))
        assertEquals(PhaseCopy.FILE_NOT_FOUND, phase)
    }

    @Test
    fun allSkip_snackbarKind() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        vm.enqueue(request("a"), "s1")
        val second = vm.confirmSingle(request("a"), persistPrefs = false)
        assertEquals(0, second.newCount)
        assertEquals(SnackbarKind.NO_NEW_TASKS, DownloadPresentation.snackbarFor(second).kind)
    }

    @Test
    fun snapshotOverLimit_rejectedBeforeEnqueue() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val snapshot = BatchSnapshot(
            BatchSource.PLAYLIST,
            "p",
            (0 until 501).map { request("i$it") },
        )
        assertTrue(vm.validateSnapshot(snapshot) != null)
        try {
            vm.enqueueBatch(snapshot, BatchSelection(setOf(0)), "s")
            throw AssertionError("expected reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("500"))
        }
        assertTrue(h.coordinator.observeDownloads().first().isEmpty())
    }

    @Test
    fun liveReason_fromPreview() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(
            h.coordinator,
            FakeCatalogs(catalog(emptyList(), isLive = true, durationSec = 0L)),
        )
        val preview = vm.preview(vid("a"), DownloadConfig()) as DownloadViewModel.PreviewState.Unavailable
        assertEquals(DownloadUnavailableReason.LIVE, preview.reason)
    }

    @Test
    fun clearHistory_defaultTerminalRecords() = runTest {
        val h = DownloadHarness()
        val vm = DownloadViewModel(h.coordinator)
        val live = vm.enqueue(request("a"), "s1").created.single().taskId
        val done = vm.enqueue(request("b"), "s2").created.single().taskId
        h.coordinator.reportAssetPublished(done, 0, AssetKind.VIDEO, "file://b.mp4")
        vm.remove(DownloadTarget.Task(done), RemoveMode.RECORD_ONLY).join()
        // live task remains
        assertEquals(1, vm.observeDownloads().first().size)
        assertEquals(live, vm.observeDownloads().first().single().taskId)
    }

    private class FakeCatalogs(
        private val catalog: DownloadCatalog = catalog(
            listOf(videoFormat(720), audioFormat()),
            subtitles = listOf(subtitle("en")),
        ),
    ) : DownloadCatalogSource {
        override suspend fun catalog(videoId: String) = catalog.copy(videoId = videoId)
        override suspend fun refresh(videoId: String) = catalog(videoId)
    }
}
