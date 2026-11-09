package com.hhst.youtubelite.watch.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDescriptionTest {
    @Test fun commonTimestampFormsParse() {
        assertEquals(42L, timestampSeconds("0:42"))
        assertEquals(754L, timestampSeconds("12:34"))
        assertEquals(3723L, timestampSeconds("1:02:03"))
        // Long videos keep minute counts past 59 in the m:ss form.
        assertEquals(75 * 60L + 30, timestampSeconds("75:30"))
        assertEquals(0L, timestampSeconds("0:00"))
    }

    @Test fun outOfRangeAndMalformedTimesStayPlain() {
        assertNull(timestampSeconds("99:99"))
        assertNull(timestampSeconds("1:99"))
        assertNull(timestampSeconds("1:60:00"))
        assertNull(timestampSeconds("12"))
        assertNull(timestampSeconds(""))
    }

    @Test fun everySegmentIsScannedAndClassified() {
        val segments = parseDescription("Start at 0:42 then https://youtu.be/aqz-KE-bpKQ?t=90 and www.blender.org.")
        assertEquals(
            listOf(
                DescriptionSegment.Plain("Start at "),
                DescriptionSegment.Timestamp("0:42", 42),
                DescriptionSegment.Plain(" then "),
                DescriptionSegment.Link("https://youtu.be/aqz-KE-bpKQ?t=90", "https://youtu.be/aqz-KE-bpKQ?t=90"),
                DescriptionSegment.Plain(" and "),
                DescriptionSegment.Link("www.blender.org", "https://www.blender.org"),
                DescriptionSegment.Plain("."),
            ),
            segments,
        )
    }

    @Test fun chapterListKeepsHoursMinutesAndSeconds() {
        val segments = parseDescription("Chapters: 0:42 setup, 12:34 middle, 1:02:03 credits.")
        val stamps = segments.filterIsInstance<DescriptionSegment.Timestamp>()
        assertEquals(listOf(42L, 754L, 3723L), stamps.map { it.seconds })
        assertEquals(listOf("0:42", "12:34", "1:02:03"), stamps.map { it.text })
    }

    @Test fun invalidTimesAreNotLinkified() {
        val segments = parseDescription("Cut 99:99 and 12:60:30 do not exist.")
        assertTrue(segments.none { it is DescriptionSegment.Timestamp })
        assertTrue(segments.any { it is DescriptionSegment.Plain && it.text.contains("99:99") })
    }

    @Test fun clockDigitsDoNotBecomeTimestamps() {
        // The 12:60:30-style rejects keep dense digit runs out of the timestamp lane;
        // inside a URL the URL match always wins.
        val segments = parseDescription("Ratio 1:2345 stays plain https://x.test/a/1:23/b.")
        assertTrue(segments.none { it is DescriptionSegment.Timestamp })
    }

    @Test fun trailingPunctuationStaysOutsideLinks() {
        val segments = parseDescription("See (https://www.youtube.com/watch?v=aqz-KE-bpKQ), it helps.")
        val links = segments.filterIsInstance<DescriptionSegment.Link>()
        assertEquals(listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), links.map { it.url })
        assertEquals(listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), links.map { it.text })
        assertTrue(segments.contains(DescriptionSegment.Plain("),")))
    }

    @Test fun balancedParenthesesAndUppercaseSchemesStayUsable() {
        val text = "See HTTPS://en.wikipedia.org/wiki/Blender_(software). Or WWW.blender.org!"
        val segments = parseDescription(text)
        assertEquals(listOf("HTTPS://en.wikipedia.org/wiki/Blender_(software)", "https://WWW.blender.org"),
            segments.filterIsInstance<DescriptionSegment.Link>().map { it.url })
        assertEquals(text, segments.joinToString("") { when (it) {
            is DescriptionSegment.Plain -> it.text
            is DescriptionSegment.Link -> it.text
            is DescriptionSegment.Timestamp -> it.text
        } })
        assertNull(timestampSeconds("999999999999999999:00"))
        assertNull(timestampSeconds("1:-1"))
    }

    @Test fun emptyTextProducesNoSegments() {
        assertTrue(parseDescription("").isEmpty())
    }
}
