package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.koin.core.context.GlobalContext
import java.io.File

/** Opt-in live-chat reads only; never sends a message or starts playback. */
class LiveChatProbeAndroidTest {
    @Test fun discoverAndReadLiveChat(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("chatProbe") == "1")
        val requests = JSONArray()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            check(path in setOf("search", "next", "player", "get_live_chat", "get_live_chat_replay", "token"))
            chain.proceed(chain.request()).also { response -> if (path != "token") runCatching {
                val root = JSONObject(response.peekBody(4L * 1024 * 1024).string())
                val shapes = JSONObject()
                fun visit(value: Any?) {
                    when (value) {
                        is JSONObject -> value.keys().forEach { key ->
                            if (key in setOf("liveChatRenderer", "liveChatContinuation", "liveChatTextMessageRenderer", "error",
                                    "reloadContinuationData", "invalidationContinuationData", "timedContinuationData", "liveChatReplayContinuationData",
                                    "sendLiveChatMessageEndpoint", "liveChatMessageInputRenderer")) {
                                shapes.put(key, shapes.optInt(key) + 1)
                                if (key == "liveChatRenderer") shapes.put("rendererKeys", JSONArray((value.opt(key) as JSONObject).keys().asSequence().toList()))
                            }
                            visit(value.opt(key))
                        }
                        is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                    }
                }
                visit(root)
                synchronized(requests) { requests.put(JSONObject().put("endpoint", path).put("status", response.code)
                    .put("authenticated", chain.request().header("Authorization") != null)
                    .put("client", chain.request().header("X-Youtube-Client-Name")).put("shape", shapes)) }
            } }
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val report = JSONObject()
        try {
            val items = client.read { context, transport -> loadSearch("lofi hip hop radio live", context, http = transport).requireValue().items }
            val ids = args.getString("videoId")?.let(::listOf) ?: items.filter { it.live }.map { it.id }.distinct().take(3)
            check(ids.isNotEmpty()) { "No live candidates" }
            val samples = JSONArray()
            report.put("samples", samples)
            for (id in ids) {
                val sample = JSONObject().put("id", id)
                samples.put(sample)
                client.read { context, transport ->
                    val tv = loadWatch(id, context, http = transport)
                    val web = loadWatch(id, context.anonymous(), http = transport)
                    sample.put("tvChat", !tv.value?.chatKey.isNullOrBlank()).put("webChat", !web.value?.chatKey.isNullOrBlank())
                        .put("tvError", tv.error?.code).put("webError", web.error?.code)
                    web.value?.chatKey?.let { key ->
                        val publicChat = loadChat(key, context.anonymous(), transport)
                        sample.put("publicChatError", publicChat.error?.code).put("publicMessages", publicChat.value?.messages?.size)
                            .put("publicNext", publicChat.value?.nextKey != null)
                        val accountChat = loadChat(key, context, transport)
                        sample.put("accountChatError", accountChat.error?.code).put("accountMessages", accountChat.value?.messages?.size)
                            .put("accountNext", accountChat.value?.nextKey != null).put("accountSend", accountChat.value?.sendParams != null)
                    }
                }
                val video = MediaWatchRepository(client).watch(id).video
                sample.put("appLive", video.live).put("appChat", !video.chatKey.isNullOrBlank()).put("appChatFailed", video.chatLookupFailed)
                assertTrue("The candidate must still be live", video.live)
                assertFalse("Discovery should not fail", video.chatLookupFailed)
                assertFalse("An active chat must not be marked unavailable", video.chatKey.isNullOrBlank())
                if (id == ids.first()) coroutineScope {
                    val complete = CompletableDeferred<Unit>()
                    val pages = java.util.concurrent.atomic.AtomicInteger()
                    val messages = java.util.concurrent.atomic.AtomicInteger()
                    val listener = launch {
                        com.hhst.youtubelite.watch.chat.MediaLiveChatGateway(client).listen(requireNotNull(video.chatKey)) { page ->
                            messages.addAndGet(page.messages.size)
                            if (pages.incrementAndGet() >= 2) complete.complete(Unit)
                        }
                    }
                    try { withTimeout(25_000) { complete.await() } }
                    finally { listener.cancelAndJoin() }
                    assertTrue("The production poll loop must deliver messages", messages.get() > 0)
                    sample.put("gatewayPages", pages.get()).put("gatewayMessages", messages.get())
                }
            }
        } finally {
            report.put("requests", requests)
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "live-chat-probe.json").writeText(report.toString(2))
        }
    }
}
