package com.hhst.youtubelite.player.datasource

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@UnstableApi
class PlaybackStartupTest {
    private val ladder = listOf(144 to 110_000, 240 to 295_000, 360 to 680_000, 480 to 1_291_000, 720 to 2_700_000, 1080 to 4_900_000)
        .map { (height, bitrate) ->
            Format.Builder().setId("$height").setCodecs("avc1.4d401e,mp4a.40.2")
                .setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(height).setWidth(height * 16 / 9)
                .setAverageBitrate(bitrate).setPeakBitrate(bitrate).build()
        }

    private class FixedMeter(private val estimate: Long) : BandwidthMeter {
        override fun getBitrateEstimate() = estimate
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) = Unit
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
    }

    private fun initialPick(factory: StartCappedSelectionFactory, estimate: Long, period: Any = "p"): Format {
        val group = TrackGroup(*ladder.toTypedArray())
        val definition = ExoTrackSelection.Definition(group, *IntArray(ladder.size) { it })
        val selection = factory.createTrackSelections(arrayOf(definition), FixedMeter(estimate),
            MediaSource.MediaPeriodId(period, 0), Timeline.EMPTY)[0]!!
        selection.enable()
        selection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), arrayOf<MediaChunkIterator>())
        return selection.selectedFormat
    }

    @Test fun startFormatStopsAtStartHeightOnFastNetworks() {
        assertEquals(480, PlaybackStartup.startFormat(ladder, 20_000_000)!!.height)
    }

    @Test fun startFormatFollowsTheEstimateBelowTheCap() {
        assertEquals(240, PlaybackStartup.startFormat(ladder, 600_000)!!.height)
        assertEquals(144, PlaybackStartup.startFormat(ladder, 10_000)!!.height)
    }

    @Test fun noCapWhenNothingExceedsStartHeight() {
        assertNull(PlaybackStartup.capEstimate(ladder.filter { it.height <= 480 }))
    }

    @Test fun selectionPicksTheVariantThePlaylistOrderPredicted() {
        for (estimate in listOf(10_000L, 600_000L, 1_500_000L, 20_000_000L)) {
            val predicted = PlaybackStartup.startFormat(ladder, estimate)!!
            assertEquals("estimate $estimate", predicted.height,
                initialPick(StartCappedSelectionFactory(clock = { 0L }), estimate, "p$estimate").height)
        }
    }

    @Test fun laterSelectionsOfThePeriodKeepTheRealEstimate() {
        val factory = StartCappedSelectionFactory(clock = { 0L })
        assertEquals(480, initialPick(factory, 20_000_000).height)
        assertEquals(1080, initialPick(factory, 20_000_000).height)
    }

    @Test fun capLapsesAfterTheStartWindow() {
        var now = 0L
        val factory = StartCappedSelectionFactory(clock = { now })
        val group = TrackGroup(*ladder.toTypedArray())
        val selection = factory.createTrackSelections(arrayOf(ExoTrackSelection.Definition(group, *IntArray(ladder.size) { it })),
            FixedMeter(20_000_000), MediaSource.MediaPeriodId("window", 0), Timeline.EMPTY)[0]!!
        selection.enable()
        selection.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(), arrayOf<MediaChunkIterator>())
        assertEquals(480, selection.selectedFormat.height)
        now = PlaybackStartup.START_WINDOW_MS
        // Upward switches still need Media3's buffered-duration margin; past it the real estimate applies.
        selection.updateSelectedTrack(0, 60_000_000, C.TIME_UNSET, emptyList(), arrayOf<MediaChunkIterator>())
        assertEquals(1080, selection.selectedFormat.height)
    }

    @Test fun startVariantMovesFirstAndKeepsTheRestInOrder() {
        val variants = ladder.map {
            HlsMultivariantPlaylist.Variant(android.net.Uri.parse("https://example.com/${it.height}.m3u8"), it, null, null, null, null, null, null)
        }
        val playlist = HlsMultivariantPlaylist("https://example.com/master.m3u8", emptyList(), variants,
            emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(), true, emptyMap(), emptyList())
        val ordered = startVariantFirst(playlist, 20_000_000) as HlsMultivariantPlaylist
        assertEquals(listOf(480, 144, 240, 360, 720, 1080), ordered.variants.map { it.format.height })
        val manual = startVariantFirst(playlist, 10_000, "1080p") as HlsMultivariantPlaylist
        assertEquals(1080, manual.variants.first().format.height)
        assertEquals(variants.size, manual.variants.size)
        val already = HlsMultivariantPlaylist("https://example.com/master.m3u8", emptyList(), listOf(variants[3]) + variants.filterIndexed { i, _ -> i != 3 },
            emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(), true, emptyMap(), emptyList())
        assertSame(already, startVariantFirst(already, 20_000_000))
    }
}
