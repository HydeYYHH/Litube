@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import org.junit.Assert.*
import org.junit.Test

class HlsStartupAndroidTest {
    private val formats = listOf(144, 240, 360, 480, 720, 1080).map { format(it, false) } +
        listOf(144, 240, 480, 720).map { format(it, true) }

    private fun format(height: Int, vp9: Boolean) = Format.Builder()
        .setId("${if (vp9) "vp9" else "avc"}-$height")
        .setCodecs(if (vp9) "vp09.00.10.08" else "avc1.4d401e")
        .setSampleMimeType(if (vp9) MimeTypes.VIDEO_VP9 else MimeTypes.VIDEO_H264)
        .setHeight(height).setWidth(height * 16 / 9).setFrameRate(30f)
        .setAverageBitrate(height * 2_000).setPeakBitrate(height * 2_000).build()

    private fun support(format: Format) = RendererCapabilities.create(C.FORMAT_HANDLED,
        RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        if (format.sampleMimeType == MimeTypes.VIDEO_VP9) RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED
            else RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED,
        RendererCapabilities.DECODER_SUPPORT_PRIMARY)

    @Test fun decoderCapabilityBeatsTheLargerCodecGroupAndOrdersTheSameStartTrack() {
        val parameters = TrackSelectionParameters.Builder().setMaxVideoSize(1280, 720).build()
        val pick = PlaybackStartup.selectFormat(formats, 20_000_000, parameters, ::support)!!
        assertEquals(MimeTypes.VIDEO_VP9, pick.sampleMimeType)
        assertEquals(480, pick.height)
        val variants = formats.map { HlsMultivariantPlaylist.Variant(Uri.parse("https://example.com/${it.id}.m3u8"),
            it, null, null, null, null, null, null) }
        val master = HlsMultivariantPlaylist("https://example.com/master.m3u8", emptyList(), variants,
            emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(), true, emptyMap(), emptyList())
        val ordered = startVariantFirst(master, 20_000_000, select = { pool, estimate ->
            PlaybackStartup.selectFormat(pool, estimate, parameters, ::support)
        }) as HlsMultivariantPlaylist
        assertSame(pick, ordered.variants.first().format)
        assertEquals(variants.size, ordered.variants.size)
    }

    @Test fun manualHeightAndUnsupportedCodecsRemainRespected() {
        val pinned = TrackSelectionParameters.Builder().setMinVideoSize(0, 720).setMaxVideoSize(Int.MAX_VALUE, 720).build()
        assertEquals(720, PlaybackStartup.selectFormat(formats, 20_000_000, pinned, ::support)!!.height)
        val pick = PlaybackStartup.selectFormat(formats, 20_000_000, pinned) { format ->
            if (format.sampleMimeType == MimeTypes.VIDEO_VP9) RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                else support(format)
        }!!
        assertEquals(MimeTypes.VIDEO_H264, pick.sampleMimeType)
        assertEquals(720, pick.height)
    }
}
