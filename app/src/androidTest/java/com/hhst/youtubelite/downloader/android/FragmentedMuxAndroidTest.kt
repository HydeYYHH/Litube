@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.downloader.android

import androidx.media3.common.MimeTypes
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.io.DownloadFinalizerImpl
import com.hhst.youtubelite.downloader.io.MediaFileIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Fragment parsing uses Android Pair/SparseArray, so exercise it on the actual runtime. */
class FragmentedMuxAndroidTest {
    @Test fun fragmentedAvcAacMuxesToReadableMp4() = verify("fragmented_avc_aac.mp4", false)
    @Test fun fragmentedAacMuxesToReadableM4a() = verify("fragmented_aac.m4a", true)

    private fun verify(name: String, audioOnly: Boolean) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = File.createTempFile("fragmented-input", ".mp4", instrumentation.targetContext.cacheDir)
        val output = File.createTempFile("fragmented-output", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("downloader/media/$name").use { stream ->
                input.outputStream().use(stream::copyTo)
            }
            val result = DownloadFinalizerImpl().muxAndVerify(listOf(input), output, audioOnly)
            assertTrue("mux $result", result is MuxResult.Ok)
            val tracks = MediaFileIo.scan(output)
            assertEquals(null, MediaFileIo.verify(tracks, audioOnly))
            assertTrue(tracks.any { it.mime == MimeTypes.AUDIO_AAC && it.sampleCount > 0L })
            if (!audioOnly) assertTrue(tracks.any { it.mime == MimeTypes.VIDEO_H264 && it.sampleCount > 0L })
            else assertEquals(1, tracks.size)
        } finally { input.delete(); output.delete() }
    }
}
