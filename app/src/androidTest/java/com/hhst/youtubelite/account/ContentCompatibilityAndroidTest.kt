package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.requireValue
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import java.io.File

class ContentCompatibilityAndroidTest {
    @Test fun inspectReadContracts() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("contentReads") == "1")
        val events = JSONArray()
        val failures = mutableListOf<String>()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val root = runCatching { JSONObject(response.peekBody(4L * 1024 * 1024).string()) }.getOrNull()
            val keys = linkedMapOf<String, Int>()
            val fragments = JSONArray()
            fun shape(value: Any?, depth: Int = 0, name: String = ""): Any? = when {
                depth > 12 -> "..."
                value is JSONObject -> JSONObject().apply { value.keys().forEach { key -> put(key, shape(value.opt(key), depth + 1, key)) } }
                value is JSONArray -> JSONArray().apply { for (i in 0 until minOf(4, value.length())) put(shape(value.opt(i), depth + 1)) }
                value is String -> if (name in setOf("panelIdentifier", "targetId", "identifier")) value.take(100) else "[text]"
                else -> value
            }
            fun visit(value: Any?, depth: Int = 0) {
                if (depth > 40) return
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        keys[key] = (keys[key] ?: 0) + 1
                        if (key in setOf("channelHeaderRenderer", "engagementPanelSectionListRenderer", "commentsEntryPointRenderer", "likeButtonRenderer", "toggleButtonRenderer", "likeCountEntity", "segmentedLikeDislikeButtonViewModel"))
                            fragments.put(JSONObject().put(key, shape(value.opt(key))))
                        visit(value.opt(key), depth + 1)
                    }
                    is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), depth + 1)
                }
            }
            visit(root)
            events.put(JSONObject().put("endpoint", chain.request().url.encodedPath.substringAfterLast('/'))
                .put("http", response.code).put("account", chain.request().header("Authorization") != null)
                .put("schema", JSONObject(keys as Map<*, *>)).put("fragments", fragments))
            response
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        try {
            client.read { context, transport ->
                events.put(JSONObject().put("signedIn", context.signedIn))
                for (session in listOf(context, context.anonymous()).distinct()) {
                    fun record(operation: String, block: () -> JSONObject) {
                        events.put(runCatching { block().put("operation", operation).put("account", session.signedIn) }
                            .getOrElse { failure ->
                                failures += "$operation:${session.signedIn}:${failure.javaClass.simpleName}"
                                JSONObject().put("operation", operation).put("account", session.signedIn)
                                    .put("failure", failure.javaClass.simpleName).apply {
                                        (failure as? com.hhst.youtubelite.data.media.MediaException)?.failure?.let { error ->
                                            put("kind", error.kind.name)
                                            put("code", error.code)
                                            put("status", error.status)
                                            put("retryAfterMs", error.retryAfterMs)
                                        }
                                    }
                            })
                    }
                    record("channel") {
                        val page = loadChannel("UCsRM0YB_dabtEPGPTKo-gcw", session, http = transport).requireValue()
                        check(!page.title.isNullOrBlank() && !page.thumbnail.isNullOrBlank() && !page.description.isNullOrBlank())
                        JSONObject().put("title", !page.title.isNullOrBlank()).put("avatar", !page.thumbnail.isNullOrBlank())
                            .put("description", !page.description.isNullOrBlank()).put("items", page.page.items.size)
                    }
                    record("comments") {
                        val page = loadComments("aqz-KE-bpKQ", session, http = transport).requireValue()
                        check(page.comments.isNotEmpty() && page.nextKey != null && page.sortOptions.isNotEmpty())
                        JSONObject().put("items", page.comments.size).put("next", page.nextKey != null)
                            .put("disabled", page.turnedOff)
                    }
                    record("short-counts") {
                        val page = loadWatch("FEZS601QSVE", session, http = transport).requireValue()
                        check(!page.likeCount.isNullOrBlank())
                        JSONObject().put("likes", !page.likeCount.isNullOrBlank()).put("comments", !page.commentCount.isNullOrBlank())
                    }
                    if (session.signedIn) {
                        record("subscriptions") {
                            JSONObject().put("items", loadSubscribedChannels(session, transport).requireValue().size)
                        }
                        record("subscription-browse") {
                            val page = loadBrowse("FEchannels", session, http = transport).requireValue()
                            JSONObject().put("items", page.items.size).put("channels", page.items.count { it.kind == "channel" })
                        }
                    }
                }
            }
            assertTrue("Read contracts failed: ${failures.joinToString()}", failures.isEmpty())
        } finally {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "content-read-contracts.json")
                .writeText(events.toString(2))
        }
    }
}
