package com.hhst.youtubelite.comments.domain

import java.net.URI

sealed interface CommentLink {
    data class Video(val id: String, val seconds: Long? = null) : CommentLink
    data class Channel(val id: String) : CommentLink
    data class External(val url: String) : CommentLink
}

data class CommentTextRun(
    val text: String,
    val link: CommentLink? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
)

private val textLink = Regex("""https?://[^\s<>"']+|(?<![\w:])\d{1,3}:\d{2}(?::\d{2})?(?![\w:])""", RegexOption.IGNORE_CASE)

internal fun safeCommentUrl(value: String): String? = runCatching {
    val uri = URI(value)
    value.takeIf { uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }
}.getOrNull()

internal fun commentTimestamp(value: String): Long? {
    val parts = value.split(':').map { it.toLongOrNull() ?: return null }
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]).takeIf { parts[0] >= 0 && parts[1] in 0..59 }
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).takeIf { parts[0] >= 0 && parts[1] in 0..59 && parts[2] in 0..59 }
        else -> null
    }
}

fun commentTextRuns(body: String, runs: List<CommentTextRun>, videoId: String?): List<CommentTextRun> {
    val source = runs.takeIf { it.isNotEmpty() && it.joinToString("") { run -> run.text } == body }
        ?: listOf(CommentTextRun(body))
    return source.flatMap { run ->
        if (run.link != null) return@flatMap listOf(run)
        buildList {
            var end = 0
            for (match in textLink.findAll(run.text)) {
                if (match.range.first > end) add(run.copy(text = run.text.substring(end, match.range.first)))
                val value = if (match.value.startsWith("http", true)) match.value.trimEnd('.', ',', ';', '!', '?') else match.value
                val link = when {
                    value.startsWith("http", true) -> safeCommentUrl(value)?.let(CommentLink::External)
                    videoId != null -> commentTimestamp(value)?.let { CommentLink.Video(videoId, it) }
                    else -> null
                }
                add(run.copy(text = value, link = link))
                if (value.length < match.value.length) add(run.copy(text = match.value.substring(value.length)))
                end = match.range.last + 1
            }
            if (end < run.text.length) add(run.copy(text = run.text.substring(end)))
        }
    }
}
