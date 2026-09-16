package com.hhst.youtubelite.extractor

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline coverage for stream-invalidation epochs and in-flight extract
 * lifecycle. Every request fails fast against a throwing interceptor, so no
 * test touches the network and no IO work outlives a test.
 */
class ExtractorEpochTest {

    /** In-memory [Cache] recording writes; reads always miss, mirroring a cold cache. */
    private open class RecordingCache : Cache {
        val putStreams = mutableListOf<Pair<String, Stream>>()
        val putMetadataCalls = mutableListOf<Pair<String, Metadata>>()
        val putChapters = mutableListOf<Pair<String, ChapterList>>()
        val invalidated = mutableListOf<String>()

        override fun getMetadata(videoId: String): Metadata? = null

        override fun putMetadata(videoId: String, metadata: Metadata) {
            putMetadataCalls += videoId to metadata
        }

        override fun getStream(videoId: String): Stream? = null

        override fun putStream(videoId: String, stream: Stream) {
            putStreams += videoId to stream
        }

        override fun invalidateStream(videoId: String) {
            invalidated += videoId
        }

        override fun getChapters(videoId: String): ChapterList? = null

        override fun putChapters(videoId: String, chapters: ChapterList) {
            putChapters += videoId to chapters
        }
    }

    /** Stores stream entries so invalidation races can be observed. */
    private class MapCache : Cache {
        private val streams = HashMap<String, Stream>()
        override fun getMetadata(videoId: String): Metadata? = null
        override fun putMetadata(videoId: String, metadata: Metadata) = Unit
        override fun getStream(videoId: String): Stream? = synchronized(this) { streams[videoId] }
        override fun putStream(videoId: String, stream: Stream) {
            synchronized(this) { streams[videoId] = stream }
        }
        override fun invalidateStream(videoId: String) {
            synchronized(this) { streams.remove(videoId) }
        }
        override fun getChapters(videoId: String): ChapterList? = null
        override fun putChapters(videoId: String, chapters: ChapterList) = Unit
    }

    /** Fails every request in-flight so extraction work never leaves the JVM. */
    private fun offlineDownloader(): HttpDownloader = HttpDownloader(
        OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline epoch test") }
            .build(),
    )

    private fun extractor(cache: Cache): Extractor = Extractor(
        downloader = offlineDownloader(),
        cache = cache,
    )

    @Test
    fun epochGuard_dropsStaleStreamWriteForItsVideoOnly() {
        val inner = RecordingCache()
        val epochs = StreamEpochGuard()
        val epoch = epochs.epochFor(ID)
        val guarded = EpochGuardedCache(inner, ID, epoch, epochs)
        val stream = Stream()

        guarded.putStream(ID, stream)
        assertEquals(1, inner.putStreams.size)

        epochs.bump(ID)
        guarded.putStream(ID, stream)
        assertEquals(1, inner.putStreams.size)

        guarded.putStream("abcdefghijk", stream)
        assertEquals(2, inner.putStreams.size)
        guarded.putMetadata(ID, Metadata())
        guarded.putChapters(ID, ChapterList())
        assertEquals(1, inner.putMetadataCalls.size)
        assertEquals(1, inner.putChapters.size)
    }

    @Test
    fun invalidateStream_invalidatesTheParsedVideoIdOnly() {
        val cache = RecordingCache()
        val extractor = extractor(cache)

        extractor.invalidateStream("https://youtu.be/$ID")
        assertEquals(listOf(ID), cache.invalidated)

        extractor.invalidateStream("definitely not an id")
        assertEquals(1, cache.invalidated.size)
    }

    @Test
    fun invalidateStream_forcesANewExtraction() {
        val cache = RecordingCache()
        val extractor = extractor(cache)

        val first = extractor.extract(ID)
        extractor.invalidateStream(ID)
        val second = extractor.extract(ID)

        assertNotSame(
            "a post-invalidation extract must not hand back the in-flight extraction",
            first,
            second,
        )
        assertTrue(cache.invalidated.contains(ID))

        runBlocking {
            listOf(first, second).forEach { extraction ->
                listOf(extraction.metadata, extraction.stream, extraction.chapters).forEach {
                    runCatching { it.await() }
                }
            }
        }
        assertEquals(0, cache.putStreams.size)
    }

    @Test
    fun olderGuardStaysStaleWhenNewerSiblingSettlesFirst() {
        val guard = StreamEpochGuard()
        val cache = RecordingCache()

        val firstEpoch = guard.epochFor(ID)
        val staleGuard = EpochGuardedCache(cache, ID, firstEpoch, guard)

        guard.bump(ID)
        val secondEpoch = guard.epochFor(ID)
        val freshGuard = EpochGuardedCache(cache, ID, secondEpoch, guard)

        freshGuard.putStream(ID, Stream())
        assertEquals(1, cache.putStreams.size)

        staleGuard.putStream(ID, Stream())
        assertEquals(1, cache.putStreams.size)
    }

    @Test
    fun fullCacheHit_repeatedExtractDoesNotThrowRecursiveUpdate() {
        val cache = FakeCache()
        cache.putMetadata(ID, Metadata().apply { id = ID; title = "t" })
        cache.putStream(ID, Stream())
        cache.putChapters(ID, ChapterList())
        val extractor = extractor(cache)
        repeat(8) {
            val extraction = extractor.extract(ID)
            runBlocking {
                extraction.metadata.await()
                extraction.stream.await()
                extraction.chapters.await()
            }
            assertTrue(extraction.metadata.success)
            assertTrue(extraction.stream.success)
            assertTrue(extraction.chapters.success)
        }
    }

    @Test
    fun concurrentExtract_reusesInFlightUntilSettled() {
        val hold = CountDownLatch(1)
        val cache = object : RecordingCache() {
            override fun getMetadata(videoId: String): Metadata? {
                hold.await()
                return null
            }
        }
        val extractor = extractor(cache)
        val first = extractor.extract(ID)
        val second = extractor.extract(ID)
        assertSame(first, second)
        hold.countDown()
        runBlocking {
            runCatching { first.metadata.await() }
            runCatching { first.stream.await() }
            runCatching { first.chapters.await() }
        }
    }

    @Test
    fun guardedPutAndBump_neverLeaveStaleCommitted() {
        val inner = MapCache()
        val guard = StreamEpochGuard()
        val stalePresent = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(400) {
                inner.invalidateStream(ID)
                val epoch = guard.epochFor(ID)
                val guarded = EpochGuardedCache(inner, ID, epoch, guard)
                val barrier = CyclicBarrier(2)
                val writer = pool.submit {
                    barrier.await(2, TimeUnit.SECONDS)
                    guarded.putStream(ID, Stream())
                }
                val bumper = pool.submit {
                    barrier.await(2, TimeUnit.SECONDS)
                    guard.withLock(ID) {
                        guard.bumpUnlocked(ID)
                        inner.invalidateStream(ID)
                    }
                }
                writer.get(2, TimeUnit.SECONDS)
                bumper.get(2, TimeUnit.SECONDS)
                if (guard.epochFor(ID) > epoch && inner.getStream(ID) != null) {
                    stalePresent.incrementAndGet()
                }
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("INVALIDATED write survived bump", 0, stalePresent.get())
        assertNull(inner.getStream(ID))
    }

    private companion object {
        const val ID = "dQw4w9WgXcQ"
    }
}
