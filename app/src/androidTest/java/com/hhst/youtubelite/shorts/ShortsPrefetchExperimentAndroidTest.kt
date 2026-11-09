@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.player.datasource.MediaSourceResolver
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.player.datasource.YoutubePlaybackCacheKey
import com.hhst.youtubelite.player.engine.defaultLoadControl
import com.hhst.youtubelite.player.engine.streamCache
import com.hhst.youtubelite.shorts.data.ShortsMediaPreloader
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled on-device experiment: identical media, latency, lead time and cache path. */
class ShortsPrefetchExperimentAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun compareSerialParallelAndColdFirstFrame() = runBlocking {
        val records = mutableListOf<Map<String, Any>>()
        // Reverse the order on the second pass to expose decoder/order effects.
        for (mode in listOf("cold", "serial", "parallel", "production", "production", "parallel", "serial", "cold")) {
            Fixture(1_100).use { fixture ->
                val started = SystemClock.elapsedRealtime()
                val warming = launch(Dispatchers.IO) {
                    if (mode == "production") fixture.preloader.preload(fixture.id)
                    else if (mode != "cold") {
                        val deadline = started + ShortsMediaPreloader.WARM_TIME_MS
                        if (mode == "parallel") coroutineScope {
                            fixture.formats.map { format -> async {
                                val requestContext = coroutineContext
                                fixture.head(format, deadline) { requestContext.ensureActive() }
                            } }.awaitAll()
                        } else for (format in fixture.formats) fixture.head(format, deadline) { coroutineContext.ensureActive() }
                    }
                }
                delay((started + 1_800 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                val covered = fixture.formats.map { fixture.cache.getCachedBytes(YoutubePlaybackCacheKey.of(it), 0, fixture.bytes(it).size.toLong()) }
                val requestsAtTap = fixture.server.requestCount
                warming.cancel()
                fixture.preloader.cancel()
                val frame = firstFrame(fixture)
                withTimeout(8_000) { warming.join() }
                records += linkedMapOf("mode" to mode, "headerDelayMs" to 1_100, "leadMs" to 1_800,
                    "audioBytesAtTap" to covered[0], "videoBytesAtTap" to covered[1],
                    "tapToFrameMs" to frame, "requestsAtTap" to requestsAtTap,
                    "requestsTotal" to fixture.server.requestCount)
                instrumentation.sendStatus(2, Bundle().apply { putString("prefetchExperiment", com.hhst.youtubelite.core.TestJson.any(records.last())) })
            }
        }
        File(app.filesDir, "shorts-prefetch-experiment.json").writeText(com.hhst.youtubelite.core.TestJson.any(records))
        val serial = records.filter { it["mode"] == "serial" }.map { it["tapToFrameMs"] as Long }.average()
        val parallel = records.filter { it["mode"] == "parallel" }.map { it["tapToFrameMs"] as Long }.average()
        assertTrue("Parallel warming must improve actual decoded-frame latency: $records", parallel < serial * .7)
        val production = records.filter { it["mode"] == "production" }.map { it["tapToFrameMs"] as Long }.average()
        assertTrue("Production warming must deliver the measured benefit: $records", production < serial * .7)
    }

    @Test fun stalledHeadersRespectWarmBudget() {
        Fixture(3_500).use { fixture ->
            val started = SystemClock.elapsedRealtime()
            fixture.head(fixture.formats.last(), started + ShortsMediaPreloader.WARM_TIME_MS) {}
            val elapsed = SystemClock.elapsedRealtime() - started
            instrumentation.sendStatus(2, Bundle().apply { putLong("stalledPrefetchMs", elapsed) })
            assertTrue("A two-second warm must not wait for late headers: ${elapsed}ms", elapsed < 2_600)
        }
    }

    @Test fun refreshedSignedUrlReusesBytesButDifferentResourceDoesNot() {
        Fixture(0).use { fixture ->
            val format = fixture.formats.last()
            fixture.head(format, SystemClock.elapsedRealtime() + 2_000) {}
            val requests = fixture.server.requestCount
            val refreshed = format.copy(url = format.url + "&sig=rotated&expire=9999999999")
            fixture.sources.formats(listOf(refreshed)).createDataSource().let { source ->
                try {
                    source.open(DataSpec.Builder().setUri(refreshed.url).setLength(4_096).build())
                    val bytes = ByteArray(4_096)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = source.read(bytes, offset, bytes.size - offset)
                        assertTrue(count != C.RESULT_END_OF_INPUT)
                        offset += count
                    }
                    assertArrayEquals(fixture.bytes(format).copyOf(bytes.size), bytes)
                } finally { source.close() }
            }
            assertEquals("URL refresh discarded reusable media", requests, fixture.server.requestCount)
            val different = refreshed.copy(resourceIdentity = refreshed.resourceIdentity + ":different-track")
            assertNotEquals(YoutubePlaybackCacheKey.of(refreshed), YoutubePlaybackCacheKey.of(different))
            assertEquals(0L, fixture.cache.getCachedBytes(YoutubePlaybackCacheKey.of(different), 0, 4_096))
        }
    }

    private fun firstFrame(fixture: Fixture): Long {
        val result = MediaSourceResolver(fixture.sources).resolve(Stream(formats = fixture.formats), Metadata(id = fixture.id, duration = 4))
        val completed = CountDownLatch(1)
        val failure = AtomicReference<String>()
        var first = 0L
        lateinit var player: ExoPlayer
        val started = SystemClock.elapsedRealtime()
        activity.scenario.onActivity { host ->
            player = ExoPlayer.Builder(host).setLoadControl(defaultLoadControl()).build().apply {
                volume = 0f
                setVideoSurfaceView(host.surface)
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { first = SystemClock.elapsedRealtime(); completed.countDown() }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCodeName); completed.countDown() }
                })
                setMediaSource(result.mediaSource)
                prepare()
                play()
            }
        }
        try {
            assertTrue("First frame timed out", completed.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue(first > 0)
            return first - started
        } finally { instrumentation.runOnMainSync { player.release() } }
    }

    private inner class Fixture(private val delayMs: Long) : AutoCloseable {
        val id = UUID.randomUUID().toString().replace("-", "").take(11)
        private val audio = instrumentation.context.assets.open("player/prefetch/audio.mp4").use { it.readBytes() }
        private val video = instrumentation.context.assets.open("player/prefetch/video.mp4").use { it.readBytes() }
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val bytes = if (request.requestUrl!!.encodedPath.contains("audio")) audio else video
                    val range = request.requestUrl!!.queryParameter("range") ?: request.getHeader("Range")?.removePrefix("bytes=") ?: "0-"
                    val from = range.substringBefore('-').toInt()
                    val to = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                    if (from > to) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                    return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $from-$to/${bytes.size}")
                        .setBody(Buffer().write(bytes, from, to - from + 1)).setHeadersDelay(delayMs, TimeUnit.MILLISECONDS)
                }
            }
            start()
        }
        private val session = YoutubeWebSession("experiment", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "fixture-UA", 0, null, "fixture", JsonObject(emptyMap())) { "" }
        private val http = OkHttpClient()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sources = PlayerDataSource.create(app, http, YoutubeMediaRequests({ true }, ExtractionDiagnostics()))
        val cache = PlayerDataSource.getCache(app)
        val formats = listOf(format(audio, true), format(video, false))
        val preloader = ShortsMediaPreloader(app, Extractor(streamCache(Stream(formats = formats)), scope), sources)
        fun bytes(format: Format) = if (format.audioOnly) audio else video
        fun head(format: Format, deadline: Long, check: () -> Unit): Long {
            val method = ShortsMediaPreloader::class.java.getDeclaredMethod("head", String::class.java, Format::class.java,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
            method.isAccessible = true
            try { return method.invoke(preloader, id, format, ShortsMediaPreloader.PER_FORMAT_BUDGET_BYTES, deadline, check) as Long }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        }
        private fun format(bytes: ByteArray, audio: Boolean): Format {
            var offset = 0
            var indexStart = -1
            var indexEnd = -1
            while (offset + 8 <= bytes.size) {
                val size = ByteBuffer.wrap(bytes, offset, 4).int
                if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "sidx") { indexStart = offset; indexEnd = offset + size - 1; break }
                require(size >= 8)
                offset += size
            }
            require(indexStart > 0)
            val name = if (audio) "audio" else "video"
            return Format(url = server.url("/$name.mp4?id=$id&itag=${if (audio) 140 else 134}").toString(),
                audioOnly = audio, videoOnly = !audio, mimeType = if (audio) "audio/mp4" else "video/mp4",
                codec = if (audio) "mp4a.40.2" else "avc1.64000c", bitrate = if (audio) 64_000 else 250_000,
                height = if (audio) 0 else 180, width = if (audio) 0 else 320, fps = if (audio) 0 else 24,
                itag = if (audio) 140 else 134, initStart = 0, initEnd = indexStart - 1,
                indexStart = indexStart, indexEnd = indexEnd, approxDurationMs = 4_000,
                formatKey = "$id:$name", resourceIdentity = "$id:$name",
                requestPlan = MediaRequestPlan(session, StreamClient.VISIONOS, MediaProtocol.DASH, MediaRange.QUERY, resourceLength = bytes.size.toLong()))
        }
        override fun close() {
            preloader.cancel()
            scope.cancel()
            for (format in formats) cache.removeResource(YoutubePlaybackCacheKey.of(format))
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
