package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOnlyHlsTest {

    @Test fun withoutHlsAudioDropsRenditionsAndMuxedCodecs() {
        val format = androidx.media3.common.Format.Builder()
            .setId("v")
            .setCodecs("mp4a.40.2,avc1.64001f")
            .setSampleMimeType(MimeTypes.VIDEO_MP4)
            .setWidth(1280)
            .setHeight(720)
            .build()
        val variant = HlsMultivariantPlaylist.Variant(
            Uri.parse("https://example.com/v.m3u8"),
            format,
            null,
            "aud",
            null,
            null,
            null,
            null,
        )
        val rendition = HlsMultivariantPlaylist.Rendition(
            Uri.parse("https://example.com/a.m3u8"),
            androidx.media3.common.Format.Builder().setId("a").setCodecs("mp4a.40.2").build(),
            "aud",
            "Default",
            null,
        )
        val playlist = HlsMultivariantPlaylist(
            "https://example.com/master.m3u8",
            emptyList(),
            listOf(variant),
            emptyList(),
            listOf(rendition),
            emptyList(),
            emptyList(),
            null,
            emptyList(),
            true,
            emptyMap(),
            emptyList(),
        )
        val stripped = withoutHlsAudio(playlist) as HlsMultivariantPlaylist
        assertTrue(stripped.audios.isEmpty())
        assertNull(stripped.muxedAudioFormat)
        assertNull(stripped.variants[0].audioGroupId)
        assertEquals("avc1.64001f", stripped.variants[0].format.codecs)
        assertEquals(720, stripped.variants[0].format.height)
    }

    @Test fun declareAudioCodecsStampsRenditionsThatOmitCodecs() {
        val format = androidx.media3.common.Format.Builder()
            .setId("v")
            .setCodecs("mp4a.40.5,avc1.4d4015")
            .setSampleMimeType(MimeTypes.VIDEO_MP4)
            .build()
        val variant = HlsMultivariantPlaylist.Variant(
            Uri.parse("https://example.com/v.m3u8"),
            format,
            null,
            "233",
            null,
            null,
            null,
            null,
        )
        val english = androidx.media3.common.Format.Builder()
            .setId("en")
            .setLabel("English (US) original")
            .setLanguage("en-US")
            .build()
        val japanese = androidx.media3.common.Format.Builder()
            .setId("ja")
            .setLabel("Japanese")
            .setLanguage("ja")
            .setCodecs("mp4a.40.2")
            .build()
        val playlist = HlsMultivariantPlaylist(
            "https://example.com/master.m3u8",
            emptyList(),
            listOf(variant),
            emptyList(),
            listOf(
                HlsMultivariantPlaylist.Rendition(Uri.parse("https://example.com/en.m3u8"), english, "233", "English (US) original", null),
                HlsMultivariantPlaylist.Rendition(Uri.parse("https://example.com/ja.m3u8"), japanese, "233", "Japanese", null),
            ),
            emptyList(),
            emptyList(),
            null,
            emptyList(),
            true,
            emptyMap(),
            emptyList(),
        )
        val declared = declareAudioCodecs(playlist) as HlsMultivariantPlaylist
        assertEquals(2, declared.audios.size)
        assertEquals("mp4a.40.5", declared.audios[0].format.codecs)
        assertEquals(MimeTypes.AUDIO_AAC, declared.audios[0].format.sampleMimeType)
        assertEquals(2, declared.audios[0].format.channelCount)
        assertEquals(48_000, declared.audios[0].format.sampleRate)
        assertEquals("English (US) original", declared.audios[0].name)
        assertEquals("mp4a.40.2", declared.audios[1].format.codecs)
        assertEquals(2, declared.audios[1].format.channelCount)
    }
}
