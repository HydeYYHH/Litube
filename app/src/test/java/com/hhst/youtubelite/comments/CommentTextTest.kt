package com.hhst.youtubelite.comments

import com.hhst.youtubelite.comments.domain.*
import org.junit.Assert.*
import org.junit.Test

class CommentTextTest {
    private val video = "abcdefghijk"

    @Test fun timestampsNavigateToTheCurrentVideoWithoutChangingText() {
        val body = "Intro 00:40, then 1:02:03 and 12:99. abc00:30 stays plain."
        val runs = commentTextRuns(body, emptyList(), video)
        assertEquals(body, runs.joinToString("") { it.text })
        assertEquals(listOf(CommentLink.Video(video, 40), CommentLink.Video(video, 3723)), runs.mapNotNull { it.link })
        assertTrue(commentTextRuns(body, emptyList(), null).all { it.link == null })
    }

    @Test fun structuredStylesAndTargetsSurviveFallbackLinkParsing() {
        val runs = listOf(CommentTextRun("Author", CommentLink.Channel("UC" + "a".repeat(22)), bold = true),
            CommentTextRun(" 00:40", italic = true, strikethrough = true))
        val result = commentTextRuns("Author 00:40", runs, video)
        assertEquals(runs.first(), result.first())
        assertTrue(result.last().italic && result.last().strikethrough)
        assertEquals(CommentLink.Video(video, 40), result.last().link)
    }

    @Test fun externalLinksDoNotLinkifyEmbeddedTimesOrAcceptCredentials() {
        val body = "https://example.com/00:40. https://user:pass@example.com/ 00:20"
        val runs = commentTextRuns(body, emptyList(), video)
        assertEquals(body, runs.joinToString("") { it.text })
        assertEquals(listOf(CommentLink.External("https://example.com/00:40"), CommentLink.Video(video, 20)), runs.mapNotNull { it.link })
        assertNull(safeCommentUrl("javascript:alert(1)"))
        assertNull(safeCommentUrl("file:///data/private"))
    }

    @Test fun mismatchedServerRunsFallBackToTheActualBody() {
        assertEquals("Text", commentTextRuns("Text", listOf(CommentTextRun("Other")), video).joinToString("") { it.text })
    }

    @Test fun onlyMatchingVodTimestampsSeekAndPositionsStayBounded() {
        val link = CommentLink.Video(video, 40)
        assertEquals(40_000L, link.seekPosition(video, 90_000, false))
        assertEquals(30_000L, link.seekPosition(video, 30_000, false))
        assertNull(link.seekPosition("other", 90_000, false))
        assertNull(link.seekPosition(video, 90_000, true))
        assertNull(CommentLink.Video(video, Long.MAX_VALUE).seekPosition(video, 90_000, false))
        assertEquals(40_000L, CommentLink.External("https://youtu.be/$video?t=40").seekPosition(video, 90_000, false))
        assertNull(CommentLink.External("https://example.com/watch?v=$video&t=40").seekPosition(video, 90_000, false))
    }
}
