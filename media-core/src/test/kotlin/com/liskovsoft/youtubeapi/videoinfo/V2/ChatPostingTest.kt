package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChatPostingTest {
    @Test fun participationRestrictionsComeFromTheActualChatActionPanel() {
        val page = chatPage("""{"continuationContents":{"liveChatContinuation":{"actionPanel":{"liveChatRestrictedParticipationRenderer":{"message":{"runs":[{"text":"Subscribers-only mode"}]}}}}}}""")
        assertEquals("Subscribers-only mode", page.postingRestriction)
        assertNull(chatPage("""{"help":{"liveChatRestrictedParticipationRenderer":{"message":{"simpleText":"Info only"}}}}""").postingRestriction)
    }

    @Test fun aRejectionInsideHttpSuccessIsStillARejectedWrite() {
        val result = send("""{"errorMessage":{"liveChatTextActionsErrorMessageRenderer":{"errorText":{"runs":[{"text":"Unable to send message"}]}}},"actions":[{"dimChatItemAction":{}}]}""")
        assertFalse(result.ok)
        assertEquals("chat_message_rejected", result.error?.code)
        assertEquals("Unable to send message", result.error?.detail)
    }
    @Test fun webPostingUsesCookiesAndTheSelectedChannelWithoutRetries() {
        var calls = 0
        val context = RequestContext(accountId = "viewer", webSession = WebSession("SAPISID=secret", 2), onBehalfOfUser = "brand")
        val result = sendChatMessage("address", "hello", context, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                assertEquals("SAPISID=secret", request.headers["Cookie"])
                assertEquals("2", request.headers["X-Goog-AuthUser"])
                assertEquals("brand", request.headers["X-Goog-PageId"])
                assertEquals("https://www.youtube.com", request.headers["Origin"])
                assertEquals("WEB", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
                return HttpReply(403, """{"error":{"status":"PERMISSION_DENIED"}}""")
            }
        })
        assertEquals(1, calls)
        assertEquals(RequestFailure.HTTP, result.error?.kind)
        assertEquals("chat_posting_restricted", result.error?.code)
    }

    @Test fun broadcastAddressMatchesTheLiveMessageProto() {
        assertEquals("Q2lrcUp3b1lWVU5oWVdGaFlXRmhZV0ZoWVdGaFlXRmhZV0ZoWVdGaEVndGhZV0ZoWVdGaFlXRmhZUkFCR0FRJTNE",
            chatMessageParameters("a".repeat(11), "UC" + "a".repeat(22)))
        for ((video, channel) in listOf("" to "", "short" to "UCchannel", "a".repeat(11) to "@handle",
            "a".repeat(11) to "UC" + "a".repeat(23), "a".repeat(10) + "/" to "UC" + "a".repeat(22))) {
            assertNull(chatMessageParameters(video, channel))
        }
    }

    private fun send(response: String, status: Int = 200) = sendChatMessage("address", "hello", RequestContext("viewer", "token"),
        object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(status, response) })

    @Test fun onlyARealMessageEntityConfirmsPosting() {
        val accepted = send("""{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"server-id","message":{"runs":[{"text":"hello"}]}}}}}]}""")
        assertTrue(accepted.value!!.confirmed)
        for (response in listOf("{}", """{"status":"SUCCESS"}""",
            """{"actions":[{"addChatItemAction":{}}]}""",
            """{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":""}}}}]}""",
            """{"actions":[{"runAttestationCommand":{"challenge":"challenge"}}]}""")) {
            assertFalse(response, send(response).value!!.confirmed)
        }
        assertFalse(send("""{"error":{"code":403,"message":"Posting restricted"}}""", 403).ok)
    }

    @Test fun requestUsesTextSegmentsAndNeverAnAnonymousWrite() {
        var calls = 0
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls++
            assertEquals("Bearer token", request.headers["Authorization"])
            val body = jsonObject(request.body)!!
            assertEquals("address", body.text("params"))
            val rich = body.path("richMessage")!!
            assertTrue(rich.arr("textRuns").isEmpty())
            assertEquals("你好 \"chat\"\nnext", (rich.arr("textSegments").single() as Json.Obj).text("text"))
            return HttpReply(200, "{}")
        } }
        sendChatMessage("address", "  你好 \"chat\"\nnext  ", RequestContext("viewer", "token"), http)
        assertEquals(1, calls)
        assertFalse(sendChatMessage("address", "hello", RequestContext(), http).ok)
        assertEquals(1, calls)
    }
}
