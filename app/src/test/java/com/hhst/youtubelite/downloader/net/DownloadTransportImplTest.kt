package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.core.AssetKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import com.hhst.youtubelite.downloader.core.TransferResult
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.extractor.ExtractionDiagnostics
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.io.AssumeAvailableNetwork
import com.hhst.youtubelite.downloader.io.NetworkKind
import com.hhst.youtubelite.downloader.io.NetworkMonitor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class DownloadTransportImplTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(System.getProperty("java.io.tmpdir"), "dl-transport-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun rangeHeader_writesVerifiedChunk() = runBlocking {
        val body = ByteArray(32) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-31/32")
                .addHeader("Content-Length", "32")
                .setBody(okio.Buffer().write(body)),
        )
        val chunks = mutableListOf<DownloadChunk>()
        val result = transport(chunkBytes = 32).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(32),
            source = source(32),
            dest = File(dir, "c.part"),
            verified = emptyList(),
            onChunk = { chunks += it; true },
        )
        assertEquals(TransferResult.Completed, result)
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().verified)
        assertEquals(32L, chunks.single().receivedBytes)
        assertEquals("bytes=0-31", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun twoHundredUnproven_fallsBackToFullStream() = runBlocking {
        val body = ByteArray(24) { 7 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Length", "24")
                .setBody(okio.Buffer().write(body)),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Length", "24")
                .setBody(okio.Buffer().write(body)),
        )
        val dest = File(dir, "full.part")
        val result = transport(chunkBytes = 8).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(24),
            source = source(24, DownloadRangeMode.HTTP_HEADER),
            dest = dest,
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.Completed, result)
        assertEquals(24, dest.length().toInt())
        assertTrue(server.requestCount >= 2)
    }

    @Test
    fun queryRangeTwoHundred_acceptedAsChunk() = runBlocking {
        val body = ByteArray(8) { 1 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Length", "8")
                .setBody(okio.Buffer().write(body)),
        )
        val result = transport(chunkBytes = 8).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(8),
            source = source(8, DownloadRangeMode.QUERY_PARAM),
            dest = File(dir, "q.part"),
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.Completed, result)
        val recorded = server.takeRequest()
        assertTrue(recorded.requestUrl!!.queryParameter("range") == "0-7")
    }

    @Test
    fun earlyEof_fails() = runBlocking {
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Range", "bytes 0-31/32")
                    .addHeader("Content-Length", "32")
                    .setBody(okio.Buffer().write(ByteArray(8))),
            )
        }
        val result = transport(chunkBytes = 32).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(32),
            source = source(32),
            dest = File(dir, "eof.part"),
            verified = emptyList(),
            onChunk = { true },
        )
        assertTrue(result is TransferResult.Failed)
        assertEquals("early-eof", (result as TransferResult.Failed).reason)
    }

    @Test
    fun unknownLength_readsUntilEof() = runBlocking {
        val body = ByteArray(11) { 3 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(body)),
        )
        val dest = File(dir, "unk.part")
        val chunks = mutableListOf<DownloadChunk>()
        val result = transport().downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(null),
            source = source(null),
            dest = dest,
            verified = emptyList(),
            onChunk = { chunks += it; true },
        )
        assertEquals(TransferResult.Completed, result)
        assertEquals(11, dest.length().toInt())
        assertEquals(11L, chunks.single().receivedBytes)
    }

    @Test
    fun retryAfter429_thenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-3/4")
                .setBody(okio.Buffer().write(ByteArray(4))),
        )
        val delays = mutableListOf<Long>()
        val result = transport(chunkBytes = 4, sleeper = { delays += it }).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(4),
            source = source(4),
            dest = File(dir, "r.part"),
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.Completed, result)
        assertTrue(delays.isNotEmpty())
    }

    @Test
    fun permanentClientErrorsFailWithoutRepeatingTheSameRequest() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val status = request.requestUrl!!.pathSegments.last().toInt()
                return MockResponse().setResponseCode(status).apply {
                    // An unsatisfied range with a different total cannot prove a completed file.
                    if (status == 416) setHeader("Content-Range", "bytes */8")
                }
            }
        }
        val delays = mutableListOf<Long>()
        val transfer = transport(chunkBytes = 4, sleeper = { delays += it })
        val statuses = listOf(400, 401, 404, 405, 410, 416, 422, 451)
        statuses.forEach { status ->
            val result = transfer.downloadComponent(
                "status-$status", 0, component(4), source(4).copy(url = server.url("/$status").toString()),
                File(dir, "$status.part"), emptyList(), onChunk = { true },
            )
            assertEquals(TransferResult.Failed("http-$status"), result)
        }
        assertEquals(statuses.size, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun temporaryClientAndServerErrorsStillRetry() = runBlocking {
        val delays = mutableListOf<Long>()
        listOf(408, 425, 500, 503).forEach { status ->
            // Keep OkHttp's own 408/503 follow-up out of this application-retry assertion.
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/4").setBody("data"))
            val result = transport(chunkBytes = 4, sleeper = { delays += it }).downloadComponent(
                "status-$status", 0, component(4), source(4), File(dir, "$status.part"), emptyList(),
                onChunk = { true },
            )
            assertEquals(TransferResult.Completed, result)
        }
        assertEquals(8, server.requestCount)
        assertEquals(4, delays.size)
    }

    @Test
    fun permanentFailureKeepsAlreadyVerifiedChunks() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-3/8").setBody("data"))
        repeat(4) { server.enqueue(MockResponse().setResponseCode(404)) }
        val delays = mutableListOf<Long>()
        val chunks = mutableListOf<DownloadChunk>()
        val dest = File(dir, "partial.part")
        val result = transport(chunkBytes = 4, sleeper = { delays += it }).downloadComponent(
            "partial", 0, component(8), source(8), dest, emptyList(),
            onChunk = { chunks += it; true },
        )
        assertEquals(TransferResult.Failed("http-404"), result)
        assertEquals(2, server.requestCount)
        assertTrue(delays.isEmpty())
        assertTrue(FileIntegrity.verify(dest, 0, 4, chunks.single().checksum))
    }

    @Test
    fun forbidden_refreshesUrlAndRedownloads() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-3/4")
                .setBody(okio.Buffer().write(ByteArray(4) { 9 })),
        )
        var recovered = false
        val transport = transport(
            chunkBytes = 4,
            forbidden = ForbiddenRecovery { _, _, _ ->
                recovered = true
                RecoveredSource(source(4), needsRedownload = true, exhausted = false)
            },
        )
        val dest = File(dir, "f.part")
        val result = transport.downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(4),
            source = source(4),
            dest = dest,
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.Completed, result)
        assertTrue(recovered)
        assertEquals(4, dest.length().toInt())
    }

    @Test
    fun identityChange_needsRedownloadClearsFile() = runBlocking {
        val first = File(dir, "id.part").also { it.writeBytes(ByteArray(4) { 1 }) }
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-3/4")
                .setBody(okio.Buffer().write(ByteArray(4) { 2 })),
        )
        val result = transport(
            chunkBytes = 4,
            forbidden = ForbiddenRecovery { _, _, _ ->
                RecoveredSource(source(4), needsRedownload = true, exhausted = false)
            },
        ).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(4).copy(needsRedownload = true),
            source = source(4),
            dest = first,
            verified = listOf(DownloadChunk("old", "c1", 0, 3, 4, true, first.path, "dead")),
            onChunk = { true },
        )
        assertEquals(TransferResult.Completed, result)
        assertEquals(2.toByte(), first.readBytes()[0])
    }

    @Test
    fun networkLoss_doesNotConsumeRetries() = runBlocking {
        val monitor = NetworkMonitor { NetworkKind.NONE }
        val result = transport(network = monitor).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(4),
            source = source(4),
            dest = File(dir, "n.part"),
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.WaitingNetwork, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun enospc_duringFetch_isFailedEnospc() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val client = DownloadHttpClients.create().newBuilder()
            .addInterceptor { calls++; throw java.io.IOException("No space left on device") }
            .build()
        val result = DownloadTransportImpl(
            client = client,
            chunkBytes = 4,
            sleeper = { delays += it },
            random = kotlin.random.Random(0),
        ).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(4),
            source = source(4),
            dest = File(dir, "enospc.part"),
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.Failed("ENOSPC"), result)
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun pauseCancelsInFlightCall_keepsVerifiedChunks() = runBlocking {
        val body = ByteArray(8) { 4 }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-7/16")
                .setBody(okio.Buffer().write(body)),
        )
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )
        val chunks = mutableListOf<DownloadChunk>()
        val firstChunkVerified = CompletableDeferred<Unit>()
        val t = transport(chunkBytes = 8)
        val dest = File(dir, "p.part")
        val job = async(Dispatchers.IO) {
            t.downloadComponent(
                taskId = "t1",
                generation = 0,
                component = component(16),
                source = source(16),
                dest = dest,
                verified = emptyList(),
                onChunk = {
                    chunks += it
                    firstChunkVerified.complete(Unit)
                    true
                },
            )
        }
        try {
            withTimeout(10_000) { firstChunkVerified.await() }
            withContext(Dispatchers.IO) {
                assertEquals("bytes=0-7", server.takeRequest(10, TimeUnit.SECONDS)?.getHeader("Range"))
                assertEquals("bytes=8-15", server.takeRequest(10, TimeUnit.SECONDS)?.getHeader("Range"))
            }
            t.cancelInFlight("t1")
            assertEquals(TransferResult.Paused, withTimeout(10_000) { job.await() })
            assertEquals(1, chunks.size)
            assertTrue(chunks.single().verified)
            assertEquals(8L, chunks.single().receivedBytes)
            assertTrue(FileIntegrity.verify(dest, 0, 8, chunks.single().checksum))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun resumeSkipsVerifiedCheckpoint() = runBlocking {
        val dest = File(dir, "resume.part")
        dest.writeBytes(ByteArray(16) { it.toByte() })
        val first = DownloadChunk(
            id = "c1:0",
            componentId = "c1",
            startByte = 0,
            endByte = 7,
            receivedBytes = 8,
            verified = true,
            tempPath = dest.path,
            checksum = FileIntegrity.sha256(dest, 0, 8),
        )
        FileIntegrity.writeChecksum(dest, 0, first.checksum!!)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 8-15/16")
                .setBody(okio.Buffer().write(ByteArray(8) { (it + 8).toByte() })),
        )
        val chunks = mutableListOf<DownloadChunk>()
        val result = transport(chunkBytes = 8).downloadComponent(
            taskId = "t1",
            generation = 0,
            component = component(16),
            source = source(16),
            dest = dest,
            verified = listOf(first),
            onChunk = { chunks += it; true },
        )
        assertEquals(TransferResult.Completed, result)
        assertEquals(1, chunks.size)
        assertEquals(8L, chunks.single().startByte)
        assertEquals("bytes=8-15", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun recoveryStopsAfterOneRefreshAndOneExistingBackup() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(403)) }
        var refreshed = 0
        var backups = 0
        val recoveryGenerations = mutableListOf<Long>()
        val transfer = DownloadTransportImpl(client = DownloadHttpClients.create(), chunkBytes = 4,
            forbidden = ForbiddenRecovery { _, generation, _ ->
                recoveryGenerations += generation
                refreshed++
                RecoveredSource(source(4), false, false)
            },
            fallback = ForbiddenRecovery { _, generation, _ ->
                recoveryGenerations += generation
                backups++
                RecoveredSource(source(4), false, false)
            })
        val result = transfer.downloadComponent("t1", 73, component(4), source(4), File(dir, "bounded.part"), emptyList()) { true }
        assertEquals(TransferResult.Failed("403"), result)
        assertEquals(1, refreshed)
        assertEquals(1, backups)
        assertEquals(listOf(73L, 73L), recoveryGenerations)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun parentCancellationClosesAnAlreadyOpenResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-3/4")
            .setBody("data").setBodyDelay(2, TimeUnit.SECONDS))
        val transfer = transport(chunkBytes = 4)
        val job = async(Dispatchers.IO) {
            transfer.downloadComponent("t1", 0, component(4), source(4), File(dir, "cancel.part"), emptyList()) { true }
        }
        server.takeRequest(3, TimeUnit.SECONDS)
        delay(100)
        val start = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
    }

    @Test
    fun liveProgress_arrivesBeforeCheckpoint_withTrustedTotal() = runBlocking {
        val length = 512L * 1024L
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 0-${length - 1}/$length")
            .setBody(okio.Buffer().write(ByteArray(length.toInt())))
            .throttleBody(64 * 1024, 60, TimeUnit.MILLISECONDS))
        val checkpoints = mutableListOf<DownloadChunk>()
        val progress = mutableListOf<DownloadChunk>()
        val result = transport(chunkBytes = length).downloadComponent(
            "live", 0, component(length), source(length), File(dir, "live.part"), emptyList(),
            onProgress = { chunk, total ->
                assertEquals(length, total)
                assertFalse(chunk.verified)
                assertTrue(checkpoints.isEmpty())
                progress += chunk
                true
            },
            onChunk = { checkpoints += it; true },
        )
        assertEquals(TransferResult.Completed, result)
        assertTrue(progress.any { it.receivedBytes in 1 until length })
        assertEquals(length, checkpoints.single().receivedBytes)
        assertTrue(checkpoints.single().verified)
    }

    @Test
    fun unknownLength_fullResponseReportsContentLength() = runBlocking {
        server.enqueue(MockResponse().setBody("media"))
        val totals = mutableListOf<Long?>()
        val result = transport().downloadComponent(
            "headers", 0, component(null), source(null, DownloadRangeMode.NONE), File(dir, "headers.part"), emptyList(),
            onProgress = { _, total -> totals += total; true },
            onChunk = { true },
        )
        assertEquals(TransferResult.Completed, result)
        assertEquals(listOf(5L), totals)
    }

    @Test
    fun rejectedLiveProgress_doesNotCreateVerifiedCheckpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 0-3/4").setBody("data"))
        val checkpoints = mutableListOf<DownloadChunk>()
        val result = transport(chunkBytes = 4).downloadComponent(
            "stale", 0, component(4), source(4), File(dir, "stale.part"), emptyList(),
            onProgress = { _, _ -> false },
            onChunk = { checkpoints += it; true },
        )
        assertEquals(TransferResult.Paused, result)
        assertTrue(checkpoints.isEmpty())
    }

    @Test
    fun unknownLengthResumeReusesVerifiedPrefixAndDropsUnverifiedTail() = runBlocking {
        fun cropped(body: String) = MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-${body.length - 1}/${body.length}").setBody(body)
        server.enqueue(cropped("ABCD"))
        server.enqueue(cropped("EFGH"))
        server.enqueue(cropped("IJKL"))
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */12"))
        val session = com.hhst.youtubelite.extractor.YoutubeWebSession(
            "test", com.hhst.youtubelite.extractor.WebAccount.ANONYMOUS,
            0, null, null, null, null, "UA", 0, null, "test", kotlinx.serialization.json.JsonObject(emptyMap()), { "" })
        val plan = com.hhst.youtubelite.extractor.MediaRequestPlan(session,
            com.hhst.youtubelite.extractor.StreamClient.VISIONOS,
            com.hhst.youtubelite.extractor.MediaProtocol.HTTPS,
            com.hhst.youtubelite.extractor.MediaRange.QUERY, false)
        val requests = YoutubeMediaRequests({ true }, ExtractionDiagnostics())
        val transfer = DownloadTransportImpl(client = DownloadHttpClients.create(), chunkBytes = 4,
            sleeper = DownloadSleeper { }, mediaRequests = requests)
        val input = source(null, DownloadRangeMode.QUERY_PARAM).copy(requestPlan = plan)
        val dest = File(dir, "unknown.part")
        val chunks = mutableListOf<DownloadChunk>()
        assertEquals(TransferResult.Paused, transfer.downloadComponent("t1", 0, component(null), input,
            dest, emptyList(), onChunk = { chunks += it; false }))
        dest.appendText("unverified-tail")
        assertEquals(TransferResult.Completed, transfer.downloadComponent("t1", 0, component(null), input,
            dest, chunks, onChunk = { true }))
        assertEquals("ABCDEFGHIJKL", dest.readText())
        assertEquals(listOf("0-3", "4-7", "8-11", "12-15"), (1..4).map {
            server.takeRequest().requestUrl!!.queryParameter("range")
        })
    }

    @Test
    fun connectionLimitCoversOpenBodiesAcrossClonedClientsAndUpdatesLive() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 0-3/4").setBody("data")) }
        val http = DownloadHttpClients.create(maxRequests = 1)
        val firstTransport = DownloadTransportImpl(http, chunkBytes = 4)
        val secondTransport = DownloadTransportImpl(http.newBuilder().build(), chunkBytes = 4)
        val open = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.IO) {
            firstTransport.downloadComponent("first", 0, component(4), source(4), File(dir, "first.part"), emptyList(),
                onProgress = { chunk, _ -> if (chunk.receivedBytes == 0L) { open.complete(Unit); release.await() }; true },
                onChunk = { true })
        }
        try {
            withTimeout(3_000) { open.await() }
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)
            val started = CompletableDeferred<Unit>()
            val second = async(Dispatchers.IO) {
                started.complete(Unit)
                secondTransport.downloadComponent("second", 0, component(4), source(4), File(dir, "second.part"),
                    emptyList(), onChunk = { true })
            }
            started.await()
            assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
            DownloadHttpClients.setMaxConnections(http, 2)
            assertEquals(TransferResult.Completed, withTimeout(3_000) { second.await() })
            assertFalse(first.isCompleted)
        } finally { release.complete(Unit) }
        assertEquals(TransferResult.Completed, first.await())
    }

    @Test
    fun pausingWhileWaitingForAConnectionDoesNotOpenARequestOrLeakTheSlot() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 0-3/4").setBody("data")) }
        val transfer = DownloadTransportImpl(DownloadHttpClients.create(maxRequests = 1), chunkBytes = 4)
        val open = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.IO) {
            transfer.downloadComponent("owner", 0, component(4), source(4), File(dir, "owner.part"), emptyList(),
                onProgress = { chunk, _ -> if (chunk.receivedBytes == 0L) { open.complete(Unit); release.await() }; true },
                onChunk = { true })
        }
        try {
            withTimeout(3_000) { open.await() }
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)
            val pending = async(Dispatchers.IO) {
                transfer.downloadComponent("paused", 0, component(4), source(4), File(dir, "paused.part"),
                    emptyList(), onChunk = { true })
            }
            assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
            transfer.cancelInFlight("paused")
            assertEquals(TransferResult.Paused, withTimeout(3_000) { pending.await() })
            assertEquals(1, server.requestCount)
        } finally { release.complete(Unit) }
        assertEquals(TransferResult.Completed, first.await())
        assertEquals(TransferResult.Completed, withContext(Dispatchers.IO) {
            transfer.downloadComponent("next", 0, component(4), source(4), File(dir, "next.part"), emptyList(), onChunk = { true })
        })
    }

    private fun transport(
        chunkBytes: Long = 4L * 1024 * 1024,
        sleeper: DownloadSleeper = DownloadSleeper { },
        forbidden: ForbiddenRecovery? = null,
        network: NetworkMonitor = AssumeAvailableNetwork,
    ) = DownloadTransportImpl(
        client = DownloadHttpClients.create(),
        network = network,
        chunkBytes = chunkBytes,
        sleeper = sleeper,
        forbidden = forbidden,
        random = kotlin.random.Random(0),
    )

    private fun component(bytes: Long?) = InputComponent(
        id = "c1",
        assetId = "a1",
        kind = InputComponentKind.VIDEO,
        expectedBytes = bytes,
    )

    private fun source(bytes: Long?, mode: DownloadRangeMode = DownloadRangeMode.HTTP_HEADER) =
        DownloadComponentSource(
            assetKind = AssetKind.VIDEO,
            componentKind = InputComponentKind.VIDEO,
            url = server.url("/media").toString(),
            resourceIdentity = bytes?.let { "dl:x:1:::$it" },
            expectedBytes = bytes,
            rangeModeName = mode.name,
            methodName = "GET",
        )
}
