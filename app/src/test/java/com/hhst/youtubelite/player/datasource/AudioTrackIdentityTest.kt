package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackIdentityTest {

    @Test
    fun keyPrefersTrackId_thenLocaleAndType() {
        val withId = audio(locale = "en-US", trackId = "en.0", type = "original")
        assertEquals("id:en.0", AudioTrackIdentity.key(withId))
        val noId = audio(locale = "es-MX", type = "dubbed")
        assertEquals("loc:es-MX|dubbed", AudioTrackIdentity.key(noId))
    }

    @Test
    fun matchesLegacyLanguageAndRegionTag() {
        val us = audio(locale = "en-US", type = "original")
        assertTrue(AudioTrackIdentity.matches(us, "en"))
        assertTrue(AudioTrackIdentity.matches(us, "en-US"))
        assertTrue(AudioTrackIdentity.matches(us, "loc:en-US|original"))
        assertFalse(AudioTrackIdentity.matches(us, "es"))
        assertFalse(AudioTrackIdentity.matches(us, "id:other"))
    }

    @Test
    fun renditionChoicesKeepDistinctPlaylistTracks() {
        val rows = AudioTrackIdentity.renditionChoices(
            listOf(
                "en-US" to "English (US) original",
                "en-US" to "English (US) original",
                "ja" to "Japanese",
                null to null,
            ),
        )
        assertEquals(listOf("hls:en-US:English (US) original", "hls:ja:Japanese"), rows.map { it.key })
        assertEquals(listOf("English (US) original", "Japanese"), rows.map { it.label })
    }

    @Test
    fun oneRenditionStaysOnTheDefaultRow() {
        assertTrue(
            AudioTrackIdentity.renditionChoices(listOf("en-US" to "English")).isEmpty(),
        )
    }

    @Test
    fun choicesDisambiguateSameLanguageDifferentType() {
        val original = audio(locale = "en", type = "original", trackId = "en.0")
        val desc = audio(locale = "en", type = "descriptive", trackId = "en.d")
        val rows = AudioTrackIdentity.choices(listOf(original, desc))
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.key == "id:en.0" && it.label.contains("Original") })
        assertTrue(rows.any { it.key == "id:en.d" && it.label.contains("Descriptive") })
    }

    private fun audio(
        locale: String,
        type: String,
        trackId: String? = null,
    ) = Format(
        url = "https://example.invalid/$locale",
        audioOnly = true,
        audioLocale = locale,
        audioTrackType = type,
        audioTrackOriginal = type == "original",
        audioTrackId = trackId,
        bitrate = 128_000,
    )
}
