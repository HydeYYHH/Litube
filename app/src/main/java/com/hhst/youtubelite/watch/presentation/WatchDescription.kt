package com.hhst.youtubelite.watch.presentation

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import com.hhst.youtubelite.ui.design.DesignTokens

internal sealed interface DescriptionSegment {
    data class Plain(val text: String) : DescriptionSegment
    /** Chapter mark (m:ss / h:mm:ss) pointing into the current video. */
    data class Timestamp(val text: String, val seconds: Long) : DescriptionSegment
    /** [text] is what the description displays; [url] is what the click routes. */
    data class Link(val text: String, val url: String) : DescriptionSegment
}

private val urlPattern = Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val timestampPattern = Regex("""(?<![\d:])(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?(?![\d:])""")
private fun linkText(value: String): String {
    var result = value
    while (result.isNotEmpty()) {
        val last = result.last()
        val opening = when (last) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> null }
        val punctuation = last in ".,;!?*"
        val unmatched = opening != null && result.count { it == last } > result.count { it == opening }
        if (!punctuation && !unmatched) break
        result = result.dropLast(1)
    }
    return result
}

/**
 * Splits a description into clickable and plain runs the way the web renders
 * them: full URLs and bare www hosts become links, `h:mm:ss` and `m:ss` tokens
 * become chapter timestamps, everything else stays text. A URL always wins over
 * a timestamp it contains; out-of-range times stay plain text.
 */
internal fun parseDescription(text: String): List<DescriptionSegment> {
    if (text.isEmpty()) return emptyList()
    val segments = mutableListOf<DescriptionSegment>()
    var index = 0
    while (index < text.length) {
        val url = urlPattern.find(text, index)
        val time = timestampPattern.find(text, index)
        val next = listOfNotNull(url, time).minByOrNull { it.range.first }
        if (next == null) {
            segments += DescriptionSegment.Plain(text.substring(index))
            break
        }
        if (next.range.first > index) segments += DescriptionSegment.Plain(text.substring(index, next.range.first))
        if (next === url) {
            val value = linkText(next.value)
            segments += DescriptionSegment.Link(
                value,
                if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value,
            )
            if (value.length < next.value.length) segments += DescriptionSegment.Plain(next.value.substring(value.length))
        } else {
            val seconds = timestampSeconds(next.value)
            if (seconds == null) segments += DescriptionSegment.Plain(next.value)
            else segments += DescriptionSegment.Timestamp(next.value, seconds)
        }
        index = next.range.last + 1
    }
    return segments
}

/**
 * `m:ss` keeps minute counts past 59 (long videos use `75:30`); the `h:mm:ss`
 * form requires minutes and seconds in range. Every field is at most two digits,
 * so the total can never overflow.
 */
internal fun timestampSeconds(value: String): Long? {
    if (!Regex("""\d{1,2}:\d{1,2}(?::\d{1,2})?""").matches(value)) return null
    val parts = value.split(':').map { it.toLongOrNull() ?: return null }
    val seconds = when (parts.size) {
        2 -> {
            if (parts[1] > 59) return null
            parts[0] * 60L + parts[1]
        }
        3 -> {
            if (parts[1] > 59 || parts[2] > 59) return null
            parts[0] * 3600L + parts[1] * 60L + parts[2]
        }
        else -> return null
    }
    return seconds.takeIf { it >= 0 }
}

@Composable
internal fun WatchDescription(
    text: String,
    onTimestamp: (Long) -> Unit,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { parseDescription(text) }
    val currentTimestamp by rememberUpdatedState(onTimestamp)
    val currentLink by rememberUpdatedState(onLink)
    val linkStyle = TextLinkStyles(SpanStyle(color = DesignTokens.Link))
    val annotated = remember(segments, linkStyle) {
        buildAnnotatedString {
            segments.forEach { segment ->
                when (segment) {
                    is DescriptionSegment.Plain -> append(segment.text)
                    is DescriptionSegment.Timestamp -> withLink(
                        LinkAnnotation.Clickable(segment.text, linkStyle, LinkInteractionListener { currentTimestamp(segment.seconds) }),
                    ) { append(segment.text) }
                    is DescriptionSegment.Link -> withLink(
                        LinkAnnotation.Clickable(segment.url, linkStyle, LinkInteractionListener { currentLink(segment.url) }),
                    ) { append(segment.text) }
                }
            }
        }
    }
    SelectionContainer(modifier) {
        BasicText(annotated, style = DesignTokens.text(14, 20, color = DesignTokens.Secondary))
    }
}
