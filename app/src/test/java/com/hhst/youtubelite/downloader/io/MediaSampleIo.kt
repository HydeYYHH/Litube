@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.io

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.DataReader
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import com.hhst.youtubelite.downloader.core.MediaCombo
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Test-only MP4/fMP4 reader that retains every sample in memory. Serves as an
 * independent oracle for [MediaFileIo], which streams samples instead.
 */
data class ExtractedTrack(
    val format: Format,
    val samples: List<MediaCombo.Sample>,
) {
    val durationUs: Long get() = samples.maxOfOrNull { it.timeUs } ?: 0L
    val mime: String? get() = format.sampleMimeType
}

object MediaSampleIo {
    fun extract(file: File): List<ExtractedTrack> = extract(file.readBytes())

    fun extract(bytes: ByteArray): List<ExtractedTrack> {
        val input = BytesInput(bytes)
        var extractor: Extractor = Mp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED,
            Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        )
        if (!extractor.sniff(input)) {
            extractor.release()
            input.resetPeekPosition()
            extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED,
                FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
            check(extractor.sniff(input)) { "not an MP4/M4A" }
        }
        input.resetPeekPosition()
        val output = DumpOutput()
        extractor.init(output)
        val seek = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        var hops = 0
        while (result != Extractor.RESULT_END_OF_INPUT) {
            result = extractor.read(input, seek)
            if (result == Extractor.RESULT_SEEK) {
                check(++hops < 64) { "seek loop" }
                input.seekTo(seek.position)
                // RESULT_SEEK repositions the input for the current read;
                // extractor.seek() would restart sample selection at time 0.
                result = Extractor.RESULT_CONTINUE
            }
        }
        extractor.release()
        return output.tracks.mapNotNull { track ->
            val format = track.format ?: return@mapNotNull null
            ExtractedTrack(format, track.samples)
        }
    }

    fun verify(tracks: List<ExtractedTrack>, audioOnly: Boolean): String? {
        if (tracks.isEmpty()) return "no-tracks"
        tracks.forEach { track ->
            if (track.samples.isEmpty()) return "empty-track"
            val mime = track.mime.orEmpty()
            val times = track.samples.map { it.timeUs }
            if (times.any { it < 0L }) return "negative-timestamp"
            // Video PTS may go backwards in decode order (B-frames). Audio
            // should be nearly monotonic; allow a small encoder-delay dip.
            if (MimeTypes.isAudio(mime)) {
                var prev = Long.MIN_VALUE
                times.forEach { time ->
                    if (time + 50_000L < prev) return "timestamp-regression"
                    prev = maxOf(prev, time)
                }
            }
        }
        val video = tracks.filter { MimeTypes.isVideo(it.mime.orEmpty()) }
        val audio = tracks.filter { MimeTypes.isAudio(it.mime.orEmpty()) }
        if (audioOnly) {
            if (audio.isEmpty()) return "no-audio"
        } else {
            if (video.isEmpty()) return "no-video"
            if (audio.isEmpty()) return "no-audio"
            val vDur = video.maxOf { it.durationUs }
            val aDur = audio.maxOf { it.durationUs }
            if (kotlin.math.abs(vDur - aDur) > 2_000_000L) return "av-desync"
        }
        if (tracks.maxOf { it.durationUs } <= 0L) return "zero-duration"
        return null
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
            input: DataReader,
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
