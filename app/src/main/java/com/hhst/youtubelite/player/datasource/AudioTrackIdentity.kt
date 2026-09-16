package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import java.util.Locale

/** Menu row for one audio track: stable key + human label. */
data class AudioTrackChoice(val key: String, val label: String)

/**
 * Concrete audio identity: YouTube track id when present, otherwise
 * locale tag + type. Old language-only prefs (`en`) still match.
 */
object AudioTrackIdentity {
    fun key(format: Format): String {
        format.audioTrackId?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        val locale = format.audioLocale.orEmpty()
        val type = format.audioTrackType.orEmpty()
        if (locale.isNotBlank() || type.isNotBlank()) return "loc:$locale|$type"
        return ""
    }

    fun matches(format: Format, trackKey: String): Boolean {
        if (trackKey.isBlank()) return false
        if (key(format) == trackKey) return true
        val locale = format.audioLocale ?: return false
        if (trackKey.startsWith("id:")) return false
        if (trackKey.startsWith("loc:")) {
            val rest = trackKey.removePrefix("loc:")
            val sep = rest.indexOf('|')
            val wantLoc = if (sep >= 0) rest.substring(0, sep) else rest
            val wantType = if (sep >= 0) rest.substring(sep + 1) else ""
            if (wantLoc.isNotBlank() && !localeMatches(locale, wantLoc)) return false
            if (wantType.isNotBlank() &&
                !format.audioTrackType.equals(wantType, ignoreCase = true)
            ) {
                return false
            }
            return true
        }
        // Legacy remembered value: ISO language, optionally with region.
        return localeMatches(locale, trackKey)
    }

    fun choices(formats: List<Format>): List<AudioTrackChoice> {
        val unique = formats.filter { it.audioOnly }.distinctBy { key(it).ifBlank { it.url } }
        val localeCounts = unique.groupingBy { languageOf(it.audioLocale) }.eachCount()
        return unique.map { format ->
            val k = key(format).ifBlank { format.audioLocale.orEmpty() }
            val showType = (localeCounts[languageOf(format.audioLocale)] ?: 0) > 1 ||
                (!format.audioTrackType.isNullOrBlank() && format.audioTrackType != "original")
            AudioTrackChoice(k, label(format, showType))
        }.filter { it.key.isNotBlank() }
    }

    fun label(format: Format, showType: Boolean): String {
        val lang = format.audioLocale?.let(::displayLanguage)
            ?: format.audioTrackName
            ?: "Audio"
        if (!showType) return lang
        val type = typeLabel(format.audioTrackType) ?: return lang
        return "$lang · $type"
    }

    fun displayLanguage(tag: String): String {
        val name = Locale.forLanguageTag(tag.replace('_', '-'))
            .getDisplayName(Locale.getDefault())
            .trim()
        return name.ifBlank { tag }
    }

    private fun typeLabel(type: String?): String? = when (type?.lowercase()) {
        "dubbed" -> "Dubbed"
        "descriptive" -> "Descriptive"
        "secondary" -> "Secondary"
        "original" -> "Original"
        else -> null
    }

    private fun languageOf(tag: String?): String =
        tag?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()

    private fun localeMatches(actual: String, stored: String): Boolean {
        if (actual.equals(stored, ignoreCase = true)) return true
        return languageOf(actual) == languageOf(stored) && languageOf(stored).isNotEmpty()
    }
}
