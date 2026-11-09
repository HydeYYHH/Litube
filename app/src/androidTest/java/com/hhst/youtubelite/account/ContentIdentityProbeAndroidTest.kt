package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.*
import com.hhst.youtubelite.search.domain.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.koin.core.context.GlobalContext
import java.io.File

/** Opt-in public content read probe; never changes playback or account state. */
class ContentIdentityProbeAndroidTest {
    @Test fun searchWatchAndChannelIdentity(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("identityCollection") == "1")
        val captures = JSONArray()
        val names = setOf("tileRenderer", "videoRenderer", "lockupViewModel", "videoMetadataRenderer",
            "musicWatchMetadataRenderer", "videoOwnerRenderer", "channelMetadataRenderer", "channelHeaderRenderer", "pageHeaderViewModel",
            "videoDetails", "playabilityStatus", "playerMicroformatRenderer")
        fun clean(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { key ->
                if (key !in setOf("trackingParams", "clickTrackingParams", "visitorData", "feedbackToken", "serializedShareEntity", "loggingDirectives",
                        "menu", "subscribeButton", "subscriptionButton", "onLongPressCommand", "onFocusCommand", "watchEndpointSupportedOnesieConfig", "playerParams"))
                    put(key, clean(value.opt(key)))
            } }
            is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(clean(value.opt(i))) }
            else -> value
        }
        fun select(value: Any?, found: JSONObject) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    if (key in names) {
                        val entries = found.optJSONArray(key) ?: JSONArray().also { found.put(key, it) }
                        if (entries.length() < 6) entries.put(clean(value.opt(key)))
                    }
                    else select(value.opt(key), found)
                }
                is JSONArray -> for (i in 0 until value.length()) select(value.opt(i), found)
            }
        }
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            check(path in setOf("search", "next", "player", "browse", "resolve_url", "token"))
            chain.proceed(chain.request()).also { response ->
                if (path != "token") runCatching {
                    val found = JSONObject()
                    select(JSONObject(response.peekBody(4L * 1024 * 1024).string()), found)
                    synchronized(captures) { captures.put(JSONObject().put("endpoint", path).put("renderers", found)) }
                }
            }
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val report = JSONObject()
        try {
            val search = MediaSearchRepository(client)
            val page = search.search(args.getString("query") ?: "know my name skye", SearchFilters())
            assertTrue("Search authors must survive unlinked TV metadata rows", page.results.take(12).all { it.channel.isNotBlank() })
            report.put("search", JSONArray(page.results.take(12).map { row -> JSONObject()
                .put("id", row.id).put("title", row.title).put("author", row.channel).put("channelId", row.channelId)
                .put("age", row.age).put("duration", row.duration) }))
            val watch = MediaWatchRepository(client)
            val samples = JSONArray()
            for (row in page.results.filter { it.id in setOf("B9ZK6K9MfZQ", "ObmBoBKUW2w") }) {
                val details = watch.watch(row.id).video
                val result = JSONObject().put("id", row.id).put("author", details.channel).put("channelId", details.channelId)
                    .put("age", details.age)
                assertNotEquals("A transport label is not an artist", "Channel", details.channel)
                if (row.id == "ObmBoBKUW2w") {
                    val complete = search.metadata(row)
                    assertEquals("3:41", complete.duration)
                    assertTrue(complete.age.isNotBlank())
                    result.put("searchDuration", complete.duration).put("searchAge", complete.age)
                    val channel = MediaChannelRepository(client, MediaPostRepository(client)).channel(details.channelId)
                    val albums = channel.homeShelves.flatMap { it.playlists }
                    assertTrue("Topic albums are real channel content", albums.isNotEmpty())
                    val album = albums.firstOrNull { it.title.contains("Know My Name") } ?: albums.first()
                    val albumPage = MediaPlaylistRepository(client).playlist(album.id)
                    assertTrue("The album must open playable entries", albumPage?.entries?.isNotEmpty() == true)
                    result.put("albumCount", albums.size).put("albumEntries", albumPage?.entries?.size)
                }
                val publicDetails = client.read { context, transport ->
                    com.liskovsoft.youtubeapi.videoinfo.V2.loadPlayerDetails(row.id, context.anonymous(), transport).value }
                result.put("publicAuthor", publicDetails?.author).put("publicTitle", publicDetails?.title)
                    .put("publicDuration", publicDetails?.durationSeconds)
                samples.put(result)
                if (details.channelId.isNotBlank()) {
                    val core = client.read { context, transport -> com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel(details.channelId, context, http = transport).requireValue() }
                    result.put("channelTitle", core.title).put("channelItems", core.page.items.size)
                        .put("tabs", JSONArray(core.page.tabs.map { it.title }))
                        .put("items", JSONArray(core.page.items.map { JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title) }))
                        .put("groups", JSONArray(core.page.groups.map { JSONObject().put("title", it.title).put("count", it.items.size) }))
                }
            }
            report.put("watch", samples)
        } finally {
            report.put("captures", captures)
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "content-identity.json").writeText(report.toString(2))
        }
    }
}
