package com.hhst.youtubelite.account

import com.hhst.youtubelite.account.data.MediaSignInService
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MemoryAuthStore
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaSignInServiceTest {
    @Test fun anInvalidOldSessionCannotBlockRequestingANewCode() = runBlocking {
        var refreshes = 0
        MediaSession.initialize(MemoryAuthStore())
        MediaSession.tokenFactory = { token, client -> AccountToken(token, client, object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                refreshes++
                return HttpReply(400, """{"error":"invalid_grant"}""", emptyMap())
            }
        }) }
        try {
            MediaSession.completeSignIn(SignInClient("old", "secret", "device"),
                AccessToken("expired", "invalid-refresh", 0), emptyList())
            val store = MemoryAuthStore().apply {
                write(MediaSession.KEY_CLIENT_ID, "client")
                write(MediaSession.KEY_CLIENT_SECRET, "secret")
                write(MediaSession.KEY_DEVICE_ID, "device")
            }
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                assertNull(chain.request().header("Authorization"))
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"device_code":"device-code","user_code":"ABCD-EFGH","verification_url":"https://www.youtube.com/activate","expires_in":600,"interval":1}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build()
            assertEquals("ABCD-EFGH", MediaSignInService(http, store).loadCode().userCode)
            assertEquals(0, refreshes)
        } finally {
            MediaSession.tokenFactory = { token, client -> AccountToken(token, client) }
            MediaSession.initialize(MemoryAuthStore())
        }
    }
}
