package com.hhst.youtubelite.downloader.share

import com.hhst.youtubelite.browser.PageKind
import com.hhst.youtubelite.browser.UrlPolicy
import com.hhst.youtubelite.extractor.VideoId
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed class DownloadShareTarget {
    abstract val dedupeKey: String
    abstract val url: String

    data class Video(
        val videoId: String,
        override val url: String,
        val title: String? = null,
    ) : DownloadShareTarget() {
        override val dedupeKey: String = "v:$videoId"
    }

    data class Playlist(
        override val url: String,
        val listId: String?,
    ) : DownloadShareTarget() {
        override val dedupeKey: String = "p:${listId ?: url}"
    }
}

/** Parses share / open-with text into a download target. Does not enqueue. */
object DownloadShareParser {
    const val EXTRA_PENDING_PLAYLIST = "download_pending_playlist"
    const val EXTRA_CONSUMED = "download_share_consumed"

    private val URL_RE = Regex("""https?://\S+[^\s.,;:!?)]""")
    private val LIST_RE = Regex("""[?&]list=([^&]+)""")

    fun extractUrl(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        return URL_RE.findAll(candidate)
            .map { it.value }
            .firstOrNull { UrlPolicy.isAllowedUrl(it) && PageKind.of(it) != "unknown" }
            ?: candidate.trim().takeIf { UrlPolicy.isAllowedUrl(it) && PageKind.of(it) != "unknown" }
    }

    fun playlistId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val raw = LIST_RE.find(url.substringBefore('#'))?.groupValues?.getOrNull(1) ?: return null
        return raw.takeIf { it.isNotBlank() }
    }

    fun isPlaylistOnly(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (VideoId.parse(url) != null) return false
        if (!UrlPolicy.isAllowedUrl(url)) return false
        if (playlistId(url) != null) return true
        val kind = PageKind.of(url)
        return kind == "playlist" || kind.startsWith("playlist/")
    }

    fun samePlaylist(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val idA = playlistId(a)
        val idB = playlistId(b)
        if (idA != null && idA == idB) return true
        return a.substringBefore('#') == b.substringBefore('#')
    }

    fun parse(action: String?, extraText: String?, dataString: String?): DownloadShareTarget? {
        val candidate = when (action) {
            "android.intent.action.SEND" -> extraText
            "android.intent.action.VIEW" -> dataString ?: extraText
            else -> extraText ?: dataString
        } ?: return null
        val url = extractUrl(candidate) ?: return null
        val videoId = VideoId.parse(url)
        if (videoId != null) return DownloadShareTarget.Video(videoId, url)
        if (isPlaylistOnly(url)) return DownloadShareTarget.Playlist(url, playlistId(url))
        return null
    }
}

/**
 * Same share delivered twice (onCreate + onNewIntent, or a double tap) must
 * not open two confirm sheets. A later share of the same URL still works
 * after [WINDOW_MS].
 */
object DownloadShareOnce {
    const val WINDOW_MS = 3_000L

    private val lastKey = AtomicReference<String?>(null)
    private val lastAt = AtomicLong(0L)

    fun consume(key: String, now: Long): Boolean {
        val previous = lastKey.get()
        val elapsed = now - lastAt.get()
        if (previous == key && elapsed in 0 until WINDOW_MS) return false
        lastKey.set(key)
        lastAt.set(now)
        return true
    }

    fun reset() {
        lastKey.set(null)
        lastAt.set(0L)
    }
}
