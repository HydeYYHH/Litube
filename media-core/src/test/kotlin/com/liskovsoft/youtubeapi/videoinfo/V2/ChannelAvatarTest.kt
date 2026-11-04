package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChannelAvatarTest {
    private class Replies(vararg bodies: Pair<Int, String>) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        private val replies = bodies.toList()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            val (status, body) = replies[requests.lastIndex]
            return HttpReply(status, body)
        }
    }

    private val web = """{"metadata":{"channelMetadataRenderer":{"externalId":"UCabc","avatar":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/avatar=s160"}]}}}}"""
    private val music = """{"header":{"musicVisualHeaderRenderer":{"foregroundThumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.googleusercontent.com/avatar=s160"}]}}}}}}"""

    @Test fun publicMetadataReadsAreSmallAndIndependentOfAccountCredentials() {
        val http = Replies(200 to music)
        val reply = loadChannelAvatar("UCabc", RequestContext(accountId = "account", accessToken = "secret",
            onBehalfOfUser = "page", visitor = "visitor", language = "zh", region = "TW"), http)
        assertEquals("https://yt3.googleusercontent.com/avatar=s160", reply.value)
        val request = http.requests.single()
        assertTrue(request.url.contains("prettyPrint=false"))
        assertTrue(request.url.endsWith("fields=header"))
        assertFalse(request.headers.keys.any { it.equals("Authorization", true) || it.equals("X-Goog-PageId", true) })
        assertFalse(request.body.contains("secret"))
        assertFalse(request.body.contains("onBehalfOfUser"))
        val client = jsonObject(request.body)!!.path("context", "client")!!
        assertEquals("WEB_REMIX", client.text("clientName"))
        assertEquals("67", request.headers["X-Youtube-Client-Name"])
        assertEquals("zh", client.text("hl"))
        assertEquals("TW", client.text("gl"))
        assertEquals("visitor", client.text("visitorData"))
    }

    @Test fun missingMusicHeaderFallsBackToWebMetadataOnce() {
        val http = Replies(200 to "{}", 200 to web)
        assertEquals("https://yt3.googleusercontent.com/avatar=s160", loadChannelAvatar("UCabc", http = http).value)
        assertEquals(2, http.requests.size)
        assertEquals("WEB", jsonObject(http.requests.last().body)!!.path("context", "client")!!.text("clientName"))
        assertTrue(http.requests.last().url.contains("tvSurfaceContentRenderer%2Fheader"))
    }

    @Test fun missingAvatarsRemainAnExplicitFailureAfterBothShapes() {
        val http = Replies(200 to "{}", 200 to "{}")
        assertEquals("channel_avatar_missing", loadChannelAvatar("UCabc", http = http).error?.code)
        assertEquals(2, http.requests.size)
    }

    @Test fun httpFailuresDoNotFanOutToAnotherClient() {
        val http = Replies(429 to "{}")
        assertEquals("http_429", loadChannelAvatar("UCabc", http = http).error?.code)
        assertEquals(1, http.requests.size)
    }

    @Test fun artistHeadersUseTheirThumbnail() {
        val http = Replies(200 to """{"header":{"musicImmersiveHeaderRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/artist"}]}}}}}}""")
        assertEquals("https://yt3.ggpht.com/artist", loadChannelAvatar("UCartist", http = http).value)
        assertEquals(1, http.requests.size)
    }

    @Test fun musicBackgroundArtCannotBecomeAnAvatar() {
        val http = Replies(200 to """{"header":{"musicVisualHeaderRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/banner"}]}}}}}}""", 200 to web)
        assertEquals("https://yt3.googleusercontent.com/avatar=s160", loadChannelAvatar("UCabc", http = http).value)
        assertEquals(2, http.requests.size)
    }
}
