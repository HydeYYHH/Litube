@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.cast

import androidx.media3.extractor.ChunkIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.MoreExecutors
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CastIndexLoaderAndroidTest {
    @Test fun aSingleWorkerLoadsBothNativeProxyIndices() {
        val media = CastVmFixture.media()
        val video = CastVmFixture.bytes("cast-video.mp4")
        val audio = CastVmFixture.bytes("cast-audio.m4a")
        val upstream = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val bytes = if (session.uri == "/v") video else audio
                val rawRange = session.parameters["range"]?.firstOrNull() ?: session.headers["range"]?.removePrefix("bytes=")
                val range = rawRange?.split('-')
                val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                val end = (range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                return newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    "application/octet-stream", bytes.copyOfRange(start, end + 1).inputStream(), (end - start + 1).toLong()).apply {
                    if (range != null) addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                }
            }
        }
        upstream.start()
        val workers = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        val loader = CastIndexLoader<ChunkIndex>(workers)
        val done = CountDownLatch(1)
        val result = AtomicReference<Pair<ChunkIndex?, ChunkIndex?>>()
        val proxy = LocalStreamProxy(InstrumentationRegistry.getInstrumentation().targetContext,
            OkHttpClient(), advertisedHost = "127.0.0.1")
        fun ranges(token: String) = media.getJSONObject(token).let {
            LocalStreamProxy.StreamRanges(it.getLong("indexStart"), it.getLong("indexEnd"), -1)
        }
        try {
            proxy.configure(null, "Film", "local-film", "http://127.0.0.1:${upstream.listeningPort}/v", "video/mp4",
                "http://127.0.0.1:${upstream.listeningPort}/a", "audio/mp4", ranges("v"), ranges("a"))
            loader.load({ proxy.fetchChunkIndex("v") }, { proxy.fetchChunkIndex("a") }) { v, a, upgrade ->
                if (!upgrade) { result.set(v to a); done.countDown() }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(requireNotNull(result.get().first).length > 0)
            assertTrue(requireNotNull(result.get().second).length > 0)
        } finally {
            loader.cancel()
            workers.shutdownNow()
            upstream.stop()
            proxy.stop()
        }
    }
}
