package com.hhst.youtubelite.player

import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.player.datasource.AudioCatalogSupplement
import com.hhst.youtubelite.player.datasource.AudioTrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCatalogSupplementTest {
    private fun audio(locale: String? = null, name: String? = null, type: String? = null,
                      id: String? = null, original: Boolean = false) = Format(
        url = "https://media.example.test/${id ?: name ?: locale}",
        audioOnly = true, audioLocale = locale, audioTrackName = name,
        audioTrackType = type, audioTrackId = id, audioTrackOriginal = original,
    )

    @Test fun extraChoicesOnlyReturnsRowsTheMenuDoesNotShowYet() {
        val known = AudioTrackIdentity.choices(
            listOf(audio(locale = "ko", type = "original", original = true))).map { it.key }.toSet()
        val discovered = listOf(
            audio(locale = "ko", type = "original", original = true),
            audio(locale = "id", name = "Indonesian"),
            audio(locale = "en", name = "English US"),
        )
        val extra = AudioCatalogSupplement.extraChoices(known, discovered)
        assertEquals(2, extra.size)
        assertTrue(extra.all { it.label.isNotBlank() })
    }

    @Test fun extraChoicesIsEmptyWhenThePoolAddsNothing() {
        val known = AudioTrackIdentity.choices(
            listOf(audio(locale = "ko", type = "original", original = true))).map { it.key }.toSet()
        assertTrue(AudioCatalogSupplement.extraChoices(known,
            listOf(audio(locale = "ko", type = "original", original = true))).isEmpty())
    }

    @Test fun needsSupplementOnlyWhenTheCurrentPoolCannotSatisfyThePick() {
        val current = listOf(audio(locale = "ko", type = "original", original = true))
        val supplement = listOf(audio(locale = "id", name = "Indonesian"))
        val key = AudioTrackIdentity.choices(supplement).single().key
        assertTrue(AudioCatalogSupplement.needsSupplement(current, supplement, key))
        assertFalse(AudioCatalogSupplement.needsSupplement(supplement, supplement, key))
        assertFalse(AudioCatalogSupplement.needsSupplement(current, supplement, null))
        assertFalse(AudioCatalogSupplement.needsSupplement(current, null, key))
        // An original pick stays on the current source: the pool always has the default.
        val originalKey = AudioTrackIdentity.choices(current).single().key
        assertFalse(AudioCatalogSupplement.needsSupplement(current, supplement, originalKey))
    }
}
