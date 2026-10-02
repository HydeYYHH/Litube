package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadFinalizer
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.core.DownloadResolveOutcome
import com.hhst.youtubelite.downloader.core.DownloadResolver
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.DownloadTransport
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.SeqIdFactory
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.core.vid
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.DownloadPublishedUris
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.io.FreeSpace
import com.hhst.youtubelite.downloader.notify.DownloadNotificationController
import com.hhst.youtubelite.downloader.notify.RecordingNotificationPort
import com.hhst.youtubelite.downloader.publish.DownloadPublisherImpl
import com.hhst.youtubelite.downloader.publish.LocalPublishBackend
import com.hhst.youtubelite.downloader.share.DownloadShareParser
import com.hhst.youtubelite.downloader.share.DownloadShareTarget
import com.hhst.youtubelite.downloader.ui.DownloadEntries
import com.hhst.youtubelite.downloader.ui.DownloadPresentation
import com.hhst.youtubelite.downloader.ui.DownloadRowAction
import com.hhst.youtubelite.downloader.ui.DownloadUiMapper
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.downloader.webview.DownloadWebStatus
import com.hhst.youtubelite.downloader.work.DownloadStartupReconciler
import com.hhst.youtubelite.downloader.work.SchedulerHarness
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.queue.QueueItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DownloadFaultInjectionTest {

    @Test
    fun isNoSpace_matchesEnospcAndWindowsCopy() {
        assertTrue(FileIntegrity.isNoSpace(IOException("ENOSPC")))
        assertTrue(FileIntegrity.isNoSpace(IOException("No space left on device")))
        assertTrue(FileIntegrity.isNoSpace(IOException("There is not enough space on the disk")))
        assertFalse(FileIntegrity.isNoSpace(IOException("connection reset")))
    }

    @Test
    fun engineTransfer_enospcFailsAsset() = runBlocking<Unit> {
        val env = engineEnv(transport = FailedTransport("ENOSPC"))
        val id = env.coordinator.enqueue(request("a"), "s1").created.single().taskId
        env.engine.run(id)
        val snap = env.repo.transact { snapshot(id) }!!
        assertTrue(snap.assets.any { it.asset.failed && it.asset.errorMessage == "ENOSPC" })
        assertEquals(DownloadStatus.FAILED, snap.task.status)
    }

    @Test
    fun engineMux_enospcFailsAsset() = runBlocking<Unit> {
        val env = engineEnv(
            transport = CompletingTransport(),
            finalizer = ScriptedFinalizer(MuxResult.Failed("ENOSPC")),
        )
        val id = env.coordinator.enqueue(request("a"), "s1").created.single().taskId
        env.engine.run(id)
        val snap = env.repo.transact { snapshot(id) }!!
        assertEquals("ENOSPC", snap.assets.single().asset.errorMessage)
        assertTrue(snap.assets.single().asset.failed)
        assertFalse(snap.assets.single().asset.published)
    }

    @Test
    fun enginePublish_enospcFailsAsset() = runBlocking<Unit> {
        val out = File(System.getProperty("java.io.tmpdir"), "dl-enospc-pub-${System.nanoTime()}").also { it.mkdirs() }
        val publisher = DownloadPublisherImpl(LocalPublishBackend(out), freeSpace = FreeSpace { 1L })
        val env = engineEnv(
            transport = CompletingTransport(),
            finalizer = CopyingFinalizer(),
            publisher = publisher,
        )
        val id = env.coordinator.enqueue(request("a"), "s1").created.single().taskId
        env.engine.run(id)
        val snap = env.repo.transact { snapshot(id) }!!
        assertEquals("ENOSPC", snap.assets.single().asset.errorMessage)
        assertFalse(snap.assets.single().asset.published)
        out.deleteRecursively()
    }

    @Test
    fun externallyDeletedFile_isMissingAndOffersRedownload() = runTest {
        val h = SchedulerHarness(33)
        val dir = File(System.getProperty("java.io.tmpdir"), "dl-ext-${System.nanoTime()}").also { it.mkdirs() }
        val backend = LocalPublishBackend(dir)
        val publisher = DownloadPublisherImpl(backend)
        val rec = DownloadStartupReconciler(
            repository = h.repo,
            coordinator = h.wired,
            scheduler = h.scheduler,
            publisher = publisher,
            directories = DownloadDirectories(File(dir, "work")),
        )
        val id = h.wired.enqueue(request("a"), "s1").created.single().taskId
        val source = File(dir, "src.bin").also { it.writeBytes(ByteArray(8) { 2 }) }
        val published = publisher.publish(
            PublishRequest("p1", "a1", "clip.mp4", "video/mp4", source),
        ) as com.hhst.youtubelite.downloader.core.PublishResult.Published
        h.wired.reportAssetPublished(id, 0, AssetKind.VIDEO, published.uri)
        val file = backend.fileFor(published.uri)!!
        assertTrue(file.isFile)
        assertTrue(file.delete())
        rec.reconcile()
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(DownloadPhase.COMPLETE, snap.task.phase)
        assertEquals(CompletionKind.FULL, snap.task.completion)
        assertEquals(FileAvailability.MISSING, snap.task.fileAvailability)
        val ui = DownloadUiMapper.item(snap)
        assertTrue(ui.fileMissing)
        assertFalse(ui.watchPageDownloaded)
        assertTrue(DownloadPresentation.actionsFor(ui).contains(DownloadRowAction.REDOWNLOAD))
        assertFalse(DownloadPresentation.actionsFor(ui).contains(DownloadRowAction.OPEN))
        val redo = h.wired.redownload(DownloadTarget.Task(id))
        assertEquals(1, redo.newCount)
        dir.deleteRecursively()
    }

    @Test
    fun invalidPublishedUri_isInaccessibleNotOpenable() = runTest {
        val h = SchedulerHarness(33)
        val rec = DownloadStartupReconciler(
            repository = h.repo,
            coordinator = h.wired,
            scheduler = h.scheduler,
            publisher = com.hhst.youtubelite.downloader.core.NoOpPublisher,
            directories = DownloadDirectories(
                File(System.getProperty("java.io.tmpdir"), "dl-inv-${System.nanoTime()}"),
            ),
        )
        val id = h.wired.enqueue(request("a"), "s1").created.single().taskId
        h.wired.reportAssetPublished(id, 0, AssetKind.VIDEO, "not-a-uri")
        rec.reconcile()
        val snap = h.repo.transact { snapshot(id) }!!
        assertEquals(FileAvailability.INACCESSIBLE, snap.task.fileAvailability)
        assertFalse(DownloadPublishedUris.isOpenable("not-a-uri"))
        assertFalse(DownloadPublishedUris.isOpenable("http://example.com/clip.mp4"))
        assertFalse(DownloadPublishedUris.isOpenable("content://"))
        val ui = DownloadUiMapper.item(snap)
        assertTrue(ui.fileMissing)
        assertFalse(ui.watchPageDownloaded)
    }

    @Test
    fun listNotificationAndWeb_shareTheSameRoomState() = runTest {
        val h = SchedulerHarness(33)
        val vm = DownloadViewModel(h.wired)
        val result = vm.enqueue(request("a"), "s1")
        val id = result.created.single().taskId
        val uri = "content://com.hhst.youtubelite.download.fileprovider/downloads/a.mp4"
        h.wired.reportAssetPublished(id, 0, AssetKind.VIDEO, uri)
        val listed = vm.observeDownloads().first().single()
        val video = vm.observeVideo(vid("a")).first()
        val web = DownloadWebStatus.payload(video)
        val batch = h.wired.observeBatch(result.batchId).first()!!
        val notif = DownloadNotificationController(RecordingNotificationPort()).payload(batch)
        assertTrue(listed.watchPageDownloaded)
        assertEquals(listed.watchPageDownloaded, video.watchPageDownloaded)
        assertEquals(true, web["watchPageDownloaded"])
        assertEquals(DownloadWebStatus.COMPLETE, web["state"])
        assertTrue(notif.complete)

        h.wired.reportFileAvailability(id, 0, AssetKind.VIDEO, FileAvailability.MISSING)
        val listedMissing = vm.observeDownloads().first().single()
        val videoMissing = vm.observeVideo(vid("a")).first()
        val webMissing = DownloadWebStatus.payload(videoMissing)
        val batchMissing = h.wired.observeBatch(result.batchId).first()!!
        val notifMissing = DownloadNotificationController(RecordingNotificationPort()).payload(batchMissing)
        assertTrue(listedMissing.fileMissing)
        assertFalse(listedMissing.watchPageDownloaded)
        assertFalse(videoMissing.watchPageDownloaded)
        assertEquals(false, webMissing["watchPageDownloaded"])
        assertEquals(DownloadWebStatus.FAILED, webMissing["state"])
        assertFalse(notifMissing.complete)
    }

    @Test
    fun shareQueueAndWeb_useTheSameCoordinatorFingerprint() = runTest {
        val h = com.hhst.youtubelite.downloader.core.DownloadHarness()
        val parsed = DownloadShareParser.parse(
            android.content.Intent.ACTION_SEND,
            "Watch https://youtu.be/dQw4w9wgGcQ",
            null,
        )
        val videoId = (parsed as DownloadShareTarget.Video).videoId
        val fromShare = h.coordinator.enqueue(
            DownloadRequest(videoId = videoId, title = "Share"),
            "share-1",
        )
        val queue = DownloadEntries.queueSnapshot(
            listOf(
                QueueItem(
                    videoId = videoId,
                    url = VideoId.watchUrl(videoId),
                    title = "Queue",
                    author = "A",
                    thumbnailUrl = VideoId.thumbnailUrl(videoId),
                ),
            ),
        )
        val fromQueue = h.coordinator.enqueueBatch(queue, BatchSelection(setOf(0)), "queue-1")
        val fromWeb = h.coordinator.enqueueBatch(
            BatchSnapshot(BatchSource.PLAYLIST, "web", listOf(DownloadRequest(videoId = videoId, title = "Web"))),
            BatchSelection(setOf(0)),
            "web-1",
        )
        assertEquals(1, fromShare.newCount)
        assertEquals(0, fromQueue.newCount)
        assertEquals(1, fromQueue.skippedCount)
        assertEquals(0, fromWeb.newCount)
        assertEquals(fromShare.created.single().taskId, fromQueue.existing.single().taskId)
    }

    @Test
    fun processRestore_pausedStaysPaused_runningWaitsForSystem() = runTest {
        val h = com.hhst.youtubelite.downloader.core.DownloadHarness()
        val running = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val paused = h.coordinator.enqueue(request("b"), "s2").created.single().taskId
        h.coordinator.reportExecution(running, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.reportExecution(paused, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        h.coordinator.pause(DownloadTarget.Task(paused))
        h.coordinator.onProcessRestore()
        assertEquals(DownloadStatus.WAITING_SYSTEM, h.repo.transact { getTask(running) }!!.status)
        assertEquals(DownloadStatus.PAUSED, h.repo.transact { getTask(paused) }!!.status)
        assertTrue(h.repo.transact { getTask(paused) }!!.userPaused)
        assertFalse(
            h.coordinator.reportExecution(paused, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER),
        )
    }

    private fun engineEnv(
        transport: DownloadTransport,
        finalizer: DownloadFinalizer = CopyingFinalizer(),
        publisher: DownloadPublisher = DownloadPublisherImpl(
            LocalPublishBackend(
                File(System.getProperty("java.io.tmpdir"), "dl-fault-out-${System.nanoTime()}"),
            ),
        ),
    ): EngineEnv {
        val repo = InMemoryDownloadRepository()
        val dirs = DownloadDirectories(
            File(System.getProperty("java.io.tmpdir"), "dl-fault-work-${System.nanoTime()}"),
        )
        val coordinator = DownloadCoordinator(
            repository = repo,
            transport = transport,
            publisher = publisher,
            ids = SeqIdFactory(),
        )
        val engine = DownloadEngine(
            coordinator = coordinator,
            repository = repo,
            resolver = ReadyResolver(),
            transport = transport,
            finalizer = finalizer,
            publisher = publisher,
            directories = dirs,
        )
        return EngineEnv(coordinator, repo, engine)
    }

    private class EngineEnv(
        val coordinator: DownloadCoordinator,
        val repo: InMemoryDownloadRepository,
        val engine: DownloadEngine,
    )

    private class ReadyResolver : DownloadResolver {
        override suspend fun resolve(taskId: String) = DownloadResolveOutcome.Ready(taskId, 0)
        override fun sourcesOf(taskId: String) = listOf(
            DownloadComponentSource(
                assetKind = AssetKind.VIDEO,
                componentKind = InputComponentKind.VIDEO,
                url = "http://example.test/v",
                expectedBytes = 16,
                rangeModeName = "NONE",
            ),
            DownloadComponentSource(
                assetKind = AssetKind.VIDEO,
                componentKind = InputComponentKind.AUDIO,
                url = "http://example.test/a",
                expectedBytes = 16,
                rangeModeName = "NONE",
            ),
        )
    }

    private class FailedTransport(private val reason: String) : DownloadTransport {
        override fun cancelInFlight(taskId: String) = Unit
        override suspend fun downloadComponent(
            taskId: String,
            component: InputComponent,
            source: DownloadComponentSource,
            dest: File,
            verified: List<DownloadChunk>,
            onChunk: suspend (DownloadChunk) -> Boolean,
        ) = com.hhst.youtubelite.downloader.core.TransferResult.Failed(reason)
    }

    private class CompletingTransport : DownloadTransport {
        override fun cancelInFlight(taskId: String) = Unit
        override suspend fun downloadComponent(
            taskId: String,
            component: InputComponent,
            source: DownloadComponentSource,
            dest: File,
            verified: List<DownloadChunk>,
            onChunk: suspend (DownloadChunk) -> Boolean,
        ): com.hhst.youtubelite.downloader.core.TransferResult {
            dest.parentFile?.mkdirs()
            dest.writeBytes(ByteArray(16) { 4 })
            onChunk(
                DownloadChunk(
                    id = "${component.id}:0",
                    componentId = component.id,
                    startByte = 0,
                    endByte = 15,
                    receivedBytes = 16,
                    verified = true,
                    tempPath = dest.path,
                ),
            )
            return com.hhst.youtubelite.downloader.core.TransferResult.Completed
        }
    }

    private class ScriptedFinalizer(private val result: MuxResult) : DownloadFinalizer {
        override suspend fun muxAndVerify(inputs: List<File>, output: File, audioOnly: Boolean) = result
    }

    private class CopyingFinalizer : DownloadFinalizer {
        override suspend fun muxAndVerify(inputs: List<File>, output: File, audioOnly: Boolean): MuxResult {
            output.parentFile?.mkdirs()
            inputs.first().copyTo(output, overwrite = true)
            return MuxResult.Ok(1_000L)
        }
    }
}
