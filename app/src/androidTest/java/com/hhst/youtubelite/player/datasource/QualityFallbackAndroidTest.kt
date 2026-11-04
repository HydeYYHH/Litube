@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.extractor.Stream
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityFallbackAndroidTest {
    private val http = OkHttpClient()
    private val resolver = MediaSourceResolver(PlayerDataSource.create(
        InstrumentationRegistry.getInstrumentation().targetContext, http))
    private val metadata = Metadata().apply { id = "qualityFallback"; duration = 20 }

    @After fun close() {
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun video(height: Int) = Format(
        url = "https://example.com/videoplayback?c=VISIONOS&itag=$height", itag = height,
        height = height, width = height * 16 / 9, fps = 30, videoOnly = true,
        codec = "avc1.42c01e", mimeType = "video/mp4", bitrate = 300_000,
        initStart = 0, initEnd = 99, indexStart = 100, indexEnd = 199,
    )

    @Test fun missingAdaptiveQualityKeepsThePlayableVideoPool() {
        val audio = Format(url = "https://example.com/videoplayback?c=VISIONOS&itag=140",
            itag = 140, audioOnly = true, codec = "mp4a.40.2", mimeType = "audio/mp4", bitrate = 128_000,
            initStart = 0, initEnd = 99, indexStart = 100, indexEnd = 199)
        val stream = Stream().apply { formats = listOf(video(360), video(720), audio) }
        for ((requested, expected) in listOf("1080p60" to 720, "480p" to 360, "144p" to 360)) {
            val source = resolver.resolve(stream, metadata, requested)
            assertEquals(expected, source.videoFormat?.height)
            assertEquals(listOf(720, 360), source.videoFormats.map { it.height })
            assertEquals(audio, source.audioFormat)
        }
    }

    @Test fun missingMuxedQualityStillResolvesAVideoInsteadOfFailingOrUsingAudioOnly() {
        val muxed = video(360).copy(videoOnly = false)
        val stream = Stream().apply { formats = listOf(muxed) }
        for (forceMuxed in listOf(false, true)) {
            assertEquals(muxed, resolver.resolve(stream, metadata, "1080p", forceMuxed = forceMuxed).videoFormat)
        }
    }
}
