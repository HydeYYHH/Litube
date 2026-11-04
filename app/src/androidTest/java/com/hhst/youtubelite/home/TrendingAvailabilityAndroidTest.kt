package com.hhst.youtubelite.home

import android.os.Bundle
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class TrendingAvailabilityAndroidTest {
    @Test fun recordsGeneralTrendingClientsAndCategoryContinuationLimits() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("trendingAvailability") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val before = MediaSession.snapshot()
        val rows = JSONArray()
        fun record(row: JSONObject) {
            rows.put(row)
            instrumentation.sendStatus(2, Bundle().apply { putString("trendingProbe", row.toString()) })
        }
        try {
            withTimeout(240_000) {
                GlobalContext.get().get<MediaClient>().read { _, http ->
                    val context = RequestContext(visitor = before.visitor, visitorCookie = before.visitorCookie)
                    fun send(query: PlayerQuery, label: String) {
                        val reply = http.execute(HttpRequest("POST", query.url, query.headers, query.body))
                        val page = browsePage(reply.body)
                        record(JSONObject().put("name", label).put("http", reply.status).put("count", page.items.size)
                            .put("hasContinuation", page.nextKey != null).put("messages", JSONArray(page.messages))
                            .put("groups", JSONArray(page.groups.map { it.title }))
                            .put("apiError", runCatching { JSONObject(reply.body).optJSONObject("error")?.optString("message") }.getOrNull()))
                    }
                    for (region in listOf("US", "GB", "HK", "TW", "JP", "IN", "BR")) {
                        for (params in listOf<String?>(null, "6gQJRkVleHBsb3Jl", "4gIOGgxtb3N0X3BvcHVsYXI%3D")) {
                            send(browseQuery("FEtrending", context.copy(region = region), params), "WEB_${region}_${params ?: "plain"}")
                        }
                    }
                    val agent = playerQuery(PlayerClient.WEB, "aqz-KE-bpKQ", null).headers.getValue("User-Agent")
                    for (host in listOf("www.youtube.com", "m.youtube.com")) {
                        val browserAgent = if (host.startsWith("m."))
                            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36" else agent
                        val reply = http.execute(HttpRequest("GET", "https://$host/feed/trending?hl=en&gl=US", mapOf("User-Agent" to browserAgent)))
                        val initial = Regex("ytInitialData\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL).find(reply.body)?.groupValues?.get(1)
                        val page = initial?.let(::browsePage)
                        record(JSONObject().put("name", host).put("http", reply.status).put("count", page?.items?.size)
                            .put("groups", JSONArray(page?.groups?.map { it.title }.orEmpty()))
                            .put("tabs", JSONArray(page?.tabs?.map { it.title }.orEmpty()))
                            .put("title", Regex("<title>(.*?)</title>").find(reply.body)?.groupValues?.get(1))
                            .put("canonical", Regex("<link[^>]*rel=\"canonical\"[^>]*href=\"([^\"]*)\"").find(reply.body)?.groupValues?.get(1)))
                    }
                    val official = http.execute(HttpRequest("GET", "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&regionCode=US&maxResults=5"))
                    record(JSONObject().put("name", "data_api_without_key").put("http", official.status)
                        .put("error", runCatching { JSONObject(official.body).getJSONObject("error").optString("status") }.getOrNull()))
                    for (section in listOf(HomeSection.GAMING, HomeSection.SPORTS, HomeSection.NEWS)) {
                        val shelfName = when (section) { HomeSection.GAMING -> "Trending videos"; HomeSection.SPORTS -> "Trending"; else -> "Top news" }
                        val first = loadHome(section, context, http = http)
                        val group = first.value?.groups?.firstOrNull { it.title == shelfName }
                        var cursor = group?.nextKey
                        val ids = group?.items?.filter { it.kind == "video" }?.map { it.id }?.toMutableSet() ?: mutableSetOf()
                        record(JSONObject().put("name", "${section.name}_shelf_0").put("ok", first.ok)
                            .put("uniqueVideos", ids.size).put("hasContinuation", cursor != null))
                        for (index in 1..4) {
                            val key = cursor ?: break
                            val next = loadHome(section, context, key, http)
                            next.value?.items?.filter { it.kind == "video" }?.forEach { ids += it.id }
                            cursor = next.value?.nextKey?.takeUnless { it == key }
                            record(JSONObject().put("name", "${section.name}_shelf_$index").put("ok", next.ok)
                                .put("uniqueVideos", ids.size).put("count", next.value?.items?.size).put("hasContinuation", cursor != null))
                        }
                    }
                }
            }
            assertEquals(before, MediaSession.snapshot())
        } finally {
            File(instrumentation.targetContext.filesDir, "trending-regions.json").writeText(
                JSONObject().put("timeUtc", java.time.Instant.now().toString()).put("observations", rows).toString(2))
        }
    }
}
