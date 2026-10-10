package com.hhst.youtubelite.extractor

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Live extraction against the media host; opt-in via YTL_NETWORK_TESTS=1.
 *
 * These tests hit real media endpoints, which block datacenter/VPN egress
 * in ways a connectivity probe cannot see (NewPipe then fails on consent
 * pages or redirect loops). They are skipped unless explicitly enabled so a
 * hostile network cannot fail an otherwise-green CI run.
 */
class ExtractorTest {

    private lateinit var extractor: Extractor

    @Before
    fun setUp() {
        assumeTrue(
            "set YTL_NETWORK_TESTS=1 to run the live extraction tests",
            System.getenv("YTL_NETWORK_TESTS") == "1",
        )
        assumeTrue("network unavailable", isOnline())
        extractor = Extractor(
            downloader = HttpDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build(),
            ),
            cache = FakeCache(),
        )
    }

    /** No chapter markers. */
    @Test
    fun noChapters_YQHsXMglC9A() {
        val caseStartNs = System.nanoTime()
        val extraction = extractor.extract(idUrl("YQHsXMglC9A"))
        val info = collect(extraction)
        val totalMs = elapsedMs(caseStartNs)

        assertEquals("YQHsXMglC9A", info.metadata.id)
        assertTrue(info.metadata.title.isNotBlank())
        assertNotNull(info.metadata.thumbnailUrl)
        assertTrue(info.metadata.duration > 0)

        assertFalse(info.stream.formats.isEmpty())
        assertTrue(info.stream.formats.any { it.url.startsWith("http") })

        assertTrue(
            "YQHsXMglC9A should have no chapters, got ${info.chapters.chapters.size}",
            info.chapters.chapters.isEmpty(),
        )

        logReport("YQHsXMglC9A", info, totalMs)
    }

    /** Has chapter markers. */
    @Test
    fun hasChapters_gJrjgg1KVL4() {
        val caseStartNs = System.nanoTime()
        val extraction = extractor.extract(idUrl("gJrjgg1KVL4"))
        val info = collect(extraction)
        val totalMs = elapsedMs(caseStartNs)

        assertEquals("gJrjgg1KVL4", info.metadata.id)
        assertTrue(info.metadata.title.isNotBlank())
        assertNotNull(info.metadata.thumbnailUrl)
        assertTrue(info.metadata.duration > 0)

        assertFalse(info.stream.formats.isEmpty())
        assertTrue(info.stream.formats.any { it.url.startsWith("http") })

        assertFalse("gJrjgg1KVL4 should have chapters", info.chapters.chapters.isEmpty())
        assertTrue(info.chapters.chapters.all { it.startSeconds >= 0 })
        assertTrue(info.chapters.chapters.any { it.title.isNotBlank() })

        logReport("gJrjgg1KVL4", info, totalMs)
    }

    private fun collect(extraction: Extraction): Collected {
        val metadata = extraction.metadata.get(TIMEOUT_S, TimeUnit.SECONDS)
        val stream = extraction.stream.get(TIMEOUT_S, TimeUnit.SECONDS)
        val chapters = extraction.chapters.get(TIMEOUT_S, TimeUnit.SECONDS)
        assertTrue(extraction.metadata.success)
        assertTrue(extraction.stream.success)
        assertTrue(extraction.chapters.success)
        return Collected(
            metadata = metadata,
            stream = stream,
            chapters = chapters,
            metadataMs = extraction.metadata.elapsedMs,
            streamMs = extraction.stream.elapsedMs,
            chaptersMs = extraction.chapters.elapsedMs,
        )
    }

    private fun logReport(videoId: String, info: Collected, totalMs: Long) {
        println(
            "[$videoId] timing: " +
                "total=${totalMs}ms " +
                "metadata=${info.metadataMs}ms " +
                "stream=${info.streamMs}ms " +
                "chapters=${info.chaptersMs}ms",
        )
        println(
            "[$videoId] summary: title=${info.metadata.title} " +
                "formats=${info.stream.formats.size} " +
                "subtitles=${info.stream.subtitles.size} " +
                "chapters=${info.chapters.chapters.size}",
        )
    }

    private fun isOnline(): Boolean = try {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
            .newCall(
                okhttp3.Request.Builder()
                    .url("https://www.youtube.com/generate_204")
                    .get()
                    .build(),
            )
            .execute()
            .use { it.isSuccessful || it.code in 200..399 }
    } catch (_: Exception) {
        false
    }

    private data class Collected(
        val metadata: Metadata,
        val stream: Stream,
        val chapters: ChapterList,
        val metadataMs: Long,
        val streamMs: Long,
        val chaptersMs: Long,
    )

    companion object {
        private const val TIMEOUT_S = 90L

        private fun idUrl(id: String): String = "https://www.youtube.com/watch?v=$id"

        private fun elapsedMs(startNs: Long): Long =
            (System.nanoTime() - startNs) / 1_000_000L
    }
}
