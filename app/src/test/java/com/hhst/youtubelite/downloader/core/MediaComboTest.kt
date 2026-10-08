@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.core

import androidx.media3.common.MimeTypes
import com.hhst.youtubelite.downloader.io.MediaSampleIo
import com.hhst.youtubelite.downloader.io.MuxTrack
import com.hhst.youtubelite.downloader.io.mux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Proves the MediaCombo acceptance matrix on real MP4/M4A extract-mux round trips. */
class MediaComboTest {

    @Test
    fun avcAacMp4_roundTripExtractMux() {
        val extracted = MediaSampleIo.extract(loadSample("downloader/media/avc_aac.mp4"))
        val video = extracted.first { it.mime == MimeTypes.VIDEO_H264 }
        val audio = extracted.first { it.mime == MimeTypes.AUDIO_AAC }
        assertTrue("need video samples", video.samples.isNotEmpty())
        assertTrue("need audio samples", audio.samples.isNotEmpty())

        val remuxed = File.createTempFile("avc-aac", ".mp4")
        mux(
            listOf(
                MuxTrack(checkNotNull(video.format), video.samples),
                MuxTrack(checkNotNull(audio.format), audio.samples),
            ),
            remuxed,
        )
        val again = MediaSampleIo.extract(remuxed.readBytes())
        assertTrue(again.any { it.mime == MimeTypes.VIDEO_H264 })
        assertTrue(again.any { it.mime == MimeTypes.AUDIO_AAC })
        remuxed.delete()
    }

    @Test
    fun aacM4a_roundTripExtractMux() {
        val extracted = MediaSampleIo.extract(loadSample("downloader/media/aac.m4a"))
        val audio = extracted.first { it.mime == MimeTypes.AUDIO_AAC }
        assertTrue(audio.samples.isNotEmpty())

        val remuxed = File.createTempFile("aac", ".m4a")
        mux(listOf(MuxTrack(checkNotNull(audio.format), audio.samples)), remuxed)
        val again = MediaSampleIo.extract(remuxed.readBytes())
        assertEquals(1, again.count { it.mime == MimeTypes.AUDIO_AAC })
        remuxed.delete()
    }

    @Test
    fun matrixMarksProvenCombosEnabled() {
        val entries = MediaCombo.matrix(provenAvcAac = true, provenAacM4a = true)
        val avcAac = entries.first { it.videoMime == MimeTypes.VIDEO_H264 && it.audioMime == MimeTypes.AUDIO_AAC }
        val m4a = entries.first { it.container == "M4A" }
        assertEquals(MediaCombo.Status.ENABLED, avcAac.status)
        assertEquals(MediaCombo.Status.ENABLED, m4a.status)
        assertTrue(entries.filter { it.status == MediaCombo.Status.GATED }.size >= 4)
    }

    private fun loadSample(path: String): ByteArray {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing test resource $path"
        }
        return stream.use { it.readBytes() }
    }
}
