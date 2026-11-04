package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WebSessionTest {
    @Test fun signsOnlyWithARealCredentialCookie() {
        assertFalse(WebSession("PREF=anonymous; VISITOR_INFO1_LIVE=visitor").signedIn)
        assertTrue(WebSession("__Secure-3PAPISID=secret; PREF=x").signedIn)
        assertTrue(WebSession("SAPISID=secret").headers(42)["Authorization"]!!.startsWith("SAPISIDHASH 42_"))
        assertTrue(WebSession("PREF=x").headers(42).isEmpty())
    }
    @Test fun sessionsNeverRevealCredentialsInStringRepresentations() {
        val session = WebSession("SAPISID=private", 1)
        val context = RequestContext(accessToken = "bearer", webSession = session)
        assertFalse(session.toString().contains("private"))
        assertFalse(context.toString().contains("bearer"))
        assertFalse(context.toString().contains("private"))
        assertFalse(context.anonymous().signedIn)
    }
    @Test fun credentialsAreRestrictedToTheFirstPartyApiOrigin() {
        val context = RequestContext(webSession = WebSession("SAPISID=secret"))
        val query = searchQuery("music", context)
        assertEquals("SAPISID=secret", query.headers["Cookie"])
        for (url in listOf("https://evil.test/youtubei/v1/search", "https://www.youtube.com:444/youtubei/v1/search",
            "https://www.youtube.com.evil.test/youtubei/v1/search", "https://www.youtube.com/watch")) {
            val request = contextualQuery(query.copy(url = url), context)
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Origin"])
            assertNull(request.headers["X-Goog-PageId"])
        }
    }
    @Test fun sessionScopesSeparateAccountsAndAnonymousReads() {
        val first = RequestContext(webSession = WebSession("SAPISID=a"))
        assertNotEquals(first.scope(), first.copy(webSession = WebSession("SAPISID=b")).scope())
        assertNotEquals(first.scope(), first.copy(webSession = WebSession("SAPISID=a", 1)).scope())
        assertNotEquals(first.scope(), first.anonymous().scope())
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsHeaderInjection() { WebSession("SAPISID=secret\r\nX-Foo: bad") }
}
