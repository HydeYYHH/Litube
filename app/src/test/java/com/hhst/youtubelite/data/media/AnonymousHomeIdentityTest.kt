package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import org.junit.Assert.*
import org.junit.Test

class AnonymousHomeIdentityTest {
    @Test fun retainsOnlyAnonymousCookiesAndRejectsEmptyIdentities() {
        val cookies = anonymousVisitorCookie("VISITOR_INFO1_LIVE=visitor; Path=/\nSID=secret; Path=/\nYSC=session; Path=/")!!
        assertTrue(cookies.contains("VISITOR_INFO1_LIVE=visitor"))
        assertTrue(cookies.contains("YSC=session"))
        assertFalse(cookies.contains("SID="))
        assertNull(anonymousVisitorCookie("VISITOR_INFO1_LIVE=; Path=/\nSID=secret"))
    }

    @Test fun bootstrapDoesNotMixAnOldVisitorWithANewCookie() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.headers.getValue("Cookie").startsWith("SOCS="))
                assertTrue(request.headers.getValue("User-Agent").contains("Cobalt"))
                assertNull(request.headers["Authorization"])
                return HttpReply(200, "{\"VISITOR_DATA\":\"paired\"}", mapOf("Set-Cookie" to "VISITOR_INFO1_LIVE=new; Path=/"))
            }
        }
        val first = anonymousHomeContext(RequestContext(visitor = "old"), http)
        assertEquals("paired", first.visitor)
        assertTrue(first.visitorCookie!!.contains("VISITOR_INFO1_LIVE=new"))
        assertEquals(first, anonymousHomeContext(first, http, verified = true))
        anonymousHomeContext(RequestContext(accessToken = "account"), http)
        assertEquals(1, calls)
    }

    @Test fun missingDocumentIdentityCannotCreateAnUnrelatedPair() {
        val original = RequestContext(visitor = "existing")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, "unsupported browser",
                mapOf("Set-Cookie" to "VISITOR_INFO1_LIVE=new; Path=/"))
        }
        assertEquals(original, anonymousHomeContext(original, http))
    }

    @Test fun refreshingAStoredCookieKeepsTheIdentityWhenOnlySessionCookiesAreReissued() {
        val original = RequestContext(visitorCookie = "VISITOR_INFO1_LIVE=same; YSC=old")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertTrue(request.headers.getValue("Cookie").contains("VISITOR_INFO1_LIVE=same"))
                return HttpReply(200, "{\"visitorData\":\"canonical\"}", mapOf("Set-Cookie" to "YSC=new; Path=/"))
            }
        }
        val refreshed = anonymousHomeContext(original, http)
        assertEquals("canonical", refreshed.visitor)
        assertTrue(refreshed.visitorCookie.orEmpty().contains("VISITOR_INFO1_LIVE=same"))
        assertTrue(refreshed.visitorCookie.orEmpty().contains("YSC=new"))
    }
}
