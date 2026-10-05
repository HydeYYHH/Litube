package com.hhst.youtubelite.downloader.ui

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadFilter
import com.hhst.youtubelite.downloader.core.DownloadLimits
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadSnapshotGuard
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.SnapshotReject
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.resolve.DownloadMediaChoice
import com.hhst.youtubelite.downloader.resolve.DownloadPlan
import com.hhst.youtubelite.downloader.resolve.audioFormat
import com.hhst.youtubelite.downloader.resolve.catalog
import com.hhst.youtubelite.downloader.resolve.playbackUrl
import com.hhst.youtubelite.downloader.resolve.videoFormat
import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPresentationTest {

    @Test
    fun filters_mapToCoordinatorPhases() {
        val all = DownloadPresentation.managerFilter(ManagerFilter.ALL)
        assertEquals(DownloadFilter(), all)
        val running = DownloadPresentation.managerFilter(ManagerFilter.IN_PROGRESS)
        assertTrue(DownloadPhase.TRANSFER in running.phases!!)
        assertFalse(DownloadPhase.COMPLETE in running.phases!!)
        val done = DownloadPresentation.managerFilter(ManagerFilter.COMPLETED)
        assertEquals(setOf(DownloadPhase.COMPLETE), done.phases)
    }

    @Test
    fun phases_showPipelineNotOnlyStatus() {
        val transferring = item(phase = DownloadPhase.TRANSFER, status = DownloadStatus.RUNNING)
        assertEquals(PhaseCopy.TRANSFERRING, DownloadPresentation.phaseCopy(transferring))
        assertEquals(
            PhaseCopy.WAITING_PROCESS,
            DownloadPresentation.phaseCopy(item(phase = DownloadPhase.WAITING_PROCESS, status = DownloadStatus.RUNNING)),
        )
        assertEquals(
            PhaseCopy.MERGING,
            DownloadPresentation.phaseCopy(item(phase = DownloadPhase.MERGE_VERIFY, status = DownloadStatus.RUNNING)),
        )
        assertEquals(
            PhaseCopy.SAVING,
            DownloadPresentation.phaseCopy(item(phase = DownloadPhase.SAVE, status = DownloadStatus.RUNNING)),
        )
        assertEquals(
            PhaseCopy.COMPLETE,
            DownloadPresentation.phaseCopy(item(phase = DownloadPhase.COMPLETE, status = DownloadStatus.QUEUED)),
        )
    }

    @Test
    fun complete_onlyWhenPublished_fileMissingIsDistinct() {
        val missing = item(
            phase = DownloadPhase.COMPLETE,
            status = DownloadStatus.QUEUED,
            fileAvailability = FileAvailability.MISSING,
            completion = CompletionKind.FULL,
            fileMissing = true,
        )
        assertEquals(PhaseCopy.FILE_NOT_FOUND, DownloadPresentation.phaseCopy(missing))
        assertTrue(
            DownloadPresentation.actionsFor(missing).contains(DownloadRowAction.REDOWNLOAD),
        )
    }

    @Test
    fun waitingNetwork_copyAndResume() {
        val waiting = item(
            phase = DownloadPhase.TRANSFER,
            status = DownloadStatus.WAITING_NETWORK,
        )
        assertEquals(PhaseCopy.WAITING_NETWORK, DownloadPresentation.phaseCopy(waiting))
        assertTrue(DownloadPresentation.actionsFor(waiting).contains(DownloadRowAction.RESUME))
    }

    @Test
    fun size_exactEstimateUnknown() {
        val exactFormat = videoFormat(720, clen = 1_000_000)
        val exact = DownloadPresentation.sizeCopy(
            DownloadPlan(video = DownloadMediaChoice(exactFormat, 1, 1_000_000, "id")),
        )
        assertEquals(SizeKind.EXACT, exact.kind)
        assertEquals(1_000_000L, exact.bytes)

        val estimatedFormat = Format(
            url = playbackUrl(137, clen = 0),
            height = 1080,
            bitrate = 5_000_000,
            videoOnly = true,
            approxDurationMs = 60_000,
        )
        val estimate = DownloadPresentation.sizeCopy(
            DownloadPlan(
                video = DownloadMediaChoice(
                    estimatedFormat,
                    5_000_000,
                    expectedBytes = 3_000_000,
                    resourceIdentity = "e",
                ),
            ),
        )
        assertEquals(SizeKind.ESTIMATE, estimate.kind)

        val unknown = DownloadPresentation.sizeCopy(DownloadPlan())
        assertEquals(SizeKind.UNKNOWN, unknown.kind)
    }

    @Test
    fun watchPageBadge_notForPartialOrAttachmentsOnly() {
        assertFalse(
            DownloadPresentation.watchPageDownloaded(
                listOf(AssetKind.SUBTITLE to true, AssetKind.COVER to true),
            ),
        )
        assertFalse(
            DownloadPresentation.watchPageDownloaded(
                listOf(AssetKind.VIDEO to true, AssetKind.SUBTITLE to false),
            ),
        )
        assertTrue(
            DownloadPresentation.watchPageDownloaded(
                listOf(AssetKind.VIDEO to true, AssetKind.SUBTITLE to true),
            ),
        )
    }

    @Test
    fun allSkip_snackbar() {
        val snack = DownloadPresentation.snackbarFor(
            EnqueueResult(0, 2, emptyList(), emptyList(), "b", "s"),
        )
        assertEquals(SnackbarKind.NO_NEW_TASKS, snack.kind)
    }

    @Test
    fun snapshot_over500_rejectedWithoutTruncate() {
        val items = (0 until 501).map { request("v$it") }
        val reject = DownloadSnapshotGuard.validate(
            BatchSnapshot(BatchSource.PLAYLIST, "p", items),
        )
        assertTrue(reject is SnapshotReject.TooManyItems)
    }

    @Test
    fun snapshot_over1MiB_rejectedWithoutTruncate() {
        val huge = request("a", title = "x".repeat(DownloadLimits.MAX_SNAPSHOT_BYTES + 64))
        val reject = DownloadSnapshotGuard.validate(
            BatchSnapshot(BatchSource.SHARE, "web", listOf(huge)),
        )
        assertTrue(reject is SnapshotReject.TooLarge)
    }


    @Test
    fun qualityOptions_fromCatalog() {
        val options = DownloadPresentation.qualityOptions(
            catalog(listOf(videoFormat(720), videoFormat(1080), audioFormat())),
        )
        assertEquals(listOf("1080p", "720p"), options)
    }

    @Test fun qualityOptions_includeFpsButExcludeGatedCodecsAndUnpairedVideo() {
        val formats = listOf(videoFormat(720), videoFormat(1080).copy(fps = 60),
            videoFormat(2160).copy(codec = "vp09.00.51.08", container = "WEBM"), audioFormat())
        assertEquals(listOf("1080p60", "720p"), DownloadPresentation.qualityOptions(catalog(formats)))
        assertTrue(DownloadPresentation.qualityOptions(catalog(formats.filterNot { it.audioOnly })).isEmpty())
    }

    @Test
    fun progress_zeroIsDeterminate_unknownTotalIsNot() {
        assertEquals(0f, DownloadPresentation.progressFraction(0, 100)!!, 0f)
        assertEquals(0.25f, DownloadPresentation.progressFraction(25, 100)!!, 0f)
        assertEquals(1f, DownloadPresentation.progressFraction(150, 100)!!, 0f)
        assertNull(DownloadPresentation.progressFraction(25, null))
        assertNull(DownloadPresentation.progressFraction(25, 0))
        assertEquals("25% · 25 B / 100 B", DownloadPresentation.progressText(DownloadStatus.RUNNING, 25, 100))
        assertEquals("25% · 25 B / 100 B", DownloadPresentation.progressText(DownloadStatus.PAUSED, 25, 100))
        assertEquals("25 B", DownloadPresentation.progressText(DownloadStatus.RUNNING, 25, null))
    }

    @Test
    fun failureReason_isBrief_andOnlyShownForCurrentFailures() {
        val failed = item(DownloadPhase.TRANSFER, DownloadStatus.FAILED)
            .copy(errorMessage = "  Connection timed out  \nstack trace")
        assertEquals("Connection timed out", DownloadPresentation.failureReason(failed))
        assertEquals(120, DownloadPresentation.failureReason(failed.copy(errorMessage = "x".repeat(200)))!!.length)
        assertNull(DownloadPresentation.failureReason(failed.copy(status = DownloadStatus.QUEUED)))
        assertEquals("Connection timed out", DownloadPresentation.failureReason(failed.copy(
            phase = DownloadPhase.COMPLETE, status = DownloadStatus.QUEUED, failedKinds = listOf(AssetKind.SUBTITLE))))
    }

    private fun item(
        phase: DownloadPhase,
        status: DownloadStatus,
        fileAvailability: FileAvailability = FileAvailability.EXISTS,
        completion: CompletionKind = CompletionKind.NONE,
        fileMissing: Boolean = false,
    ) = DownloadItemUiState(
        taskId = "t",
        videoId = "abcdefghijk",
        title = "T",
        author = "A",
        thumbnailUrl = null,
        phase = phase,
        status = status,
        fileAvailability = fileAvailability,
        completion = completion,
        fullyDownloaded = false,
        fileMissing = fileMissing,
    )
}
