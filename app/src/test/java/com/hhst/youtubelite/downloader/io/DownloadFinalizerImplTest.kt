@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.io

import androidx.media3.common.MimeTypes
import com.hhst.youtubelite.downloader.core.MediaCombo
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.io.ExtractedTrack
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.io.FreeSpace
import com.hhst.youtubelite.downloader.io.MediaSampleIo
import com.hhst.youtubelite.downloader.io.MediaFileIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadFinalizerImplTest {
    private val finalizer = DownloadFinalizerImpl()

    @Test
    fun muxAvcAacFromTransferredInputs() = runBlocking<Unit> {
        val input = copyResource("downloader/media/avc_aac.mp4", "in-avc.mp4")
        val out = File.createTempFile("out-avc", ".mp4")
        val result = finalizer.muxAndVerify(listOf(input), out, audioOnly = false)
        assertTrue("mux $result", result is MuxResult.Ok)
        val tracks = MediaSampleIo.extract(out)
        assertTrue(tracks.any { it.mime == MimeTypes.VIDEO_H264 })
        assertTrue(tracks.any { it.mime == MimeTypes.AUDIO_AAC })
        out.delete()
        input.delete()
    }

    @Test
    fun muxAacM4aFromTransferredInput() = runBlocking<Unit> {
        val input = copyResource("downloader/media/aac.m4a", "in-aac.m4a")
        val out = File.createTempFile("out-aac", ".m4a")
        val result = finalizer.muxAndVerify(listOf(input), out, audioOnly = true)
        assertTrue(result is MuxResult.Ok)
        val tracks = MediaSampleIo.extract(out)
        assertEquals(1, tracks.count { it.mime == MimeTypes.AUDIO_AAC })
        out.delete()
        input.delete()
    }

    @Test
    fun enospc_isReportedWithoutMux() = runBlocking<Unit> {
        val input = copyResource("downloader/media/avc_aac.mp4", "enospc-in.mp4")
        val out = File.createTempFile("out-enospc", ".mp4")
        val result = DownloadFinalizerImpl(freeSpace = FreeSpace { 1L })
            .muxAndVerify(listOf(input), out, audioOnly = false)
        assertTrue(result is MuxResult.Failed)
        assertEquals("ENOSPC", (result as MuxResult.Failed).reason)
        assertFalse(out.isFile && out.length() > 0L)
        input.delete()
        out.delete()
        assertTrue(FileIntegrity.isNoSpace(java.io.IOException("ENOSPC")))
    }

    @Test
    fun avDesyncAboveTwoSeconds_isRejected() {
        val video = ExtractedTrack(
            androidx.media3.common.Format.Builder()
                .setId(1)
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .build(),
            listOf(
                MediaCombo.Sample(0L, 0, byteArrayOf(1)),
                MediaCombo.Sample(5_000_000L, 0, byteArrayOf(1)),
            ),
        )
        val audio = ExtractedTrack(
            androidx.media3.common.Format.Builder()
                .setId(2)
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .build(),
            listOf(
                MediaCombo.Sample(0L, 0, byteArrayOf(1)),
                MediaCombo.Sample(1_000_000L, 0, byteArrayOf(1)),
            ),
        )
        assertEquals("av-desync", MediaSampleIo.verify(listOf(video, audio), audioOnly = false))
        val alignedAudio = ExtractedTrack(
            audio.format,
            listOf(
                MediaCombo.Sample(0L, 0, byteArrayOf(1)),
                MediaCombo.Sample(4_500_000L, 0, byteArrayOf(1)),
            ),
        )
        assertEquals(null, MediaSampleIo.verify(listOf(video, alignedAudio), audioOnly = false))
    }

    @Test
    fun gatedCodec_isRejectedWithReason() = runBlocking<Unit> {
        val input = copyResource("downloader/media/aac.m4a", "gated.m4a")
        val out = File.createTempFile("out-gated", ".mp4")
        val result = finalizer.muxAndVerify(listOf(input), out, audioOnly = false)
        assertTrue(result is MuxResult.Gated)
        assertEquals("CODEC_NOT_ENABLED", (result as MuxResult.Gated).reason)
        input.delete()
        out.delete()
    }

    @Test
    fun fileReaderPreservesEverySampleWithoutRetainingPayloads() {
        val input = copyResource("downloader/media/avc_aac.mp4", "streaming-input.mp4")
        try {
            val expected = MediaSampleIo.extract(input)
            val scans = MediaFileIo.scan(input)
            assertEquals(expected.map { it.mime }, scans.map { it.mime })
            assertEquals(expected.map { it.samples.size.toLong() }, scans.map { it.sampleCount })
            assertEquals(expected.map { it.durationUs }, scans.map { it.durationUs })
            val positions = mutableMapOf<Int, Int>()
            val trackIndexes = scans.mapIndexed { index, track -> track.id to index }.toMap()
            MediaFileIo.read(input, onSample = { id, time, flags, bytes ->
                val position = positions[id] ?: 0
                val sample = expected[trackIndexes.getValue(id)].samples[position]
                assertEquals(sample.timeUs, time)
                assertEquals(sample.flags, flags)
                assertTrue(sample.data.contentEquals(bytes))
                positions[id] = position + 1
            })
            scans.forEach { assertEquals(it.sampleCount, positions.getValue(it.id).toLong()) }
        } finally { input.delete() }
    }

    @Test
    fun fileLargerThanAndroidHeapMuxesWithoutReadingWholeFile() = runBlocking<Unit> {
        val input = copyResource("downloader/media/avc_aac.mp4", "large-streaming-input.mp4")
        val output = File.createTempFile("large-streaming-output", ".mp4")
        try {
            java.io.RandomAccessFile(input, "rw").use { file ->
                val end = file.length()
                val padding = 640 * 1024 * 1024
                file.seek(end)
                file.writeInt(padding)
                file.writeBytes("free")
                file.setLength(end + padding)
            }
            val result = finalizer.muxAndVerify(listOf(input), output, false)
            assertTrue("mux $result", result is MuxResult.Ok)
            assertEquals(null, MediaFileIo.verify(MediaFileIo.scan(output), false))
        } finally { input.delete(); output.delete() }
    }

    private fun copyResource(path: String, name: String): File {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }
        val file = File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}")
        file.writeBytes(bytes)
        return file
    }
}
