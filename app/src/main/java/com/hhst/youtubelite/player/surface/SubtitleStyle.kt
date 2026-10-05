package com.hhst.youtubelite.player.surface

/**
 * User-adjustable subtitle appearance.
 *
 * Encoded as a compact string for [core.JsonCache]
 * persistence. [decode] accepts both the current `v2;` payload and the
 * older 5-field enum format so a stored style survives this upgrade.
 */
data class SubtitleStyle(
    /** Font scale; 1f matches the original bodyMedium look. */
    val textScale: Float = 1f,
    /** 0 = top of the picture, 1 = bottom. */
    val vertical: Float = 1f,
    /** −1 = left, 0 = center, 1 = right. */
    val horizontal: Float = 0f,
    val background: Background = Background.SEMI,
    val bold: Boolean = false,
    /** Packed ARGB text color. */
    val colorArgb: Int = COLOR_WHITE,
) {
    enum class Background { NONE, SEMI, OPAQUE }

    /** Bias for [androidx.compose.ui.BiasAlignment]: −1 top, 1 bottom. */
    fun verticalBias(): Float = (vertical.coerceIn(0f, 1f) * 2f) - 1f

    fun clamped(): SubtitleStyle = copy(
        textScale = textScale.coerceIn(MIN_SCALE, MAX_SCALE),
        vertical = vertical.coerceIn(0f, 1f),
        horizontal = horizontal.coerceIn(-1f, 1f),
    )

    fun encode(): String =
        listOf(
            VERSION,
            formatDecimal(textScale.coerceIn(MIN_SCALE, MAX_SCALE)),
            formatDecimal(vertical.coerceIn(0f, 1f)),
            formatDecimal(horizontal.coerceIn(-1f, 1f)),
            background.name,
            bold.toString(),
            (colorArgb.toLong() and 0xFFFFFFFFL).toString(16).uppercase(),
        ).joinToString(SEPARATOR)

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3f
        const val COLOR_WHITE = -1 // 0xFFFFFFFF
        const val COLOR_YELLOW = -256 // 0xFFFFFF00
        private const val VERSION = "v2"
        private const val SEPARATOR = ";"

        /** Locale-independent `1.50` so [toFloatOrNull] can parse on comma-decimal devices. */
        private fun formatDecimal(value: Float): String =
            java.lang.String.format(java.util.Locale.US, "%.2f", value)

        fun decode(raw: String?): SubtitleStyle {
            if (raw.isNullOrBlank()) return SubtitleStyle()
            val parts = raw.split(SEPARATOR)
            return when {
                parts.size == 7 && parts[0] == VERSION -> decodeV2(parts)
                parts.size == 5 -> decodeLegacy(parts)
                else -> SubtitleStyle()
            }
        }

        private fun decodeV2(parts: List<String>): SubtitleStyle {
            val scale = parts[1].toFloatOrNull()?.coerceIn(MIN_SCALE, MAX_SCALE) ?: 1f
            val vertical = parts[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            val horizontal = parts[3].toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f
            val background = enumValueOrNull<Background>(parts[4]) ?: Background.SEMI
            val bold = parts[5].toBooleanStrictOrNull() == true
            val color = parseColorHex(parts[6]) ?: COLOR_WHITE
            return SubtitleStyle(scale, vertical, horizontal, background, bold, color)
        }

        /** `scale;TOP|MIDDLE|BOTTOM;BACKGROUND;bold;WHITE|YELLOW`. */
        private fun decodeLegacy(parts: List<String>): SubtitleStyle {
            val scale = parts[0].toFloatOrNull()?.coerceIn(MIN_SCALE, MAX_SCALE) ?: 1f
            val vertical = when (parts[1].uppercase()) {
                "TOP" -> 0f
                "MIDDLE" -> 0.5f
                "BOTTOM" -> 1f
                else -> 1f
            }
            val background = enumValueOrNull<Background>(parts[2]) ?: Background.SEMI
            val bold = parts[3].toBooleanStrictOrNull() == true
            val color = when (parts[4].uppercase()) {
                "YELLOW" -> COLOR_YELLOW
                "WHITE" -> COLOR_WHITE
                else -> COLOR_WHITE
            }
            return SubtitleStyle(scale, vertical, 0f, background, bold, color)
        }

        /** `#RGB`, `#RRGGBB`, or `#AARRGGBB` (with or without `#`). */
        fun parseColorHex(hex: String): Int? {
            val raw = hex.trim().removePrefix("#")
            val normalized = when (raw.length) {
                3 -> "FF" + raw.map { "$it$it" }.joinToString("")
                6 -> "FF$raw"
                8 -> raw
                else -> return null
            }
            return normalized.toLongOrNull(16)?.toInt()
        }

        /** User-facing `#RRGGBB` (alpha dropped when opaque). */
        fun formatColorHex(argb: Int): String {
            val rgb = argb and 0x00FFFFFF
            return "#%06X".format(rgb)
        }

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
