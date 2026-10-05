@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.downloader.mux

import androidx.media3.common.MimeTypes
import com.hhst.youtubelite.downloader.core.DownloadFinalizer
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.core.MediaCombo
import com.hhst.youtubelite.downloader.core.MuxResult
import com.hhst.youtubelite.downloader.io.DefaultFreeSpace
import com.hhst.youtubelite.downloader.io.FileIntegrity
import com.hhst.youtubelite.downloader.io.FreeSpace
import com.hhst.youtubelite.downloader.io.MediaFileIo
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Media3 1.10.0 sample mux. Enabled combos: AVC+AAC MP4 and AAC M4A.
 * Pause stops at a sample boundary; resume redoes the unfinished mux file.
 */
class DownloadFinalizerImpl(
    private val muxPermits: Semaphore = Semaphore(DownloadSettings.MAX_CONCURRENT_MUX),
    private val freeSpace: FreeSpace = DefaultFreeSpace,
) : DownloadFinalizer {

    private val gate = Mutex()

    override suspend fun muxAndVerify(
        inputs: List<File>,
        output: File,
        audioOnly: Boolean,
    ): MuxResult {
        coroutineContext.ensureActive()
        if (inputs.isEmpty() || inputs.any { !it.isFile || it.length() <= 0L }) {
            return MuxResult.Failed("missing-input")
        }
        val needed = FileIntegrity.estimateNeeded(inputs, includeMux = true, includePublishCopy = false)
        val dir = output.parentFile ?: inputs.first().parentFile
        if (dir != null && freeSpace.bytes(dir) in 0 until needed) {
            return MuxResult.Failed("ENOSPC")
        }
        return muxPermits.withPermit {
            gate.withLock {
                coroutineContext.ensureActive()
                val active = coroutineContext
                runCatching { muxLocked(inputs, output, audioOnly) { active.ensureActive() } }
                    .getOrElse { error ->
                        output.delete()
                        when {
                            error is CancellationException -> MuxResult.Interrupted
                            FileIntegrity.isNoSpace(error) -> MuxResult.Failed("ENOSPC")
                            error is OutOfMemoryError -> MuxResult.Failed("ENOMEM")
                            else -> MuxResult.Failed(error.message ?: "mux")
                        }
                    }
            }
        }
    }

    private fun muxLocked(inputs: List<File>, output: File, audioOnly: Boolean, check: () -> Unit): MuxResult {
        val scans = inputs.map { MediaFileIo.scan(it, check) }
        val extracted = scans.flatten()
        val gated = gatedReason(extracted, audioOnly)
        if (gated != null) return MuxResult.Gated(gated)
        val verifyIn = MediaFileIo.verify(extracted, audioOnly)
        if (verifyIn != null) return MuxResult.Failed(verifyIn)
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        FileOutputStream(output).use { stream ->
            @Suppress("DEPRECATION")
            Mp4Muxer.Builder(stream).setSampleBatchingEnabled(false).build().use { muxer ->
                val trackIds = scans.map { tracks -> tracks.associate { it.id to muxer.addTrack(it.format) } }
                inputs.forEachIndexed { index, file ->
                    MediaFileIo.read(file, onSample = { id, timeUs, flags, bytes ->
                        check()
                        muxer.writeSampleData(trackIds[index].getValue(id), ByteBuffer.wrap(bytes),
                            BufferInfo(timeUs, bytes.size, flags))
                    }, check = check)
                }
            }
        }
        if (!output.isFile || output.length() <= 0L) return MuxResult.Failed("empty-output")
        val again = MediaFileIo.scan(output, check)
        val verifyOut = MediaFileIo.verify(again, audioOnly)
        if (verifyOut != null) {
            output.delete()
            return MuxResult.Failed(verifyOut)
        }
        return MuxResult.Ok(again.maxOf { it.durationUs })
    }

    private fun gatedReason(tracks: List<MediaFileIo.Track>, audioOnly: Boolean): String? {
        val videoMime = tracks.mapNotNull { it.mime }.firstOrNull { MimeTypes.isVideo(it) }
        val audioMime = tracks.mapNotNull { it.mime }.firstOrNull { MimeTypes.isAudio(it) }
        val enabled = MediaCombo.matrix(provenAvcAac = true, provenAacM4a = true)
        val ok = if (audioOnly) {
            enabled.any {
                it.status == MediaCombo.Status.ENABLED &&
                    it.container == "M4A" &&
                    it.audioMime == audioMime
            }
        } else {
            enabled.any {
                it.status == MediaCombo.Status.ENABLED &&
                    it.container == "MP4" &&
                    it.videoMime == videoMime &&
                    it.audioMime == audioMime
            }
        }
        return if (ok) null else "CODEC_NOT_ENABLED"
    }
}
