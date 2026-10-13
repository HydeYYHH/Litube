package com.hhst.youtubelite.browser

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class WatchAutoplayCandidateTest {
    private val id = "aaaaaaaaaaa"
    private fun result(enabled: String = "true", owner: String = id, url: String = "https://m.youtube.com/watch?v=bbbbbbbbbbb") =
        """{"videoId":"$owner","enabled":$enabled,"url":"$url"}"""

    @Test fun acceptsAnExplicitTargetIncludingWebViewStringEncoding() {
        val json = result()
        assertEquals("https://m.youtube.com/watch?v=bbbbbbbbbbb", WatchAutoplayCandidate.parse(Gson().toJson(json), id)?.url)
        assertNotNull(WatchAutoplayCandidate.parse(json, id))
    }

    @Test fun stopsWhenSwitchIsOffUnknownOrWronglyTyped() {
        for (value in listOf("false", "null", "\"true\"", "1")) assertNull(WatchAutoplayCandidate.parse(result(value), id))
        assertNull(WatchAutoplayCandidate.parse(null, id))
    }

    @Test fun rejectsStaleSelfShortsAndForeignTargets() {
        assertNull(WatchAutoplayCandidate.parse(result(owner = "ccccccccccc"), id))
        for (url in listOf("https://m.youtube.com/watch?v=$id", "https://youtube.com.attacker.test/watch?v=bbbbbbbbbbb",
            "https://m.youtube.com/shorts/bbbbbbbbbbb", "javascript:alert(1)", "https://m.youtube.com/results?search_query=x")) {
            assertNull(url, WatchAutoplayCandidate.parse(result(url = url), id))
        }
    }
}
