package com.hhst.youtubelite.comments.presentation

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
internal fun CommentRichBody(body: String, runs: List<CommentTextRun>, videoId: String?,
    modifier: Modifier = Modifier, onLink: (CommentLink) -> Unit) {
    val parts = remember(body, runs, videoId) { commentTextRuns(body, runs, videoId) }
    val open by rememberUpdatedState(onLink)
    val linkStyle = TextLinkStyles(SpanStyle(color = DesignTokens.Link))
    val text = remember(parts, linkStyle) {
        buildAnnotatedString {
            parts.forEach { run ->
                withStyle(SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = if (run.strikethrough) TextDecoration.LineThrough else null,
                )) {
                    if (run.link == null) append(run.text)
                    else withLink(LinkAnnotation.Clickable("comment-link", linkStyle,
                        LinkInteractionListener { open(run.link) })) { append(run.text) }
                }
            }
        }
    }
    SelectionContainer(modifier) {
        BasicText(text, style = DesignTokens.text(14, 18).copy(lineHeight = 17.5.sp))
    }
}
