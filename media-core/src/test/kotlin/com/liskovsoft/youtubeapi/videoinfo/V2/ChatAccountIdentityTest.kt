package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChatAccountIdentityTest {
    @Test fun cookieAndOauthSessionsUseTheSameIdentityRenderer() {
        for (context in listOf(RequestContext(accessToken = "oauth"), RequestContext(webSession = WebSession("SAPISID=secret")))) {
            val result = loadActiveAccount(context, object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    assertEquals("TVHTML5", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
                    assertNotNull(request.headers["Authorization"])
                    return HttpReply(200, """{"contents":[{"accountItem":{"accountId":"stable-id","accountName":{"simpleText":"Owner"},"accountByline":{"simpleText":"owner@example.com"},"isSelected":true}}]}""")
                }
            })
            assertEquals("stable-id", result.value?.id)
            assertEquals("owner@example.com", result.value?.email)
        }
    }

    @Test fun ambiguousAccountsAndAnonymousSessionsCannotVerifyAnIdentity() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, """{"items":[{"accountItem":{"accountId":"one"}},{"accountItem":{"accountId":"two"}}]}""")
            }
        }
        assertEquals(RequestFailure.AUTH, loadActiveAccount(RequestContext(), http).error?.kind)
        assertEquals(0, calls)
        assertEquals("missing_active_account", loadActiveAccount(RequestContext(accessToken = "oauth"), http).error?.code)
        assertEquals(1, calls)
    }
}
