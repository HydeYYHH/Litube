package com.hhst.youtubelite.extractor

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Records anonymous catalog availability without modifying the active session. */
class ContentCatalogAndroidTest {
    @Test fun recordsSupplementaryPublicLists() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("contentCatalog") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val before = MediaSession.snapshot()
        val rows = JSONArray()
        try {
            withTimeout(90_000) {
                GlobalContext.get().get<MediaClient>().read { _, http ->
                    val anonymous = RequestContext(visitor = before.visitor, visitorCookie = before.visitorCookie)
                    val transport = object : HttpClient {
                        override fun execute(request: HttpRequest): HttpReply {
                            assertFalse(request.headers.keys.any {
                                it.equals("Authorization", true) || it.equals("X-Goog-AuthUser", true)
                            })
                            return http.execute(request)
                        }
                    }
                    fun <T> record(name: String, result: ReadResult<T>, describe: (T) -> JSONObject) {
                        val row = JSONObject().put("name", name).put("ok", result.ok)
                        result.error?.let { error ->
                            row.put("error", JSONObject().put("kind", error.kind.name).put("code", error.code)
                                .put("http", error.status))
                        }
                        result.value?.let { row.put("page", describe(it)) }
                        rows.put(row)
                        instrumentation.sendStatus(2, Bundle().apply { putString("contentCatalogDetails", row.toString()) })
                    }
                    for (section in listOf(HomeSection.MUSIC, HomeSection.SPORTS)) {
                        record("fresh_${section.name}", loadHome(section, RequestContext(), http = transport)) { page ->
                            JSONObject().put("count", page.items.size).put("hasContinuation", page.nextKey != null)
                                .put("shelves", JSONArray(page.groups.map { it.title }))
                        }
                    }
                    val channelId = "UCSMOQeBJ2RAnuFungnQOxLg"
                    val channel = loadChannel(channelId, anonymous, http = transport)
                    record("channel_for_posts", channel) { JSONObject().put("tabs", it.page.tabs.size) }
                    val postsTab = channel.value?.page?.tabs?.firstOrNull { it.title == "Posts" }
                    if (postsTab != null) {
                        record("channel_posts", loadChannelPosts(channelId, anonymous, postsTab.params, http = transport)) { page ->
                            JSONObject().put("count", page.posts.size).put("hasContinuation", page.nextKey != null)
                                .put("withText", page.posts.count { !it.text.isNullOrBlank() })
                                .put("withImages", page.posts.count { it.images.isNotEmpty() })
                        }
                    }
                    record("channel_rss", loadRssFeed(listOf(channelId), transport)) { feed ->
                        JSONObject().put("count", feed.videos.size).put("failures", JSONObject(feed.failures.mapValues { (_, error) ->
                            JSONObject().put("kind", error.kind.name).put("code", error.code).put("http", error.status)
                        }))
                    }
                }
            }
            assertEquals("Stored session changed during public reads", before, MediaSession.snapshot())
        } finally {
            File(instrumentation.targetContext.filesDir, "content-catalog-details.json").writeText(
                JSONObject().put("timeUtc", java.time.Instant.now().toString()).put("observations", rows).toString(2)
            )
        }
    }

    @Test fun recordsPublicListsAndTheirShelves() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("contentCatalog") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val before = MediaSession.snapshot()
        val rows = JSONArray()

        fun items(values: List<MediaItem>) = JSONObject()
            .put("count", values.size)
            .put("kinds", JSONObject(values.groupingBy { it.kind }.eachCount()))
            .put("withTitle", values.count { !it.title.isNullOrBlank() })
            .put("withAuthor", values.count { !it.author.isNullOrBlank() })
            .put("withThumbnail", values.count { !it.thumbnail.isNullOrBlank() })
            .put("samples", JSONArray(values.take(5).map { item ->
                JSONObject().put("kind", item.kind).put("id", item.id).put("title", item.title)
                    .put("author", item.author).put("views", item.viewCount).put("published", item.published)
            }))

        fun browse(page: BrowsePage) = items(page.items)
            .put("hasContinuation", page.nextKey != null)
            .put("messages", JSONArray(page.messages))
            .put("groups", JSONArray(page.groups.map { group ->
                items(group.items).put("title", group.title).put("hasContinuation", group.nextKey != null)
            }))

            .put("tabs", JSONArray(page.tabs.map { tab ->
                JSONObject().put("title", tab.title).put("browseId", tab.browseId)
                    .put("hasParams", tab.params != null).put("selected", tab.selected)
            }))

        fun playlist(page: PlaylistPage) = items(page.videos.map { video ->
            MediaItem("video", video.videoId, video.title, video.author, video.thumbnail, video.length)
        }).put("playlistTitle", page.title).put("owner", page.owner)
            .put("hasContinuation", page.nextKey != null)
            .put("indexes", JSONArray(page.videos.take(5).map { it.index }))

        try {
            withTimeout(240_000) {
                GlobalContext.get().get<MediaClient>().read { _, http ->
                    val anonymous = RequestContext(visitor = before.visitor, visitorCookie = before.visitorCookie)
                    assertFalse(anonymous.signedIn)

                    fun <T> observe(name: String, read: (HttpClient) -> ReadResult<T>, describe: (T) -> JSONObject): T? {
                        val exchanges = JSONArray()
                        val recording = object : HttpClient {
                            override fun execute(request: HttpRequest): HttpReply {
                                assertFalse("Authenticated request in anonymous probe", request.headers.keys.any {
                                    it.equals("Authorization", true) || it.equals("X-Goog-AuthUser", true)
                                })
                                return http.execute(request).also { reply ->
                                    val root = runCatching { JSONObject(reply.body) }.getOrNull()
                                    val body = runCatching { JSONObject(request.body) }.getOrNull()
                                    exchanges.put(JSONObject().put("http", reply.status)
                                        .put("browseId", body?.optString("browseId"))
                                        .put("hasParams", body?.has("params") == true)
                                        .put("apiError", root?.optJSONObject("error")?.optString("message")))
                                }
                            }
                        }
                        val started = SystemClock.elapsedRealtime()
                        val result = read(recording)
                        val row = JSONObject().put("name", name).put("ok", result.ok)
                            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("exchanges", exchanges)
                        result.error?.let { error ->
                            row.put("error", JSONObject().put("kind", error.kind.name).put("code", error.code)
                                .put("http", error.status))
                        }
                        result.value?.let { row.put("page", describe(it)) }
                        rows.put(row)
                        instrumentation.sendStatus(2, Bundle().apply { putString("contentCatalog", row.toString()) })
                        return result.value
                    }

                    for (section in HomeSection.entries) {
                        val page = observe("section_${section.name}", { loadHome(section, anonymous, http = it) }, ::browse)
                        val next = if (page?.nextKey != null) {
                            observe("next_${section.name}", { loadHome(section, anonymous, page.nextKey, it) }, ::browse)
                        } else null
                        if (section == HomeSection.MUSIC) {
                            val regionChart = page?.groups?.firstOrNull { it.title == "Top 20 music videos in your region" }
                                ?.items?.firstOrNull { it.kind == "playlist" }
                            val charts = next?.groups?.firstOrNull { it.title == "Top charts" }
                                ?.items?.filter { it.kind == "playlist" }?.take(3).orEmpty()
                            for (chart in listOfNotNull(regionChart) + charts) {
                                observe("chart_${chart.title ?: chart.id}", { loadPlaylist(chart.id, anonymous, http = it) }, ::playlist)
                            }
                        }
                        if (section in setOf(HomeSection.SPORTS, HomeSection.NEWS)) {
                            page?.groups?.firstOrNull { it.nextKey != null }?.let { group ->
                                observe("shelf_${section.name}_${group.title}", {
                                    loadHome(section, anonymous, group.nextKey, it)
                                }, ::browse)
                            }
                        }
                    }

                    fun directTvBrowse(id: String, context: RequestContext, transport: HttpClient): ReadResult<BrowsePage> {
                        val template = homeQuery(HomeSection.TRENDING, context)
                        val body = JSONObject(template.body).apply { put("browseId", id); remove("params") }
                        val reply = transport.execute(HttpRequest("POST", template.url, template.headers, body.toString()))
                        return if (reply.status in 200..299) ReadResult(browsePage(reply.body))
                        else ReadResult(error = RequestError(RequestFailure.HTTP, "http_${reply.status}", reply.status))
                    }

                    for (id in listOf("FEtrending", "FEexplore", "FEcharts")) {
                        observe("direct_$id", { directTvBrowse(id, anonymous, it) }, ::browse)
                    }
                    observe("trending_HK", { loadHome(HomeSection.TRENDING, anonymous.copy(region = "HK"), http = it) }, ::browse)
                    observe("search", { loadSearch("blender open movie", anonymous, http = it) }) { page ->
                        items(page.items).put("hasContinuation", page.nextKey != null)
                    }
                    val channelId = "UCSMOQeBJ2RAnuFungnQOxLg"
                    val channel = observe("channel", { loadChannel(channelId, anonymous, http = it) }) { page ->
                        browse(page.page).put("channelTitle", page.title)
                    }
                    for (tab in channel?.page?.tabs.orEmpty().filterNot { it.selected }) {
                        observe("channel_tab_${tab.title}", {
                            loadChannel(tab.browseId ?: channelId, anonymous, params = tab.params, http = it)
                        }) { browse(it.page).put("channelTitle", it.title) }
                    }
                    observe("playlist", { loadPlaylist("PL2JtvykrieUxaLXkeuXRHb-pJB9XpVWtd", anonymous, http = it) }, ::playlist)
                    observe("channel_rss", { loadRssFeed(listOf(channelId), it) }) { feed ->
                        JSONObject().put("count", feed.videos.size).put("failedChannels", feed.failures.size)
                    }
                    observe("watch_related", { loadWatch("aqz-KE-bpKQ", anonymous, http = it) }) { page ->
                        items(page.related).put("hasContinuation", page.nextKey != null)
                    }
                    for (section in LibrarySection.entries) {
                        val result = observe("library_${section.name}", { loadLibrary(section, anonymous, http = it) }, ::browse)
                        assertEquals(null, result)
                    }
                    val notices = observe("notifications", { loadNotices(anonymous, http = it) }) { page ->
                        JSONObject().put("count", page.notices.size)
                    }
                    assertEquals(null, notices)
                }
            }
            assertEquals("Stored session changed during public reads", before, MediaSession.snapshot())
            assertTrue(rows.length() >= HomeSection.entries.size + LibrarySection.entries.size)
        } finally {
            val output = JSONObject().put("timeUtc", java.time.Instant.now().toString())
                .put("language", "en").put("region", "US").put("signedIn", false)
                .put("hadStoredVisitor", !before.visitor.isNullOrBlank()).put("observations", rows)
            File(instrumentation.targetContext.filesDir, "content-catalog.json").writeText(output.toString(2))
        }
    }
}
