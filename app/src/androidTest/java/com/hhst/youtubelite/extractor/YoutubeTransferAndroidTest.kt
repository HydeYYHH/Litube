package com.hhst.youtubelite.extractor

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hhst.youtubelite.cast.LocalStreamProxy
import com.hhst.youtubelite.downloader.core.*
import com.hhst.youtubelite.downloader.net.DownloadResourceIdentity
import com.hhst.youtubelite.downloader.net.DownloadTransportImpl
import com.hhst.youtubelite.downloader.io.FileIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Private test files only: production chunk verification/resume and actual phone proxy HTTP. */
@UnstableApi
class YoutubeTransferAndroidTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(ExtractionTestActivity::class.java)
    @Test fun fileResumeAndPhoneProxyRead() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val id = InstrumentationRegistry.getArguments().getString("videoId") ?: "jNQXAC9IVRw"
        val extractor = GlobalContext.get().get<Extractor>()
        val policy = GlobalContext.get().get<YoutubeMediaRequests>()
        val stream = extractor.extractFresh(id).stream.await()
        val format = stream.formats.firstOrNull { !it.videoOnly && !it.audioOnly } ?: error("No file candidate")
        val plan = requireNotNull(format.requestPlan)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).addInterceptor(policy.interceptor()).build()
        // Some real progressive formats omit contentLength; learn the total from a bounded read.
        val length = if (plan.resourceLength > 0) plan.resourceLength else {
            client.newCall(policy.build(format.url, plan, 0, 1)).execute().use { response ->
                assertEquals(206, response.code)
                val total = requireNotNull(policy.window(response, plan).total)
                assertEquals(1, requireNotNull(response.body).bytes().size)
                total
            }
        }
        assertTrue("Use a small explicit acceptance video", length in 65_537..(16L * 1024 * 1024))
        val identity = DownloadResourceIdentity.of(id, format)
        val component = InputComponent("device-component", "device-asset", InputComponentKind.VIDEO,
            expectedBytes = length, resourceIdentity = identity)
        val source = DownloadComponentSource(AssetKind.VIDEO, InputComponentKind.VIDEO, format.url,
            resourceIdentity = identity, expectedBytes = length, mimeType = format.mimeType, requestPlan = plan)
        val transport = DownloadTransportImpl(client, wifiOnly = false, chunkBytes = 64L * 1024, mediaRequests = policy)
        val directory = File(app.cacheDir, "youtube-transfer-acceptance").apply { mkdirs() }
        val dest = File(directory, "media.part")
        val chunks = mutableListOf<DownloadChunk>()
        val record = linkedMapOf<String, Any>("videoId" to id, "profile" to plan.profile.name, "expectedBytes" to length)
        val proxy = LocalStreamProxy(app, client, advertisedHost = "127.0.0.1", mediaRequests = policy)
        try {
            val paused = transport.downloadComponent("device-transfer", component, source, dest, emptyList()) {
                chunks += it
                false
            }
            assertEquals(TransferResult.Paused, paused)
            assertEquals(1, chunks.size)
            assertTrue(FileIntegrity.verify(dest, chunks[0].startByte, chunks[0].receivedBytes, chunks[0].checksum))
            val firstChecksum = chunks[0].checksum
            val completed = transport.downloadComponent("device-transfer", component, source, dest, chunks.toList()) {
                chunks += it
                true
            }
            assertEquals(TransferResult.Completed, completed)
            assertEquals(length, dest.length())
            assertTrue(FileIntegrity.verify(dest, chunks[0].startByte, chunks[0].receivedBytes, firstChecksum))
            record["resumedBytes"] = chunks[0].receivedBytes
            record["verifiedChunks"] = chunks.size
            record["downloadCompleted"] = true
            proxy.configure(null, "Device acceptance", id, format.url, format.mimeType, null, null,
                LocalStreamProxy.StreamRanges(-1, -1, length), null, videoItag = format.itag, videoRequestPlan = plan)
            proxy.start()
            // A receiver uses plain HTTP. A raw loopback socket exercises that path without
            // relaxing the application's HTTPS-only outbound networking policy.
            val uri = java.net.URI(proxy.proxyUrl("stream/v?g=${proxy.currentConfigId()}"))
            val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            java.net.Socket("127.0.0.1", uri.port).use { socket ->
                socket.soTimeout = 30_000
                socket.getOutputStream().write(("GET $target HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${uri.port}\r\nRange: bytes=65536-81919\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                val input = java.io.DataInputStream(socket.getInputStream().buffered())
                fun line(): String {
                    val text = StringBuilder()
                    while (true) {
                        val byte = input.readUnsignedByte()
                        if (byte == 10) return text.toString().removeSuffix("\r")
                        require(text.length < 8192)
                        text.append(byte.toChar())
                    }
                }
                assertEquals("206", line().split(' ')[1])
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                }
                assertEquals("16384", headers["content-length"])
                val bytes = ByteArray(16 * 1024).also(input::readFully)
                assertEquals(16 * 1024, bytes.size)
                val expected = java.io.RandomAccessFile(dest, "r").use { file ->
                    file.seek(65536)
                    ByteArray(bytes.size).also(file::readFully)
                }
                assertArrayEquals(expected, bytes)
                record["proxyStatus"] = 206
                record["proxyBytes"] = bytes.size
                record["proxyMatchesDownload"] = true
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("youtubeTransfer", Gson().toJson(record)) })
        } finally {
            proxy.stop()
            transport.cancelInFlight("device-transfer")
            File(app.filesDir, "youtube-transfer.json").writeText(Gson().toJson(record))
            File(app.filesDir, "youtube-transfer-diagnostics.txt").writeText(extractor.extractionDiagnostics())
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
