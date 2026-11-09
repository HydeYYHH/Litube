package com.hhst.youtubelite.data.media

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.search.domain.SearchFilters
import com.hhst.youtubelite.search.domain.SearchUploadDate
import com.liskovsoft.youtubeapi.videoinfo.V2.BrowsePage
import com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.loadHome
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSearch
import com.liskovsoft.youtubeapi.videoinfo.V2.searchPage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opted-in public reads. No account, images, player or mutation endpoints. */
class MscLiveCatalogAndroidTest {
    @Test fun publicCatalogChainsMeasureColdAndOpenedReadsWithoutAccountWrites() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("mscLiveCatalog") == "1")
        val trials = (arguments.getString("liveCatalogTrials")?.toIntOrNull() ?: 2).coerceIn(1, 2)
        val query = "blender open movie"
        // Separate epoch so MediaHomeRepository cannot remember this experiment's visitor.
        val appGeneration = MediaSession.snapshot().generation
        val identity = AtomicReference(RequestContext(language = "en", region = "US", generation = -700_001))
        val base = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
        val probe = MscLiveNetworkProbe(base,
            arguments.getString("liveProxyHost")?.takeIf(String::isNotBlank),
            arguments.getString("liveProxyPort")?.toIntOrNull() ?: 0) { identity.get() }
        val observations = JSONArray()
        val comparisons = JSONArray()
        val output = JSONObject().put("timeUtc", java.time.Instant.now().toString())
            .put("experimentVersion", 2).put("trials", trials).put("query", query).put("language", "en").put("region", "US")
            .put("accountReads", false).put("observations", observations).put("comparisons", comparisons)
        val invariantFailures = mutableListOf<String>()
        try {
            withTimeout(300_000) {
                val bootstrapped = observe(probe, observations, "identity.bootstrap", {
                    probe.client.read { context, http -> anonymousHomeContext(context, http) }
                }) { context -> JSONObject().put("visitorPresent", context.visitor != null)
                    .put("anonymousCookiePresent", context.visitorCookie != null).put("signedIn", context.signedIn) }
                bootstrapped?.let(identity::set)

                // Availability/shape first so a slow search cannot hide the Sports empty-feed regression.
                measureHomeAndExplore(probe, observations)

                // Separate diagnostic chain for server filter state; body inspection
                // is excluded from the performance comparisons below.
                inspectSearchFilters(probe, observations, query)
                repeat(trials) { iteration ->
                    val order = if (iteration % 2 == 0) listOf(true, false) else listOf(false, true)
                    val stageSamples = mutableMapOf<Boolean, List<JSONObject>>()
                    for (reuse in order) {
                        val shared = MediaSearchRepository(probe.client)
                        val start = probe.report().getJSONArray("samples").length()
                        // ThisYear removes Channels/Playlists; Videos is a separate core
                        // capability below because the app has no Videos filter enum.
                        val stages = listOf(SearchFilters(), SearchFilters(uploaded = SearchUploadDate.ThisYear))
                        for ((index, filters) in stages.withIndex()) {
                            val repository = if (reuse) shared else MediaSearchRepository(probe.client)
                            val page = observe(probe, observations, "search.$iteration.${if (reuse) "opened" else "cold"}.$index", {
                                repository.search(query, filters)
                            }) { value -> JSONObject().put("requestedType", filters.type.name)
                                .put("requestedUploadDate", filters.uploaded.name).put("videos", value.results.size)
                                .put("playlists", value.playlists.size).put("channels", value.channels.size)
                                .put("shorts", value.shorts.size).put("hasContinuation", value.nextCursor != null) }
                            if (page == null) break
                            if (page.query != query) invariantFailures += "search query changed"
                        }
                        stageSamples[reuse] = samplesSince(probe, start)
                    }
                    val opened = stageSamples[true].orEmpty()
                    val cold = stageSamples[false].orEmpty()
                    val matched = (0 until minOf(opened.size, cold.size)).filter {
                        opened[it].optString("outcome") == "success" && cold[it].optString("outcome") == "success"
                    }
                    val stages = JSONArray(matched.map { index -> JSONObject().put("stage", index)
                        .put("openedRequests", opened[index].getInt("networkExchanges"))
                        .put("coldRequests", cold[index].getInt("networkExchanges"))
                        .put("savedRequests", cold[index].getInt("networkExchanges") - opened[index].getInt("networkExchanges"))
                        .put("openedMs", opened[index].getDouble("wallMs")).put("coldMs", cold[index].getDouble("wallMs")) })
                    val openedRequests = matched.sumOf { opened[it].getInt("networkExchanges") }
                    val coldRequests = matched.sumOf { cold[it].getInt("networkExchanges") }
                    comparisons.put(JSONObject().put("kind", "search").put("trial", iteration)
                        .put("coverage", if (matched.size == 2) "complete_app_date_transition" else "partial_app_date_transition")
                        .put("expectedStages", 2).put("completedMatchedStages", matched.size).put("stages", stages)
                        .put("openedRequests", openedRequests).put("coldRequests", coldRequests)
                        .put("openedFilterMs", matched.filter { it > 0 }.sumOf { opened[it].getDouble("wallMs") })
                        .put("coldFilterMs", matched.filter { it > 0 }.sumOf { cold[it].getDouble("wallMs") })
                        .put("savedRequests", coldRequests - openedRequests)
                        .put("interpretation", "Only successful matched stages are compared; unsupported or failed later stages retain earlier evidence. Public wall time is descriptive."))
                }

                // Change profile/epoch while retaining the real visitor; both must
                // invalidate the old panel without inventing visitor data.
                val search = MediaSearchRepository(probe.client)
                val beforeProfile = identity.get()
                val seed = observe(probe, observations, "search.profile.seed", { search.search(query, SearchFilters()) }) {
                    JSONObject().put("videos", it.results.size)
                }
                if (seed != null) try {
                    identity.set(beforeProfile.copy(region = "GB", generation = beforeProfile.generation - 1))
                    observe(probe, observations, "search.profile.changed", {
                        search.search(query, SearchFilters(uploaded = SearchUploadDate.ThisYear))
                    }) { JSONObject().put("videos", it.results.size).put("expectedColdSearches", 2) }
                    val sample = lastSample(probe)
                    if (sample.optString("outcome") == "success" && cleanExchanges(sample) && sample.getInt("networkExchanges") != 2)
                        invariantFailures += "changed profile reused a previous search panel"
                } finally { identity.set(beforeProfile) }

                measureChannel(probe, observations, comparisons, trials)
                assertEquals("The isolated probe must not change the app session", appGeneration, MediaSession.snapshot().generation)
                assertTrue("Catalog semantic invariants: $invariantFailures", invariantFailures.isEmpty())
                assertTrue("No public read succeeded; inspect the persisted transport report",
                    (0 until observations.length()).any { observations.getJSONObject(it).optString("outcome") == "ok" &&
                        observations.getJSONObject(it).getJSONObject("transport").optInt("networkExchanges") > 0 })
            }
        } finally {
            output.put("invariantFailures", JSONArray(invariantFailures)).put("network", probe.report())
            DeviceEvidence.writeJson("msc-live-catalog.json", output.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("mscLiveCatalog", JSONObject().put("observations", observations.length())
                    .put("comparisons", comparisons).put("invariantFailures", JSONArray(invariantFailures)).toString())
            })
            probe.http.connectionPool.evictAll()
            probe.http.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun inspectSearchFilters(probe: MscLiveNetworkProbe, observations: JSONArray, query: String) {
        suspend fun read(label: String, params: String?) = observe(probe, observations, label, {
            probe.client.read { context, http -> loadSearch(query, context, params, http = shapeObserver(http, observations, label)).requireValue() }
        }) { page -> JSONObject().put("items", page.items.size).put("purpose", "core_capability_not_app_videos_filter").put("filters", JSONArray(page.filters.map { filter ->
            JSONObject().put("group", filter.group).put("title", filter.title).put("selected", filter.selected)
                .put("paramsDigest", digest(filter.params))
        })) }
        val first = read("search.core.default", null) ?: return
        val date = first.filters.firstOrNull { it.title.equals("This year", true) }?.params
        if (date == null) { skipped(observations, "search.core.date", "Server omitted the This year filter"); return }
        val dated = read("search.core.date", date) ?: return
        val type = dated.filters.firstOrNull { it.title.equals("Video", true) || it.title.equals("Videos", true) }?.params
        if (type == null) skipped(observations, "search.core.combined", "Server omitted the Videos filter")
        else read("search.core.combined", type)
    }

    private suspend fun measureChannel(probe: MscLiveNetworkProbe, observations: JSONArray, comparisons: JSONArray, trials: Int) {
        // Blender's UC identifier avoids adding a resolve_url lookup to every cold read.
        val channelId = "UCSMOQeBJ2RAnuFungnQOxLg"
        repeat(trials) { iteration ->
            val counts = mutableMapOf<Boolean, Int>()
            val times = mutableMapOf<Boolean, Long>()
            val order = if (iteration % 2 == 0) listOf(true, false) else listOf(false, true)
            for (reuse in order) {
                val repository = MediaChannelRepository(probe.client, MediaPostRepository(probe.client))
                val prefix = "channel.$iteration.${if (reuse) "opened" else "cold"}"
                val landing = observe(probe, observations, "$prefix.landing", { repository.channel(channelId) }) {
                    JSONObject().put("namePresent", it.profile.name.isNotBlank()).put("videos", it.videos.size)
                        .put("videosDeferred", it.videosDeferred).put("playlistsDeferred", it.playlistsDeferred)
                } ?: continue
                val videos = observe(probe, observations, "$prefix.videos", {
                    if (reuse) repository.videos(channelId, landing) else repository.videos(channelId, continuation = null)
                }) { JSONObject().put("videos", it.videos.size).put("hasContinuation", it.nextKey != null) }
                if (videos != null) {
                    val sample = lastSample(probe)
                    counts[reuse] = sample.getInt("networkExchanges")
                    times[reuse] = sample.getLong("wallMs")
                }
            }
            comparisons.put(JSONObject().put("kind", "channel.videosAfterLanding").put("trial", iteration)
                .put("openedRequests", counts[true]).put("coldRequests", counts[false])
                .put("openedMs", times[true]).put("coldMs", times[false])
                .put("savedRequests", counts[false]?.let { cold -> counts[true]?.let { cold - it } }))
        }
    }

    private suspend fun measureHomeAndExplore(probe: MscLiveNetworkProbe, observations: JSONArray) {
        val home = MediaHomeRepository(probe.client)
        val first = observe(probe, observations, "home.All.initial", { home.loadHome("All") }) { homeSummary(it) }
        first?.nextKey?.let { cursor ->
            observe(probe, observations, "home.All.continuation", { home.loadHome("All", cursor) }) {
                homeSummary(it).put("newCards", cardKeys(it.items).minus(cardKeys(first.items)).size)
                    .put("cursorAdvanced", it.nextKey == null || it.nextKey != cursor)
            }
        } ?: skipped(observations, "home.All.continuation", "No continuation was returned")
        for (section in listOf(HomeSection.GAMING, HomeSection.SPORTS, HomeSection.NEWS)) {
            val name = section.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            // Empty and partial categories are availability evidence, not transport failures.
            observe(probe, observations, "home.$name.initial", { home.loadHome(name) }) { homeSummary(it) }
            observe(probe, observations, "home.$name.coreShape", {
                probe.client.read { context, http ->
                    loadHome(section, context, http = shapeObserver(http, observations, "home.$name.coreShape")).requireValue()
                }
            }) { browseSummary(it) }
        }
        val explore = MediaExploreRepository(probe.client)
        val page = observe(probe, observations, "explore.Sports.initial", { explore.explore(ExploreCategory.Sports) }) {
            JSONObject().put("videos", it.videos.size).put("playlists", it.playlists.size)
                .put("sections", it.sections.size).put("hasContinuation", it.nextKey != null)
        } ?: return
        val pageCursor = page.nextKey
        val shelf = page.sections.firstOrNull { it.nextKey != null }
        if (pageCursor != null) {
            observe(probe, observations, "explore.Sports.pageContinuation", {
                explore.exploreNext(ExploreCategory.Sports, pageCursor)
            }) { next -> JSONObject().put("videos", next.videos.size).put("playlists", next.playlists.size)
                .put("newVideos", next.videos.map { it.id }.toSet().minus(page.videos.map { it.id }.toSet()).size)
                .put("cursorAdvanced", next.nextKey == null || next.nextKey != pageCursor) }
        } else if (shelf != null) {
            val cursor = requireNotNull(shelf.nextKey)
            observe(probe, observations, "explore.Sports.shelfContinuation", {
                explore.exploreShelf(ExploreCategory.Sports, cursor)
            }) { next -> JSONObject().put("videos", next.videos.size).put("playlists", next.playlists.size)
                .put("newVideos", next.videos.map { it.id }.toSet().minus(shelf.videos.map { it.id }.toSet()).size)
                .put("cursorAdvanced", next.nextKey == null || next.nextKey != cursor) }
        } else skipped(observations, "explore.Sports.continuation", "No page or shelf continuation was returned")
    }

    private fun shapeObserver(http: HttpClient, observations: JSONArray, label: String) = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply = http.execute(request).also { reply ->
            // Core already owns this string; only diagnostic samples parse it again —
            // measured reads retain the production parsing path.
            val root = runCatching { JSONObject(reply.body) }.getOrNull()
            val renderers = sortedMapOf<String, Int>()
            fun visit(value: Any?) {
                when (value) {
                    is JSONObject -> for (key in value.keys()) {
                        if (key.endsWith("Renderer") || key.endsWith("ViewModel")) renderers[key] = (renderers[key] ?: 0) + 1
                        visit(value.opt(key))
                    }
                    is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                }
            }
            visit(root)
            synchronized(observations) {
                observations.put(JSONObject().put("label", "$label.responseShape").put("outcome", "shape")
                    .put("status", reply.status).put("jsonObject", root != null).put("renderers", JSONObject(renderers))
                    .put("filters", JSONArray(if (root == null) emptyList<JSONObject>() else searchPage(reply.body).filters.map {
                        JSONObject().put("group", it.group).put("title", it.title).put("selected", it.selected)
                            .put("paramsDigest", digest(it.params))
                    })))
            }
        }
    }

    private suspend fun <T : Any> observe(probe: MscLiveNetworkProbe, observations: JSONArray, label: String,
        block: suspend () -> T, summary: (T) -> JSONObject): T? {
        val item = JSONObject().put("label", label)
        observations.put(item)
        return try {
            val value = probe.sample(label, block)
            item.put("outcome", "ok").put("value", summary(value))
            value
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            item.put("outcome", "failed").put("errorClass", error.javaClass.simpleName)
            if (error is MediaException) item.put("failureKind", error.failure.kind.name)
                .put("failureCode", error.failure.code).put("httpStatus", error.failure.status)
            null
        } finally { item.put("transport", lastSample(probe)) }
    }

    private fun homeSummary(page: com.hhst.youtubelite.home.domain.HomePage) = JSONObject()
        .put("items", page.items.size).put("kinds", JSONObject(page.items.groupingBy { it.javaClass.simpleName }.eachCount()))
        .put("partialFailure", page.partialFailure).put("catalog", page.catalog?.name)
        .put("hasContinuation", page.nextKey != null)

    private fun browseSummary(page: BrowsePage) = JSONObject().put("items", page.items.size)
        .put("kinds", JSONObject(page.items.groupingBy { it.kind }.eachCount())).put("hasContinuation", page.nextKey != null)
        .put("messageCount", page.messages.size).put("groups", JSONArray(page.groups.map { group ->
            JSONObject().put("title", group.title).put("items", group.items.size)
                .put("kinds", JSONObject(group.items.groupingBy { it.kind }.eachCount())).put("hasContinuation", group.nextKey != null)
        }))

    private fun cardKeys(items: List<FeedItem>) = items.map { item -> when (item) {
        is FeedItem.Video -> "video:${item.id}"
        is FeedItem.Playlist -> "playlist:${item.summary.id}"
        else -> "${item.javaClass.simpleName}:${item.id}"
    } }.toSet()

    private fun lastSample(probe: MscLiveNetworkProbe): JSONObject = probe.report().getJSONArray("samples").let {
        it.getJSONObject(it.length() - 1)
    }

    private fun samplesSince(probe: MscLiveNetworkProbe, start: Int): List<JSONObject> =
        probe.report().getJSONArray("samples").let { samples -> (start until samples.length()).map(samples::getJSONObject) }

    private fun cleanExchanges(sample: JSONObject): Boolean = sample.getJSONArray("http").let { http ->
        sample.optInt("logicalCalls") == http.length() && (0 until http.length()).all { http.getJSONObject(it).optInt("status") in 200..299 }
    }

    private fun skipped(observations: JSONArray, label: String, reason: String) {
        observations.put(JSONObject().put("label", label).put("outcome", "skipped").put("reason", reason))
    }

    private fun digest(value: String?): String? = value?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
}
