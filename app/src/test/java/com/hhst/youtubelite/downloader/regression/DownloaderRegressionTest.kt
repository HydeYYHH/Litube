package com.hhst.youtubelite.downloader.regression

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.BatchSelection
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.DeleteResult
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.core.NoOpPublisher
import com.hhst.youtubelite.downloader.core.NoOpResolver
import com.hhst.youtubelite.downloader.core.NoOpTransport
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.core.RemoveMode
import com.hhst.youtubelite.downloader.core.TransferResult
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.engine.DownloadEngine
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.net.DownloadSleeper
import com.hhst.youtubelite.downloader.net.DownloadTransportImpl
import com.hhst.youtubelite.downloader.net.ForbiddenRecovery
import com.hhst.youtubelite.downloader.net.RecoveredSource
import com.hhst.youtubelite.downloader.publish.DownloadPublisherImpl
import com.hhst.youtubelite.downloader.publish.PublishBackend
import com.hhst.youtubelite.downloader.publish.PublishTarget
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.resolve.DownloadSelection
import com.hhst.youtubelite.downloader.resolve.audioFormat
import com.hhst.youtubelite.downloader.resolve.catalog
import com.hhst.youtubelite.downloader.resolve.videoFormat
import com.hhst.youtubelite.downloader.ui.DownloadPresentation
import com.hhst.youtubelite.downloader.ui.DownloadRowAction
import com.hhst.youtubelite.downloader.ui.DownloadUiMapper
import com.hhst.youtubelite.downloader.webview.DownloadWebGuard
import com.hhst.youtubelite.downloader.work.DownloadBatchExecutor
import com.hhst.youtubelite.downloader.work.SchedulerHarness
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Regression tests for the download pipeline. Each test pins one behavioral
 * guarantee: a failing run means the guarded behavior regressed.
 */
class DownloaderRegressionTest {

    @Test
    fun systemWaitingTask_canBeExplicitlyResumed() = runBlocking {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s").created.single().taskId
        h.coordinator.reportExecution(id, 0, DownloadStatus.WAITING_SYSTEM, DownloadPhase.TRANSFER)
        h.coordinator.resume(DownloadTarget.Task(id))
        assertEquals(DownloadStatus.QUEUED, h.repo.transact { getTask(id) }!!.status)
    }

    @Test
    fun networkRecovery_hasExecutableWorkAfterUidtEnded() = runBlocking {
        val h = SchedulerHarness(34)
        val id = h.wired.enqueue(request("a"), "s").created.single().taskId
        h.wired.reportExecution(id, 0, DownloadStatus.WAITING_NETWORK, DownloadPhase.TRANSFER)
        h.uidt.active.clear() // JobService ends the job with jobFinished(params, false).
        h.wired.onNetworkRestored()
        assertTrue(
            "task is queued but has no Job or Worker",
            h.uidt.active.isNotEmpty() || h.work.enqueued.isNotEmpty(),
        )
    }

    @Test
    fun api33BatchSubmission_doesNotReplaceItsOwnWorkerPerItem() = runBlocking {
        val h = SchedulerHarness(33)
        h.wired.enqueueBatch(
            BatchSnapshot(BatchSource.QUEUE, "batch", listOf(request("a"), request("b"), request("c"))),
            BatchSelection(setOf(0, 1, 2)),
            "s",
        )
        assertEquals("one transfer job for a single batch", 1, h.work.enqueued.size)
    }

    @Test
    fun batchFinalizer_doesNotProcessAnItemStillTransferring() = runBlocking {
        val h = SchedulerHarness(33)
        val batch = h.wired.enqueueBatch(
            BatchSnapshot(BatchSource.QUEUE, "batch", listOf(request("a"), request("b"))),
            BatchSelection(setOf(0, 1)),
            "s",
        )
        val first = batch.created[0].taskId
        val second = batch.created[1].taskId
        h.wired.reportExecution(first, 0, DownloadStatus.QUEUED, DownloadPhase.WAITING_PROCESS)
        h.wired.reportExecution(second, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        val root = temp()
        try {
            val finalizer = object : com.hhst.youtubelite.downloader.core.DownloadFinalizer {
                override suspend fun muxAndVerify(
                    inputs: List<File>,
                    output: File,
                    audioOnly: Boolean,
                ): MuxResult = MuxResult.Failed("missing-input")
            }
            val engine = DownloadEngine(h.wired, h.repo, NoOpResolver, NoOpTransport, finalizer, NoOpPublisher, DownloadDirectories(root))
            DownloadBatchExecutor(engine, h.repo, h.wired, h.scheduler).executeFinalize(batch.batchId)
            assertEquals(DownloadStatus.RUNNING, h.repo.transact { getTask(second) }!!.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitQuality_doesNotSilentlyFallBack() {
        val result = com.hhst.youtubelite.downloader.resolve.DownloadSelector.select(
            catalog(listOf(videoFormat(720), audioFormat())),
            DownloadConfig(videoQuality = "1080p", videoItagHint = 137),
        )
        assertTrue("explicit 1080p should need reselection", result is DownloadSelection.Failed)
    }

    @Test
    fun resolver_preservesUserFilename() = runBlocking {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(fileNameTemplate = "Custom name")),
            "s",
        ).created.single().taskId
        val cat = catalog(listOf(videoFormat(1080), audioFormat()))
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, object : DownloadCatalogSource {
            override suspend fun catalog(videoId: String) = cat
            override suspend fun refresh(videoId: String) = cat
        })
        resolver.resolve(id)
        assertEquals("Custom name.mp4", h.repo.transact { assetsForTask(id) }.single().outputName)
    }

    @Test
    fun missingSubtitle_doesNotBlockValidVideo() = runBlocking {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true, subtitleLanguage = "zh")),
            "s",
        ).created.single().taskId
        val cat = catalog(listOf(videoFormat(1080), audioFormat()))
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, object : DownloadCatalogSource {
            override suspend fun catalog(videoId: String) = cat
            override suspend fun refresh(videoId: String) = cat
        })
        resolver.resolve(id)
        assertFalse(
            "only subtitle should fail",
            h.repo.transact { assetsForTask(id) }.first { it.kind == AssetKind.VIDEO }.failed,
        )
    }

    @Test
    fun partialCompletion_offersRetry() = runBlocking {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeCover = true)),
            "s",
        ).created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "content://test/video")
        h.coordinator.reportAssetFailed(id, 0, AssetKind.COVER, "404")
        val ui = DownloadUiMapper.item(h.repo.transact { snapshot(id) }!!)
        assertTrue(DownloadPresentation.actionsFor(ui).contains(DownloadRowAction.RETRY))
    }

    @Test
    fun deleteFailure_keepsRecordVisible() = runBlocking {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("a"), "s").created.single().taskId
        h.coordinator.reportAssetPublished(id, 0, AssetKind.VIDEO, "content://test/video")
        h.publisher.failDelete = true
        h.coordinator.remove(DownloadTarget.Task(id), RemoveMode.RECORD_AND_FILES)
        assertFalse(
            "failed deletion must remain available to retry",
            h.repo.transact { getTask(id) }!!.removed,
        )
    }

    @Test
    fun malformedBridgeItems_isRejectedWithoutThrowing() {
        assertNull(DownloadWebGuard.parse("""{"type":"openBatch","items":{}}"""))
    }

    @Test
    fun fullResponse_shorterThanTrustedLength_doesNotSucceed() = runBlocking {
        val root = temp()
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("abcd"))
            server.enqueue(MockResponse().setBody("abcd"))
            val t = DownloadTransportImpl(client, sleeper = DownloadSleeper {})
            val src = DownloadComponentSource(
                AssetKind.VIDEO,
                InputComponentKind.VIDEO,
                server.url("/f").toString(),
                expectedBytes = 8,
                rangeModeName = "HTTP_HEADER",
            )
            // A known-length stream with a non-ranged plan still must match total bytes.
            val out = t.downloadComponent(
                "task",
                InputComponent("comp", "asset", InputComponentKind.VIDEO),
                src,
                File(root, "x"),
                emptyList(),
            ) { true }
            assertNotEquals(TransferResult.Completed, out)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun estimateWithoutClen_isNotTreatedAsTrustedLength() {
        val format = videoFormat(720).copy(url = "https://rr.googlevideo.com/videoplayback?id=x&itag=136")
        val selected = com.hhst.youtubelite.downloader.resolve.DownloadSelector.select(
            catalog(listOf(format, audioFormat())),
            DownloadConfig(),
        ) as DownloadSelection.Ready
        assertNull(
            "expectedBytes feeds the byte loop, so estimates need a separate field",
            selected.plan.video!!.expectedBytes,
        )
    }

    @Test
    fun resumeWithChangedMedia_doesNotReusePreviousObjectChunks() = runBlocking {
        val root = temp()
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        try {
            val file = File(root, "bytes")
            file.writeText("AAAA")
            val checksum = FileIntegrity.sha256(file, 0, 4)
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-7/8")
                    .setBody("BBBB"),
            )
            val source = DownloadComponentSource(
                AssetKind.VIDEO,
                InputComponentKind.VIDEO,
                server.url("/old").toString(),
                "dl:x:1::8",
                8,
            )
            val transport = DownloadTransportImpl(
                client,
                chunkBytes = 4,
                sleeper = DownloadSleeper {},
                forbidden = ForbiddenRecovery { _, _ ->
                    RecoveredSource(
                        source.copy(url = server.url("/new").toString(), resourceIdentity = "dl:y:1::8"),
                        true,
                        false,
                    )
                },
            )
            val kept = listOf(DownloadChunk("comp:0", "comp", 0, 3, 4, true, file.path, checksum))
            val result = transport.downloadComponent(
                "task",
                InputComponent("comp", "asset", InputComponentKind.VIDEO),
                source,
                file,
                kept,
            ) { true }
            assertFalse(
                "successful sparse file contains zeroed old chunk",
                result == TransferResult.Completed && file.readBytes().take(4).all { it == 0.toByte() },
            )
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedPublish_isRecoveredByPublishIdWithoutOrphan() = runBlocking {
        val root = temp()
        val source = File(root, "source")
        source.writeText("abcdefgh")
        val targets = linkedMapOf<String, ByteArray>()
        val names = linkedMapOf<String, String>()
        var fail = true
        val backend = object : PublishBackend {
            override fun existingNames() = names.values.toSet()
            override fun createTarget(displayName: String, mimeType: String): PublishTarget {
                val id = "content://test/" + (targets.size + 1)
                targets[id] = byteArrayOf()
                names[id] = displayName
                return PublishTarget(id, null)
            }
            override fun openTarget(uri: String) = PublishTarget(uri, null)
            override fun write(target: PublishTarget, source: File) {
                targets[target.uri] = source.readBytes()
                if (fail) {
                    fail = false
                    throw IOException("interrupted after create/write")
                }
            }
            override fun complete(target: PublishTarget) {}
            override fun exists(uri: String) = targets.containsKey(uri)
            override fun verified(uri: String, expectedBytes: Long, checksum: String?) =
                targets[uri]?.size?.toLong() == expectedBytes
            override fun delete(uri: String): DeleteResult {
                targets.remove(uri)
                return DeleteResult.OK
            }
            override fun openableUri(uri: String) = uri
        }
        try {
            val publisher = DownloadPublisherImpl(backend)
            val req = PublishRequest("stable-publish", "asset", "a.mp4", "video/mp4", source)
            assertTrue(publisher.publish(req) is PublishResult.Failed)
            assertTrue(publisher.publish(req) is PublishResult.Published)
            assertEquals("same publish operation left orphan MediaStore target", 1, targets.size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun temp(): File = Files.createTempDirectory("litube-regression-").toFile()
}
