package com.hhst.youtubelite.navigation

import java.net.URI
import java.net.URLDecoder

sealed interface ContentLink {
    data class Video(val id: String, val seconds: Long? = null) : ContentLink
    data class Short(val id: String?) : ContentLink
    data class Playlist(val id: String) : ContentLink
    data class Channel(val id: String) : ContentLink
    data class Post(val id: String) : ContentLink
    data class Search(val query: String) : ContentLink
    data class Feed(val name: String) : ContentLink

    companion object {
        internal fun timestamp(value: String?): Long? {
            if (value == null) return null
            value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
            val match = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matchEntire(value) ?: return null
            if (match.groupValues.drop(1).all(String::isEmpty)) return null
            return runCatching {
                val parts = match.groupValues.drop(1).map { if (it.isEmpty()) 0L else it.toLong() }
                Math.addExact(Math.addExact(Math.multiplyExact(parts[0], 3600L), Math.multiplyExact(parts[1], 60L)), parts[2])
            }.getOrNull()
        }

        fun parse(value: String): ContentLink? = runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
                host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) return null
            val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
            }.toMap()
            fun video(id: String?) = id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            fun playlist(id: String?) = id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,200}")) }
            val first = parts.firstOrNull()
            when {
                host == "youtu.be" -> video(first)?.let { Video(it, timestamp(query["t"] ?: query["start"])) }
                first in setOf("watch", "live", "embed") -> video(if (first == "watch") query["v"] else parts.getOrNull(1))?.let { Video(it, timestamp(query["t"] ?: query["start"])) }
                    ?: playlist(query["list"])?.let(::Playlist)
                first == "shorts" -> if (parts.size == 1) Short(null) else video(parts[1])?.let(::Short)
                first == "playlist" -> playlist(query["list"])?.let(::Playlist)
                first == "channel" -> parts.getOrNull(1)?.takeIf { it.matches(Regex("UC[A-Za-z0-9_-]{22}")) }?.let(::Channel)
                first?.startsWith("@") == true -> first.takeIf { it.length in 2..100 }?.let(::Channel)
                first == "post" -> parts.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,200}")) }?.let(::Post)
                first == "results" -> query["search_query"]?.takeIf(String::isNotBlank)?.let(::Search)
                first == "feed" && parts.getOrNull(1) in setOf("subscriptions", "history", "playlists", "you", "library") -> Feed(parts[1])
                first == null -> Feed("home")
                else -> null
            }
        }.getOrNull()
    }
}
