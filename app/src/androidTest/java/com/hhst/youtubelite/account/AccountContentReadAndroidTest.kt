package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaException
import com.hhst.youtubelite.data.media.MediaPlaylistRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MediaShortsRepository
import com.hhst.youtubelite.data.media.MediaWatchRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class AccountContentReadAndroidTest {
    @Test fun accountReadsExposeMetadataAndSystemPlaylists() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("accountReads") == "1")
        if (!MediaSession.signedIn() && arguments.getString("awaitSignIn") == "1") {
            val service = com.hhst.youtubelite.account.data.MediaSignInService(GlobalContext.get().get(),
                com.hhst.youtubelite.data.media.PreferencesAuthStore(instrumentation.targetContext))
            val code = service.loadCode()
            File(instrumentation.targetContext.filesDir, "device-authorization-request.json").writeText(
                JSONObject().put("url", code.verificationUrl).put("code", code.userCode).toString())
            service.complete(service.poll(code))
        }
        assertTrue("Device authorization has not completed", MediaSession.signedIn())
        val events = JSONArray()
        val failures = mutableListOf<String>()
        fun record(value: JSONObject) = synchronized(events) { events.put(value); Unit }
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            val root = runCatching { JSONObject(response.peekBody(4L * 1024 * 1024).string()) }.getOrNull()
            val counts = linkedMapOf<String, Int>()
            val names = setOf("videoPrimaryInfoRenderer", "videoMetadataRenderer", "musicWatchMetadataRenderer",
                "videoOwnerRenderer", "playlistVideoListRenderer", "playlistVideoRenderer", "entityMetadataRenderer", "tileRenderer")
            fun visit(value: Any?, depth: Int = 0) {
                if (depth > 40) return
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        if (key in names) counts[key] = (counts[key] ?: 0) + 1
                        visit(value.opt(key), depth + 1)
                    }
                    is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), depth + 1)
                }
            }
            visit(root)
            record(JSONObject().put("request", path).put("http", response.code)
                .put("renderers", JSONObject(counts as Map<*, *>))
                .put("errorStatus", root?.optJSONObject("error")?.optString("status")
                    ?.takeIf { it.matches(Regex("[A-Z_]{1,48}")) }))
            response
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        suspend fun check(name: String, block: suspend () -> JSONObject) {
            try {
                record(block().put("operation", name).put("ok", true))
            } catch (error: Exception) {
                val failure = (error as? MediaException)?.failure
                record(JSONObject().put("operation", name).put("ok", false)
                    .put("failure", failure?.kind?.name ?: error.javaClass.simpleName)
                    .put("code", failure?.code?.take(96)))
                failures += name
            }
        }
        try {
            for (id in listOf("GjjK6yD5I9I", "FEZS601QSVE")) {
                check("watch-$id") {
                    val page = MediaWatchRepository(client).watch(id)
                    check(page.video.title.isNotBlank() && page.video.channel.isNotBlank())
                    JSONObject().put("related", page.related.size).put("hasNext", page.nextKey != null)
                }
                check("short-$id") {
                    val detail = requireNotNull(MediaShortsRepository(client).hydrate(id))
                    check(detail.item.title.isNotBlank() && detail.item.author.isNotBlank())
                    JSONObject().put("hasChannelId", detail.item.channelId.isNotBlank())
                }
            }
            for (id in listOf("WL", "LL")) check("playlist-$id") {
                val page = requireNotNull(MediaPlaylistRepository(client).playlist(id))
                check(page.summary.title.isNotBlank())
                JSONObject().put("items", page.entries.size).put("hasNext", page.nextKey != null)
            }
            assertTrue("Failed account reads: ${failures.joinToString()}", failures.isEmpty())
        } finally {
            File(instrumentation.targetContext.filesDir, "account-read-verification.json").writeText(events.toString(2))
        }
    }
}
