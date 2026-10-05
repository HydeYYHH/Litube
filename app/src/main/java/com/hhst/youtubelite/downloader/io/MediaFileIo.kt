@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.io

import androidx.media3.common.C
import java.io.EOFException
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** File-backed MP4/fMP4 reads. Scanning retains metadata; muxing retains only the current sample. */
object MediaFileIo {
    data class Track(
        val id: Int,
        val format: Format,
        val sampleCount: Long,
        val durationUs: Long,
        val error: String?,
    ) {
        val mime: String? get() = format.sampleMimeType
    }

    fun scan(file: File, check: () -> Unit = {}): List<Track> = read(file, check = check)

    fun read(
        file: File,
        onSample: ((Int, Long, Int, ByteArray) -> Unit)? = null,
        check: () -> Unit = {},
    ): List<Track> = RandomAccessFile(file, "r").use { source ->
        fun inputAt(position: Long): ExtractorInput {
            require(position in 0..source.length()) { "invalid-media-seek" }
            source.seek(position)
            return DefaultExtractorInput({ buffer, offset, length ->
                check()
                source.read(buffer, offset, length)
            }, position, source.length())
        }
        var input = inputAt(0)
        var extractor: Extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED,
            Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
        try {
            if (!extractor.sniff(input)) {
                extractor.release()
                input.resetPeekPosition()
                extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED,
                    FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA)
                require(extractor.sniff(input)) { "not an MP4/M4A" }
            }
            input.resetPeekPosition()
            val output = Output(onSample, check)
            extractor.init(output)
            val seek = PositionHolder()
            var consecutiveSeeks = 0
            while (true) {
                check()
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> {
                        require(++consecutiveSeeks < 64) { "seek loop" }
                        input = inputAt(seek.position)
                    }
                    else -> consecutiveSeeks = 0
                }
            }
            output.tracks.mapNotNull { (id, track) ->
                track.format?.let { Track(id, it, track.count, track.durationUs, track.error) }
            }
        } finally {
            extractor.release()
        }
    }

    fun verify(tracks: List<Track>, audioOnly: Boolean): String? {
        if (tracks.isEmpty()) return "no-tracks"
        tracks.forEach {
            if (it.sampleCount == 0L) return "empty-track"
            if (it.error != null) return it.error
        }
        val video = tracks.filter { MimeTypes.isVideo(it.mime.orEmpty()) }
        val audio = tracks.filter { MimeTypes.isAudio(it.mime.orEmpty()) }
        if (audio.isEmpty()) return "no-audio"
        if (!audioOnly) {
            if (video.isEmpty()) return "no-video"
            if (kotlin.math.abs(video.maxOf { it.durationUs } - audio.maxOf { it.durationUs }) > 2_000_000L) {
                return "av-desync"
            }
        }
        if (tracks.maxOf { it.durationUs } <= 0L) return "zero-duration"
        return null
    }

    private class Output(
        private val onSample: ((Int, Long, Int, ByteArray) -> Unit)?,
        private val check: () -> Unit,
    ) : ExtractorOutput {
        val tracks = linkedMapOf<Int, SampleOutput>()
        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { SampleOutput(id, onSample, check) }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class SampleOutput(
        private val id: Int,
        private val onSample: ((Int, Long, Int, ByteArray) -> Unit)?,
        private val check: () -> Unit,
    ) : TrackOutput {
        var format: Format? = null
        var count = 0L
        var durationUs = Long.MIN_VALUE
        var error: String? = null
        private var previousAudioTimeUs = Long.MIN_VALUE
        private val scratch = ByteArray(32 * 1024)
        private val pending = if (onSample == null) null else ByteArrayOutputStream()

        override fun format(format: Format) { this.format = format }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            check()
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return read
                throw EOFException()
            }
            pending?.write(scratch, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            check()
            pending?.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check()
            count++
            durationUs = maxOf(durationUs, timeUs)
            if (timeUs < 0) error = error ?: "negative-timestamp"
            if (MimeTypes.isAudio(format?.sampleMimeType.orEmpty())) {
                if (timeUs + 50_000L < previousAudioTimeUs) error = error ?: "timestamp-regression"
                previousAudioTimeUs = maxOf(previousAudioTimeUs, timeUs)
            }
            if (cryptoData != null) error = error ?: "encrypted-sample"
            if (pending != null && onSample != null) {
                val bytes = pending.toByteArray()
                val start = bytes.size - size - offset
                require(start >= 0 && offset >= 0) { "invalid-sample-size" }
                pending.reset()
                onSample(id, timeUs, flags, bytes.copyOfRange(start, start + size))
                if (offset > 0) pending.write(bytes, bytes.size - offset, offset)
            }
        }
    }
}
