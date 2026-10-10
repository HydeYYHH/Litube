package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackIdentityTest {
    @Test fun namedTracksWithoutLocaleOrIdRemainSelectable() {
        val original = Format(audioOnly = true, audioTrackName = "English")
        val dubbed = original.copy(audioTrackName = "Spanish")
        val rows = AudioTrackIdentity.choices(listOf(original, original.copy(codec = "opus"), dubbed))
        assertEquals(listOf("name:English", "name:Spanish"), rows.map { it.key })
        assertTrue(AudioTrackIdentity.matches(dubbed, rows.last().key))
        assertFalse(AudioTrackIdentity.matches(original, rows.last().key))
        val dubs = listOf(original.copy(audioTrackType = "dubbed"), dubbed.copy(audioTrackType = "dubbed"))
        assertEquals(listOf("name:English|dubbed", "name:Spanish|dubbed"), AudioTrackIdentity.choices(dubs).map { it.key })
    }

    @Test fun originalHlsRenditionBeatsThePlaylistLanguageDefault() {
        val entries = listOf("ja" to "Japanese", "en" to "English (Original)")
        assertEquals("hls:en:English (Original)", AudioTrackIdentity.originalRenditionKey(entries))
        assertEquals("hls:en:English", AudioTrackIdentity.originalRenditionKey(
            listOf("ja" to "Japanese", "en" to "English"), listOf(audio("en", "original"))))
    }

    @Test fun ambiguousLanguageDoesNotMistakeDescriptionForOriginal() {
        assertNull(AudioTrackIdentity.originalRenditionKey(
            listOf("en" to "Main", "en" to "Description"), listOf(audio("en", "original"))))
        assertNull(AudioTrackIdentity.originalRenditionKey(listOf("en" to "English", "ja" to "Japanese")))
    }

    @Test fun originalTypeDoesNotRequireTheRedundantBooleanFlag() {
        val original = audio("en", "original").copy(audioTrackOriginal = false, bitrate = 96_000)
        val dub = audio("ja", "dubbed").copy(bitrate = 192_000)
        assertEquals(original, StreamSelection.selectAudio(listOf(dub, original)))
        assertEquals(dub, StreamSelection.selectAudio(listOf(dub, original), "ja"))
    }

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

    @Test
    fun menuMetadataCanBeLocalizedWithoutChangingTrackKeys() {
        val original = audio(locale = "en-US", type = "original", trackId = "en.0")
        val descriptive = audio(locale = "en-US", type = "descriptive", trackId = "en.d")
        val rows = AudioTrackIdentity.choices(listOf(original, descriptive))
        assertEquals(listOf("id:en.0", "id:en.d"), rows.map { it.key })
        assertEquals(listOf("en-US", "en-US"), rows.map { it.languageTag })
        assertEquals(listOf("original", "descriptive"), rows.map { it.trackType })
        assertNull(AudioTrackIdentity.choices(listOf(original)).single().trackType)
        val renditions = AudioTrackIdentity.renditionChoices(listOf("en" to "Director", "ja" to null))
        assertEquals("Director", renditions[0].label)
        assertNull(renditions[0].languageTag)
        assertEquals("ja", renditions[1].languageTag)
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
