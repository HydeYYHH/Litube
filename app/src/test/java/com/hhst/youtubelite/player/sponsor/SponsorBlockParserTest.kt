package com.hhst.youtubelite.player.sponsor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockParserTest {

    private val sample = """
        [
          {
            "videoID": "abc12345678",
            "segments": [
              {"category": "sponsor", "segment": [12.5, 30.0]},
              {"category": "selfpromo", "segment": [60.0, 75.25]}
            ]
          },
          {
            "videoID": "other000000",
            "segments": [
              {"category": "sponsor", "segment": [1.0, 2.0]}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun parse_filtersByVideoIdAndConvertsToMs() {
        val segments = SponsorBlockParser.parse(sample, "abc12345678")
        assertEquals(2, segments.size)
        assertEquals(12_500L, segments[0].startMs)
        assertEquals(30_000L, segments[0].endMs)
        assertEquals("sponsor", segments[0].category)
        assertEquals(75_250L, segments[1].endMs)
    }

    @Test
    fun parse_noMatch_returnsEmpty() {
        assertTrue(SponsorBlockParser.parse(sample, "nomatch0000").isEmpty())
    }

    @Test
    fun parse_malformed_returnsEmpty() {
        assertTrue(SponsorBlockParser.parse("not json", "abc12345678").isEmpty())
        assertTrue(SponsorBlockParser.parse("{}", "abc12345678").isEmpty())
    }

    @Test
    fun parse_shortSegment_dropped() {
        val json = """[{"videoID":"abc12345678","segments":[{"category":"sponsor","segment":[1.0]}]}]"""
        assertTrue(SponsorBlockParser.parse(json, "abc12345678").isEmpty())
    }

    @Test
    fun parse_musicOfftopic_keepsCategory() {
        val json =
            """[{"videoID":"cF1Na4AIecM","segments":[{"category":"music_offtopic","segment":[0.0,51.5]}]}]"""
        val segments = SponsorBlockParser.parse(json, "cF1Na4AIecM")
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(51_500L, segments[0].endMs)
        assertEquals("music_offtopic", segments[0].category)
    }
}
