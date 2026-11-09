@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Retains the platform decoder and Opus preroll while avoiding decode of whole old DASH chunks. */
internal open class PlaybackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        out += SeekAudioRenderer(context, codecAdapterFactory, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, audioSink)
    }
}

private class SeekAudioRenderer(
    context: Context, adapterFactory: MediaCodecAdapter.Factory, selector: MediaCodecSelector,
    fallback: Boolean, handler: Handler, listener: AudioRendererEventListener, sink: AudioSink,
) : MediaCodecAudioRenderer(context, adapterFactory, selector, fallback, handler, listener, sink) {
    private var opus = false
    private var prerollUs = MIN_PREROLL_US
    private var trimPending = false

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        trimPending = true
    }

    override fun onInputFormatChanged(holder: FormatHolder): DecoderReuseEvaluation? {
        val format = holder.format
        opus = format?.sampleMimeType == MimeTypes.AUDIO_OPUS && format.drmInitData == null
        val declared = format?.initializationData?.getOrNull(2)
        prerollUs = if (declared?.size == Long.SIZE_BYTES) maxOf(MIN_PREROLL_US,
            ByteBuffer.wrap(declared).order(ByteOrder.nativeOrder()).long / 1_000) else MIN_PREROLL_US
        return super.onInputFormatChanged(holder)
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        // An empty/partially loaded queue can make skipSource return zero before the
        // seek target arrives. Only a real input timestamp proves preroll was reached.
        if (buffer.timeUs >= lastResetPositionUs - prerollUs) trimPending = false
        super.onQueueInputBuffer(buffer)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (opus && trimPending) {
            decoderCounters.skippedInputBufferCount += skipSource(lastResetPositionUs - prerollUs)
        }
        super.render(positionUs, elapsedRealtimeUs)
    }

    private companion object { const val MIN_PREROLL_US = 120_000L }
}
