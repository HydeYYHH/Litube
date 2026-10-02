package com.hhst.youtubelite.downloader.net

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadChunk
import com.hhst.youtubelite.downloader.core.DownloadComponentSource
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.InputComponentKind
import com.hhst.youtubelite.downloader.core.TransferResult
import com.hhst.youtubelite.downloader.io.AssumeAvailableNetwork
import com.hhst.youtubelite.downloader.io.NetworkKind
import com.hhst.youtubelite.downloader.io.NetworkMonitor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class DownloadTransportTest {
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
            forbidden = ForbiddenRecovery { _, _ ->
                recovered = true
                RecoveredSource(source(4), needsRedownload = true, exhausted = false)
            },
        )
        val dest = File(dir, "f.part")
        val result = transport.downloadComponent(
            taskId = "t1",
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
            forbidden = ForbiddenRecovery { _, _ ->
                RecoveredSource(source(4), needsRedownload = true, exhausted = false)
            },
        ).downloadComponent(
            taskId = "t1",
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
        val client = DownloadHttpClients.create().newBuilder()
            .addInterceptor { throw java.io.IOException("No space left on device") }
            .build()
        val result = DownloadTransportImpl(
            client = client,
            chunkBytes = 4,
            sleeper = { },
            random = kotlin.random.Random(0),
        ).downloadComponent(
            taskId = "t1",
            component = component(4),
            source = source(4),
            dest = File(dir, "enospc.part"),
            verified = emptyList(),
            onChunk = { true },
        )
        assertEquals(TransferResult.Failed("ENOSPC"), result)
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
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 8-15/16")
                .setBodyDelay(30, TimeUnit.SECONDS)
                .setBody(okio.Buffer().write(body)),
        )
        val chunks = mutableListOf<DownloadChunk>()
        val t = transport(chunkBytes = 8)
        val dest = File(dir, "p.part")
        val job = async {
            t.downloadComponent(
                taskId = "t1",
                component = component(16),
                source = source(16),
                dest = dest,
                verified = emptyList(),
                onChunk = { chunks += it; true },
            )
        }
        delay(200)
        t.cancelInFlight("t1")
        val result = job.await()
        assertTrue(result == TransferResult.Paused || result is TransferResult.Failed || result == TransferResult.Completed)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.verified })
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
            checksum = com.hhst.youtubelite.downloader.io.FileIntegrity.sha256(dest, 0, 8),
        )
        com.hhst.youtubelite.downloader.io.FileIntegrity.writeChecksum(dest, 0, first.checksum!!)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 8-15/16")
                .setBody(okio.Buffer().write(ByteArray(8) { (it + 8).toByte() })),
        )
        val chunks = mutableListOf<DownloadChunk>()
        val result = transport(chunkBytes = 8).downloadComponent(
            taskId = "t1",
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
