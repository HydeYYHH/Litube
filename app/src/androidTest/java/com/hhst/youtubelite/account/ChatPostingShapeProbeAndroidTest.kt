package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.koin.core.context.GlobalContext
import java.io.File

/** Shape/permission reads only. The transport cannot send a chat message. */
class ChatPostingShapeProbeAndroidTest {
    @Test fun productionChatHasAComposerAndDecodableAvatars(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("chatPostingProbe") == "1")
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            check(chain.request().url.encodedPath.substringAfterLast('/') in setOf("next", "player", "get_live_chat", "token"))
            chain.proceed(chain.request())
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val video = MediaWatchRepository(client).watch("rFZHOHl-L8A").video
        val target = com.hhst.youtubelite.watch.chat.liveChatTarget(video, true, true, false, false, false)!!
        assertNotNull("A canonical broadcaster must produce message addressing", target.messageParams)
        val vm = kotlinx.coroutines.withContext(Dispatchers.Main) {
            com.hhst.youtubelite.watch.chat.LiveChatViewModel(com.hhst.youtubelite.watch.chat.MediaLiveChatGateway(client)).also {
                it.open(target.key, target.messageParams)
            }
        }
        try {
            val state = kotlinx.coroutines.withTimeout(25_000) {
                vm.uiState.first { it.content is com.hhst.youtubelite.watch.chat.LiveChatContent.Ready && it.canSend }
            }
            val messages = (state.content as com.hhst.youtubelite.watch.chat.LiveChatContent.Ready).messages
            val photo = messages.first { it.photo.startsWith("https://yt4.ggpht.com/") }.photo
            val bitmap = com.hhst.youtubelite.core.YoutubeThumbnail.await(photo, 128)
            assertNotNull("The production image loader must decode a real chat avatar", bitmap)
            val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            File(files, "chat-avatar.png").outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            File(files, "chat-posting-production.json").writeText(JSONObject().put("videoId", video.id)
                .put("canonicalBroadcaster", video.channelId.startsWith("UC")).put("canSend", state.canSend)
                .put("messages", messages.size).put("avatarWidth", bitmap!!.width).put("avatarHeight", bitmap.height)
                .put("postedMessages", 0).toString(2))
        } finally { kotlinx.coroutines.withContext(Dispatchers.Main) { vm.close() } }
    }

    @Test fun inspectChatInputAndPhotos(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("chatPostingProbe") == "1")
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            check(chain.request().url.encodedPath.substringAfterLast('/') in setOf("next", "get_live_chat", "token"))
            chain.proceed(chain.request())
        }.build()
        val report = JSONArray()
        try {
            MediaClient(http, MediaSession::freshContext).read { context, transport ->
                val key = loadWatch("rFZHOHl-L8A", context.anonymous(), http = transport).requireValue().chatKey!!
                for (client in listOf("TV", "WEB_GUEST", "WEB", "ANDROID", "ANDROID_VR")) {
                    val template = playerQuery(if (client == "TV") PlayerClient.TV else if (client == "ANDROID_VR") PlayerClient.ANDROID_VR else PlayerClient.WEB,
                        "context", context.visitor)
                    val requestContext = JSONObject(template.body).getJSONObject("context")
                    val headers = template.headers.toMutableMap()
                    if (client == "ANDROID") {
                        requestContext.getJSONObject("client").put("clientName", "ANDROID").put("clientVersion", "21.03.36").put("androidSdkVersion", 36)
                        headers["X-Youtube-Client-Name"] = "3"; headers["X-Youtube-Client-Version"] = "21.03.36"
                        headers["User-Agent"] = "com.google.android.youtube/21.03.36 (Linux; U; Android 16; US) gzip"
                    }
                    if (client != "WEB_GUEST") {
                        context.accessToken?.let { headers["Authorization"] = "Bearer $it" }
                        context.onBehalfOfUser?.let { requestContext.put("user", JSONObject().put("onBehalfOfUser", it)) }
                    }
                    val reply = transport.execute(HttpRequest("POST", "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat", headers,
                        JSONObject().put("context", requestContext).put("continuation", key).toString()))
                    val root = JSONObject(reply.body)
                    val chat = root.optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")
                    val input = JSONObject()
                    val photos = JSONArray()
                    var messageKeys: List<String>? = null
                    fun visit(value: Any?) {
                        when (value) {
                            is JSONObject -> value.keys().forEach { name ->
                                if (name in setOf("liveChatMessageInputRenderer", "sendLiveChatMessageEndpoint", "liveChatRestrictedParticipationRenderer", "liveChatActionPanelRenderer"))
                                    input.put(name, input.optInt(name) + 1)
                                if (name == "liveChatTextMessageRenderer" && photos.length() < 2) {
                                    val m = value.getJSONObject(name)
                                    messageKeys = m.keys().asSequence().toList()
                                    photos.put(m.opt("authorPhoto") ?: JSONObject.NULL)
                                }
                                visit(value.opt(name))
                            }
                            is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                        }
                    }
                    visit(root)
                    val parsed = chatPage(reply.body)
                    val error = root.optJSONObject("error")
                    report.put(JSONObject().put("client", client).put("status", reply.status)
                        .put("errorMessage", error?.optString("message")).put("errorStatus", error?.optString("status"))
                        .put("chatKeys", chat?.keys()?.asSequence()?.toList()?.let(::JSONArray)).put("input", input)
                        .put("messageKeys", messageKeys?.let(::JSONArray)).put("photos", photos)
                        .put("messages", parsed.messages.size).put("parsedPhotos", parsed.messages.count { !it.photo.isNullOrBlank() }))
                }
            }
        } finally {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "chat-posting-shapes.json").writeText(report.toString(2))
        }
    }
}
