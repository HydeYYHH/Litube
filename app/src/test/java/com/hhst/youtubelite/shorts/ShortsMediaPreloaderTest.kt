@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.shorts

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import com.hhst.youtubelite.shorts.data.ShortsMediaPreloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortsMediaPreloaderTest {

    private fun variant(height: Int, bitrate: Int) = HlsMultivariantPlaylist.Variant(
        Uri.parse("https://hls/$height.m3u8"),
        Format.Builder().setHeight(height).setAverageBitrate(bitrate).build(),
        null, null, null, null, null, null)

    @Test
    fun hlsVariantPickPrefersTheLargestRenditionUnderTheStartupCap() {
        val variants = listOf(variant(2160, 8_000_000), variant(1080, 4_000_000), variant(720, 2_000_000))
        val picked = ShortsMediaPreloader.pickHlsVariant(variants, 1080)!!
        assertEquals(1080, picked.format.height)
    }

    @Test
    fun hlsVariantPickFallsBackToTheFullPoolWhenEveryRenditionExceedsTheCap() {
        val variants = listOf(variant(2160, 8_000_000), variant(1440, 6_000_000))
        val picked = ShortsMediaPreloader.pickHlsVariant(variants, 1080)!!
        assertEquals(2160, picked.format.height)
    }

    @Test
    fun hlsVariantPickWithoutVariantsStaysNull() {
        assertNull(ShortsMediaPreloader.pickHlsVariant(emptyList(), 1080))
    }

    @Test fun playbackSelectionOutranksTheLargestRendition() {
        val variants = listOf(variant(480, 700_000), variant(720, 2_000_000))
        val picked = ShortsMediaPreloader.pickHlsVariant(variants, 720) { formats -> formats.first() }
        assertEquals(variants.first(), picked)
    }
}
