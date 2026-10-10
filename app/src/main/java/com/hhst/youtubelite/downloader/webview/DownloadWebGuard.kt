package com.hhst.youtubelite.downloader.webview

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.BatchSource
import com.hhst.youtubelite.downloader.core.DownloadLimits
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.core.DownloadSnapshotGuard
import com.hhst.youtubelite.downloader.core.SnapshotReject
import com.hhst.youtubelite.extractor.VideoId
import java.net.URI
import java.util.Locale

data class DownloadWebMessage(
    val type: String,
    val pageGeneration: Long,
    val tabId: Long,
    val videoId: String? = null,
    val title: String = "",
    val author: String? = null,
    val name: String = "",
    val items: List<DownloadRequest> = emptyList(),
)

sealed class DownloadWebDecision {
    data class OpenSingle(
        val videoId: String,
        val title: String,
        val author: String?,
        val thumbnailUrl: String?,
    ) : DownloadWebDecision()

    data class OpenBatch(val snapshot: BatchSnapshot) : DownloadWebDecision()

    data class Status(val videoId: String) : DownloadWebDecision()

    data object OpenManager : DownloadWebDecision()

    data class Error(val code: String, val message: String) : DownloadWebDecision()

    data object Ignore : DownloadWebDecision()
}

/**
 * Web-bridge security: HTTPS media hosts, main frame, generation match,
 * snapshot limits with no silent truncate, and no mutation commands.
 */
object DownloadWebGuard {
    const val TYPE_OPEN_CONFIRM = "openConfirm"
    const val TYPE_OPEN_BATCH = "openBatch"
    const val TYPE_REQUEST_STATUS = "requestStatus"
    const val TYPE_OPEN_MANAGER = "openManager"

    private val gson = Gson()
    private val mutationTypes = setOf(
        "enqueue", "create", "cancel", "delete", "pause", "resume", "remove", "redownload",
    )

    fun isAllowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtu.be")
    }

    fun unwrapJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return runCatching { gson.fromJson(trimmed, String::class.java) }.getOrNull()
                ?: trimmed
        }
        return trimmed
    }

    fun decide(
        origin: String?,
        isMainFrame: Boolean,
        currentTabId: Long,
        currentPageGeneration: Long,
        rawJson: String,
        allowBatch: Boolean = true,
        payloadBytes: Int = rawJson.toByteArray(Charsets.UTF_8).size,
    ): DownloadWebDecision {
        if (!isMainFrame) return DownloadWebDecision.Error("iframe", "iframe requests are rejected")
        if (!isAllowedOrigin(origin)) return DownloadWebDecision.Ignore
        if (payloadBytes > DownloadLimits.MAX_SNAPSHOT_BYTES) {
            return DownloadWebDecision.Error(
                "too_large",
                SnapshotReject.TooLarge(payloadBytes).message,
            )
        }
        val message = parse(rawJson) ?: return DownloadWebDecision.Ignore
        if (message.tabId != currentTabId || message.pageGeneration != currentPageGeneration) {
            return DownloadWebDecision.Ignore
        }
        val type = message.type
        if (type in mutationTypes) return DownloadWebDecision.Ignore
        return when (type) {
            TYPE_OPEN_CONFIRM -> {
                val id = VideoId.parse(message.videoId) ?: return DownloadWebDecision.Ignore
                DownloadWebDecision.OpenSingle(
                    videoId = id,
                    title = message.title,
                    author = message.author,
                    thumbnailUrl = VideoId.thumbnailUrl(id),
                )
            }
            TYPE_OPEN_BATCH -> {
                if (!allowBatch) {
                    val first = message.items.firstOrNull()
                    val id = first?.videoId ?: return DownloadWebDecision.Ignore
                    return DownloadWebDecision.OpenSingle(
                        videoId = id,
                        title = first.title,
                        author = first.author,
                        thumbnailUrl = VideoId.thumbnailUrl(id),
                    )
                }
                if (message.items.size > DownloadLimits.MAX_SNAPSHOT_ITEMS) {
                    return DownloadWebDecision.Error(
                        "too_many_items",
                        SnapshotReject.TooManyItems(message.items.size).message,
                    )
                }
                val snapshot = BatchSnapshot(
                    source = BatchSource.PLAYLIST,
                    name = message.name.ifBlank { "Playlist" },
                    items = message.items,
                )
                val reject = DownloadSnapshotGuard.validate(snapshot, payloadBytes)
                if (reject != null) {
                    val code = if (reject is SnapshotReject.TooManyItems) "too_many_items" else "too_large"
                    DownloadWebDecision.Error(code, reject.message)
                } else {
                    DownloadWebDecision.OpenBatch(snapshot)
                }
            }
            TYPE_REQUEST_STATUS -> {
                val id = VideoId.parse(message.videoId) ?: return DownloadWebDecision.Ignore
                DownloadWebDecision.Status(id)
            }
            TYPE_OPEN_MANAGER -> DownloadWebDecision.OpenManager
            else -> DownloadWebDecision.Ignore
        }
    }

    /**
     * Native-initiated playlist collect: generation is not required because
     * the host already confirmed the loaded page. Item/size limits are
     * enforced by the confirm sheet with no silent truncate.
     */
    fun snapshotFromCollect(rawJson: String): BatchSnapshot? {
        val message = parse(rawJson) ?: return null
        if (message.items.isEmpty()) return null
        return BatchSnapshot(
            source = BatchSource.PLAYLIST,
            name = message.name.ifBlank { "Playlist" },
            items = message.items,
        )
    }

    fun parse(rawJson: String): DownloadWebMessage? {
        val root = runCatching {
            JsonParser.parseString(rawJson).asJsonObject
        }.getOrNull() ?: return null
        val type = root.string("type") ?: return null
        // Type-check before reading: a non-array `items` must be rejected, not
        // ClassCastException'd out of the guard.
        val itemsField = root.get("items")
        val items = when {
            itemsField == null || itemsField.isJsonNull -> emptyList()
            itemsField.isJsonArray -> itemsField.asJsonArray.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                item(el.asJsonObject)
            }
            else -> return null
        }
        return DownloadWebMessage(
            type = type,
            pageGeneration = root.long("pageGeneration"),
            tabId = root.long("tabId"),
            videoId = root.string("videoId"),
            title = root.string("title").orEmpty(),
            author = root.string("author"),
            name = root.string("name").orEmpty(),
            items = items,
        )
    }

    private fun item(obj: JsonObject): DownloadRequest? {
        val id = VideoId.parse(obj.string("videoId"))
            ?: VideoId.parse(obj.string("url"))
            ?: return null
        return DownloadRequest(
            videoId = id,
            title = obj.string("title").orEmpty(),
            author = obj.string("author"),
            thumbnailUrl = VideoId.thumbnailUrl(id),
        )
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()

    private fun JsonObject.long(key: String): Long =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull() ?: 0L
}
