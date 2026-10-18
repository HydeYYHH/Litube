package com.hhst.youtubelite.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test fun keepsTrafficChallengesInsideTheAppWithoutPageScripts() {
        assertTrue(UrlPolicy.canLoad("https://www.google.com/sorry/index?continue=https%3A%2F%2Fm.youtube.com"))
        assertTrue(UrlPolicy.canLoad("https://m.youtube.com/sorry/"))
        assertFalse(UrlPolicy.shouldInject("https://m.youtube.com/sorry/"))
        assertFalse(UrlPolicy.canLoad("https://www.google.com/search?q=test"))
        assertFalse(UrlPolicy.canLoad("https://www.google.com.evil.example/sorry/"))
        assertFalse(UrlPolicy.canLoad("https://user@www.google.com/sorry/"))
        assertFalse(UrlPolicy.canLoad("http://www.google.com/sorry/"))
        assertFalse(UrlPolicy.canLoad("https://www.google.com:444/sorry/"))
    }

    @Test
    fun allowsYoutubeAndAccounts() {
        assertTrue(UrlPolicy.isAllowedUrl("https://m.youtube.com"))
        assertTrue(UrlPolicy.isAllowedUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(UrlPolicy.isAllowedUrl("https://accounts.google.com/ServiceLogin"))
    }

    @Test
    fun canLoadLocalSchemes() {
        assertTrue(UrlPolicy.canLoad("about:blank"))
    }

    @Test
    fun rejectsOtherHosts() {
        assertFalse(UrlPolicy.isAllowedUrl("https://example.com"))
        assertFalse(UrlPolicy.canLoad("https://evil.example"))
    }
}
