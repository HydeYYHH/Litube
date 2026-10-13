package com.hhst.youtubelite.browser

import com.google.gson.JsonParser
import com.hhst.youtubelite.extractor.VideoId

data class WatchAutoplayCandidate(val url: String) {
    companion object {
        fun parse(raw: String?, currentId: String): WatchAutoplayCandidate? = runCatching {
            var value = JsonParser.parseString(raw ?: return null)
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value = JsonParser.parseString(value.asString)
            val data = value.asJsonObject
            val enabled = data.get("enabled") ?: return null
            if (!enabled.isJsonPrimitive || !enabled.asJsonPrimitive.isBoolean || !enabled.asBoolean) return null
            if (data.get("videoId")?.asString != currentId) return null
            val url = data.get("url")?.takeUnless { it.isJsonNull }?.asString ?: return null
            val id = VideoId.parse(url) ?: return null
            if (!UrlPolicy.isAllowedUrl(url) || !PageKind.isPlayerSurface(PageKind.of(url)) || id == currentId) return null
            WatchAutoplayCandidate(url)
        }.getOrNull()
    }
}
