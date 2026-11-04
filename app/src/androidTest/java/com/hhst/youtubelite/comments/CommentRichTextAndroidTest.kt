package com.hhst.youtubelite.comments

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.comments.domain.*
import com.hhst.youtubelite.comments.presentation.CommentRichBody
import com.hhst.youtubelite.ui.design.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommentRichTextAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun timestampClicksEmitTheVideoAndOffsetWhilePreservingTheBody() {
        val body = "A detail at 00:40 is worth another look."
        var opened: CommentLink? = null
        compose.setContent { AppTheme {
            Box(Modifier.width(300.dp)) {
                CommentRichBody(body, emptyList(), "abcdefghijk", onLink = { opened = it })
            }
        } }
        val text = compose.onNodeWithText(body, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val target = layouts.single().getBoundingBox(body.indexOf("00:40") + 1).center
        text.performTouchInput { click(target) }
        compose.runOnIdle { assertEquals(CommentLink.Video("abcdefghijk", 40), opened) }
        text.assertIsDisplayed()
    }

    @Test fun serverStylesAndTruncatedLinkLabelsKeepTheirOriginalTargets() {
        val link = CommentLink.External("https://example.com/full-path")
        var opened: CommentLink? = null
        compose.setContent { AppTheme {
            CommentRichBody("Bold link", listOf(CommentTextRun("Bold ", bold = true), CommentTextRun("link", link)),
                null, onLink = { opened = it })
        } }
        val text = compose.onNodeWithText("Bold link", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        text.performTouchInput { click(layouts.single().getBoundingBox(6).center) }
        compose.runOnIdle { assertEquals(link, opened) }
    }
}
