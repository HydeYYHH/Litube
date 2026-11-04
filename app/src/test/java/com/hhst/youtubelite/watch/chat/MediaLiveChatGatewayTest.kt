package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.data.media.*
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaLiveChatGatewayTest {
    private val native = RequestContext(accountId = "owner", accessToken = "oauth", generation = 1)
    private fun store() = ChatWebSessionStore(MemoryAuthStore()).apply {
        save(native, native.copy(accessToken = null, webSession = WebSession("SAPISID=test")))
    }
    private fun gateway(store: ChatWebSessionStore, respond: (Request) -> Pair<Int, String>): MediaLiveChatGateway {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody()).build()
        }.build()
        return MediaLiveChatGateway(MediaClient(http) { native }, store)
    }

    @Test fun missingChatAuthorizationNeverUsesOauthToPost() = runBlocking {
        val gateway = gateway(ChatWebSessionStore(MemoryAuthStore())) { error("No request before authorization") }
        try { gateway.send("chat", "params", "hello"); fail() }
        catch (_: ChatAuthorizationRequiredException) { }
    }

    @Test fun subscribersOnlyModeIsCheckedBeforeAnyWrite() = runBlocking {
        val paths = mutableListOf<String>()
        val gateway = gateway(store()) { request ->
            paths += request.url.encodedPath.substringAfterLast('/')
            200 to """{"continuationContents":{"liveChatContinuation":{"actionPanel":{"liveChatRestrictedParticipationRenderer":{"message":{"simpleText":"Subscribers-only mode"}}}}}}"""
        }
        try { gateway.send("chat", "params", "hello"); fail() }
        catch (error: ChatPostingRestrictedException) { assertEquals("Subscribers-only mode", error.reason) }
        assertEquals(listOf("get_live_chat"), paths)
    }

    @Test fun anEligibleChatUsesItsServerParamsAndCookieAuthExactlyOnce() = runBlocking {
        val paths = mutableListOf<String>()
        val gateway = gateway(store()) { request ->
            val path = request.url.encodedPath.substringAfterLast('/')
            paths += path
            assertTrue(request.header("Authorization")!!.startsWith("SAPISIDHASH "))
            if (path == "get_live_chat") 200 to """{"continuationContents":{"liveChatContinuation":{"inputPanel":{"sendLiveChatMessageEndpoint":{"params":"server-params"}}}}}"""
            else {
                val buffer = okio.Buffer()
                request.body!!.writeTo(buffer)
                assertTrue(buffer.readUtf8().contains("\"params\":\"server-params\""))
                200 to """{"actions":[{"addChatItemAction":{"item":{"liveChatTextMessageRenderer":{"id":"server-id"}}}}]}"""
            }
        }
        gateway.send("chat", "fallback-params", "hello")
        assertEquals(listOf("get_live_chat", "send_message"), paths)
    }

    @Test fun expiredWebAuthDoesNotSignOutTheNativeAccountOrTryAWritingFallback() = runBlocking {
        val paths = mutableListOf<String>()
        val gateway = gateway(store()) { request ->
            paths += request.url.encodedPath.substringAfterLast('/')
            401 to "{}"
        }
        try { gateway.send("chat", "params", "hello"); fail() }
        catch (_: ChatAuthorizationRequiredException) { }
        assertTrue(native.signedIn)
        assertEquals("oauth", native.accessToken)
        assertEquals(listOf("get_live_chat"), paths)
    }
}
