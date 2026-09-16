package com.hhst.youtubelite.player.sponsor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockHttpTest {

    @Test
    fun notFoundIsValidEmptyResult() {
        val result = SponsorBlockManager.interpretHttp(404, "Not Found", "aaaaaaaaaaa")
        assertEquals(emptyList<SponsorBlockManager.Segment>(), result)
    }

    @Test
    fun serverErrorKeepsPreviousByReturningNull() {
        assertNull(SponsorBlockManager.interpretHttp(502, "bad gateway", "aaaaaaaaaaa"))
        assertNull(SponsorBlockManager.interpretHttp(500, null, "aaaaaaaaaaa"))
    }

    @Test
    fun okParsesMatchingVideo() {
        val json = """[{"videoID":"aaaaaaaaaaa","segments":[{"category":"sponsor","segment":[1.0,2.0]}]}]"""
        val result = SponsorBlockManager.interpretHttp(200, json, "aaaaaaaaaaa")
        assertEquals(1, result!!.size)
        assertEquals("sponsor", result[0].category)
        assertEquals(1000L, result[0].startMs)
        assertEquals(2000L, result[0].endMs)
    }

    @Test
    fun okMismatchedVideoIsEmptyNotNull() {
        val json = """[{"videoID":"bbbbbbbbbbb","segments":[{"category":"sponsor","segment":[1.0,2.0]}]}]"""
        val result = SponsorBlockManager.interpretHttp(200, json, "aaaaaaaaaaa")
        assertTrue(result!!.isEmpty())
    }
}
