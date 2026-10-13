package com.hhst.youtubelite.browser

import com.google.gson.JsonParser

enum class TransientKind(val code: String) {
    NONE("none"), MEDIA_MENU("media_menu"), SEARCH("search");

    companion object {
        fun parse(code: String?): TransientKind? = entries.firstOrNull { it.code == code }
    }
}

data class TransientUi(
    val kind: TransientKind = TransientKind.NONE,
    val origin: PageOrigin = PageOrigin.HOST,
    val viewportWidth: Double = 0.0,
    val regions: List<UiRegion> = emptyList(),
) {
    fun contains(x: Double, y: Double): Boolean = regions.any { it.contains(x, y) }

    companion object {
        fun fromPage(kind: TransientKind, origin: PageOrigin, json: String?): TransientUi {
            val empty = TransientUi(kind, origin)
            if (kind == TransientKind.NONE || json == null || json.length > 8192) return empty
            return runCatching {
                val value = JsonParser.parseString(json).asJsonObject
                val width = value.get("viewportWidth").asDouble
                require(width.isFinite() && width > 0)
                val regions = value.getAsJsonArray("regions").take(32).mapNotNull { entry ->
                    val rect = entry.asJsonArray
                    if (rect.size() != 4) return@mapNotNull null
                    val edges = rect.map { it.asDouble }
                    if (edges.any { !it.isFinite() } || edges[2] <= edges[0] || edges[3] <= edges[1]) return@mapNotNull null
                    UiRegion(edges[0], edges[1], edges[2], edges[3])
                }
                empty.copy(viewportWidth = width, regions = regions)
            }.getOrDefault(empty)
        }
    }
}

data class UiRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun contains(x: Double, y: Double) = x >= left && x < right && y >= top && y < bottom
}
