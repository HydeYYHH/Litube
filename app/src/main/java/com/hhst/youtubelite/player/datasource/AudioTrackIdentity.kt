package com.hhst.youtubelite.player.datasource

import com.hhst.youtubelite.extractor.Format
import java.util.Locale

/** Menu row for one audio track: stable key + human label. */
data class AudioTrackChoice(
    val key: String,
    val label: String,
    val languageTag: String? = null,
    val trackType: String? = null,
    val trackName: String? = null,
)

/**
 * Concrete audio identity: track id when present, otherwise
 * locale tag + type, then display name. Old language-only prefs (`en`) still match.
 */
object AudioTrackIdentity {
    fun isOriginal(format: Format): Boolean = format.audioTrackOriginal ||
        format.audioTrackType.equals("original", ignoreCase = true)

    /** HLS may declare Original only in NAME, rather than its DEFAULT flag. */
    fun originalRenditionKey(entries: List<Pair<String?, String?>>, formats: List<Format> = emptyList()): String? {
        entries.firstOrNull { (_, label) ->
            label?.contains(Regex("\\boriginal\\b|原始|原聲|原声|オリジナル|오리지널", RegexOption.IGNORE_CASE)) == true
        }?.let { return renditionKey(it.first, it.second) }
        val originals = formats.filter(::isOriginal)
        entries.firstOrNull { (_, label) ->
            !label.isNullOrBlank() && originals.any { it.audioTrackName.equals(label, ignoreCase = true) }
        }?.let { return renditionKey(it.first, it.second) }
        // A language alone cannot distinguish an original from audio description.
        val candidates = entries.filter { (language, _) ->
            language != null && originals.any { it.audioLocale?.let { locale -> localeMatches(locale, language) } == true }
        }.distinct()
        return candidates.singleOrNull()?.let { renditionKey(it.first, it.second) }
    }

    fun key(format: Format): String {
        format.audioTrackId?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        val locale = format.audioLocale.orEmpty()
        val type = format.audioTrackType.orEmpty()
        if (locale.isNotBlank()) return "loc:$locale|$type"
        // Some extraction clients supply a display name without an id/locale.
        // Those are still distinct, selectable tracks, not anonymous Default.
        format.audioTrackName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return if (type.isBlank()) "name:$it" else "name:$it|$type"
        }
        if (type.isNotBlank()) return "loc:|$type"
        return ""
    }

    fun matches(format: Format, trackKey: String): Boolean {
        if (trackKey.isBlank()) return false
        if (key(format) == trackKey) return true
        val locale = format.audioLocale ?: return false
        if (trackKey.startsWith("id:") || trackKey.startsWith("name:")) return false
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
            AudioTrackChoice(
                k, label(format, showType), format.audioLocale,
                format.audioTrackType.takeIf { showType }, format.audioTrackName,
            )
        }.filter { it.key.isNotBlank() }
    }

    /** Stable key for one HLS rendition. Blank when the playlist gave it no identity. */
    fun renditionKey(language: String?, label: String?): String {
        val lang = language?.trim().orEmpty()
        val name = label?.trim().orEmpty()
        if (lang.isEmpty() && name.isEmpty()) return ""
        return "hls:$lang:$name"
    }

    /**
     * Menu rows for playlist audio renditions. A single rendition stays off the
     * list: the Default row already means that track.
     */
    fun renditionChoices(entries: List<Pair<String?, String?>>): List<AudioTrackChoice> {
        val unique = LinkedHashMap<String, AudioTrackChoice>()
        for ((language, label) in entries) {
            val key = renditionKey(language, label)
            if (key.isBlank() || unique.containsKey(key)) continue
            val text = label?.trim()?.takeIf { it.isNotEmpty() }
                ?: language?.trim()?.takeIf { it.isNotEmpty() }?.let(::displayLanguage)
                ?: continue
            unique[key] = AudioTrackChoice(key, text, languageTag = language.takeIf { label.isNullOrBlank() })
        }
        return if (unique.size < 2) emptyList() else unique.values.toList()
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
