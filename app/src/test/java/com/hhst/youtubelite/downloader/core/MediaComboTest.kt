@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.core

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

class MediaComboTest {

    @Test
    fun muxerListsAvcAndAac() {
        assertTrue(MediaCombo.muxerVideoMimes.contains(MimeTypes.VIDEO_H264))
        assertTrue(MediaCombo.muxerAudioMimes.contains(MimeTypes.AUDIO_AAC))
    }

    @Test
    fun avcAacMp4_roundTripExtractMux() {
        val original = loadSample("downloader/media/avc_aac.mp4")
        val extracted = extract(original)
        val video = extracted.first { it.format?.sampleMimeType == MimeTypes.VIDEO_H264 }
        val audio = extracted.first { it.format?.sampleMimeType == MimeTypes.AUDIO_AAC }
        assertTrue("need video samples", video.samples.isNotEmpty())
        assertTrue("need audio samples", audio.samples.isNotEmpty())

        val remuxed = File.createTempFile("avc-aac", ".mp4")
        MediaCombo.mux(
            listOf(
                MediaCombo.Track(checkNotNull(video.format), video.samples),
                MediaCombo.Track(checkNotNull(audio.format), audio.samples),
            ),
            remuxed,
        )
        val again = extract(remuxed.readBytes())
        assertTrue(again.any { it.format?.sampleMimeType == MimeTypes.VIDEO_H264 })
        assertTrue(again.any { it.format?.sampleMimeType == MimeTypes.AUDIO_AAC })
        remuxed.delete()
    }

    @Test
    fun aacM4a_roundTripExtractMux() {
        val original = loadSample("downloader/media/aac.m4a")
        val extracted = extract(original)
        val audio = extracted.first { it.format?.sampleMimeType == MimeTypes.AUDIO_AAC }
        assertTrue(audio.samples.isNotEmpty())

        val remuxed = File.createTempFile("aac", ".m4a")
        MediaCombo.mux(
            listOf(MediaCombo.Track(checkNotNull(audio.format), audio.samples)),
            remuxed,
        )
        val again = extract(remuxed.readBytes())
        assertEquals(1, again.count { it.format?.sampleMimeType == MimeTypes.AUDIO_AAC })
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

    private fun extract(mp4: ByteArray): List<DumpTrack> {
        val extractor = Mp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED,
            Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        )
        val output = DumpOutput()
        extractor.init(output)
        val input = BytesInput(mp4)
        check(extractor.sniff(input)) { "not an MP4/M4A" }
        input.resetPeekPosition()
        val seek = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        var hops = 0
        while (result != Extractor.RESULT_END_OF_INPUT) {
            result = extractor.read(input, seek)
            if (result == Extractor.RESULT_SEEK) {
                check(++hops < 64) { "seek loop" }
                input.seekTo(seek.position)
                extractor.seek(seek.position, 0)
                result = Extractor.RESULT_CONTINUE
            }
        }
        extractor.release()
        return output.tracks
    }

    private class DumpOutput : ExtractorOutput {
        val tracks = mutableListOf<DumpTrack>()
        override fun track(id: Int, type: Int): TrackOutput {
            val track = DumpTrack()
            tracks += track
            return track
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class DumpTrack : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<MediaCombo.Sample>()
        private val pending = ByteArrayOutputStream()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val buf = ByteArray(length)
            var written = 0
            while (written < length) {
                val read = input.read(buf, written, length - written)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && written == 0) return C.RESULT_END_OF_INPUT
                    throw IOException("eof")
                }
                written += read
            }
            pending.write(buf)
            return length
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pending.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val bytes = pending.toByteArray()
            pending.reset()
            val start = (bytes.size - size - offset).coerceAtLeast(0)
            samples += MediaCombo.Sample(timeUs, flags, bytes.copyOfRange(start, start + size))
        }
    }

    private class BytesInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peek = 0

        fun seekTo(position: Long) {
            pos = position.toInt().coerceIn(0, data.size)
            peek = pos
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            peek = pos
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput && pos == data.size) return false
                throw EOFException()
            }
            System.arraycopy(data, pos, target, offset, length)
            pos += length
            peek = pos
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            pos += n
            peek = pos
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) {
                if (allowEndOfInput && pos == data.size) return false
                throw EOFException()
            }
            pos += length
            peek = pos
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peek >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - peek)
            System.arraycopy(data, peek, target, offset, n)
            peek += n
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) {
                if (allowEndOfInput && peek == data.size) return false
                throw EOFException()
            }
            System.arraycopy(data, peek, target, offset, length)
            peek += length
            return true
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peek + length > data.size) {
                if (allowEndOfInput && peek == data.size) return false
                throw EOFException()
            }
            peek += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peek = pos
        }

        override fun getPeekPosition(): Long = peek.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, e: E): Unit = throw e
    }
}
