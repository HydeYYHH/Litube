package com.hhst.youtubelite.downloader.io

/**
 * Display names for Downloads/Litube. Keeps normal Unicode; strips OS-illegal
 * characters, caps length, and disambiguates collisions.
 */
object DownloadFileNames {
    private const val MAX_BASE = 180
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun sanitize(raw: String, extension: String, existing: Set<String> = emptySet()): String {
        val ext = extension.trim().trimStart('.').lowercase()
        val extSuffix = if (ext.isEmpty()) "" else ".$ext"
        var base = raw.substringBeforeLast('.', raw)
            .replace(ILLEGAL, "_")
            .trim()
            .trim('.')
        if (base.isEmpty() || base.all { it == '_' || it == '.' || it == ' ' }) {
            base = "download"
        }
        if (base.length > MAX_BASE) base = base.take(MAX_BASE).trimEnd('.', ' ')
        val candidate = base + extSuffix
        if (candidate.lowercase() !in existing.map { it.lowercase() }.toSet()) return candidate
        var n = 1
        while (n < 10_000) {
            val numbered = "$base ($n)$extSuffix"
            if (numbered.lowercase() !in existing.map { it.lowercase() }.toSet()) return numbered
            n++
        }
        return "$base (${System.nanoTime()})$extSuffix"
    }

    fun extensionOf(name: String, fallback: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.ifBlank { fallback }
    }
}
