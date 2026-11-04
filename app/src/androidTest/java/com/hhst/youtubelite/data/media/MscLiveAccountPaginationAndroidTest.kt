package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.BrowsePage
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.LibrarySection
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.ledgerScope
import com.liskovsoft.youtubeapi.videoinfo.V2.loadLibrary
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSubscriptionsBrowse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Existing OAuth session only; six bounded browse reads at most, with no account data exported. */
class MscLiveAccountPaginationAndroidTest {
    @Test fun distinguishPlaylistAndSubscriptionPaginationFromRefreshCursors() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLivePagination") == "1")
        val initial = MediaSession.snapshot()
        val initialScope = initial.ledgerScope()
        val operations = JSONArray()
        val output = JSONObject().put("experiment", "account_pagination_diagnostic").put("readOnly", true)
            .put("maxContinuationsPerLane", 2).put("operations", operations)
            .put("timeUtc", java.time.Instant.now().toString())
            .put("identityExported", false).put("bodyStored", false)
            .put("mapping", "core_browse_with_app_playlist_and_all_subscriptions_id_mapping")
            .put("responseShapeAnalysisOutsideNetworkSample", true)
        if (!initial.signedIn || initial.accessToken.isNullOrBlank()) {
            output.put("outcome", "oauth_session_required")
            DeviceEvidence.writeJson(FILE_NAME, output.toString(2))
            assumeTrue("The diagnostic requires the existing OAuth session", false)
            return@runBlocking
        }
        val probe = MscLiveNetworkProbe(GlobalContext.get().get<OkHttpClient>(),
            args.getString("liveProxyHost")?.takeIf(String::isNotBlank),
            args.getString("liveProxyPort")?.toIntOrNull() ?: 7890, allowSignedIn = true) {
            MediaSession.freshContext().also { context ->
                if (!context.signedIn || context.accessToken.isNullOrBlank() || context.ledgerScope() != initialScope)
                    throw MediaException(RequestError(RequestFailure.AUTH, "live_account_profile_changed"))
            }
        }
        fun save() {
            output.put("network", probe.report()).put("profileChanged", MediaSession.snapshot().ledgerScope() != initialScope)
            DeviceEvidence.writeJson(FILE_NAME, output.toString(2))
        }
        try {
            withTimeout(180_000) {
                for (section in listOf(LibrarySection.PLAYLISTS, LibrarySection.SUBSCRIPTIONS)) {
                    val lane = if (section == LibrarySection.PLAYLISTS) "playlists" else "subscriptions"
                    val observed = PaginationCounts()
                    var cursor: String? = null
                    var coreHttpCalls = 0
                    for (index in 0..2) {
                        val operation = JSONObject().put("lane", lane).put("page", index)
                            .put("continuationRequest", cursor != null)
                        operations.put(operation)
                        // Retain only the body the normal transport already read; shape parsing
                        // after sample() cannot inflate the measured core-read time.
                        var responseBody: String? = null
                        try {
                            val page = probe.sample("$lane.page_$index") {
                                probe.client.read { context, http ->
                                    val inspecting = object : HttpClient {
                                        override fun execute(request: HttpRequest): HttpReply {
                                            check(++coreHttpCalls <= 3) { "pagination_read_budget_exceeded" }
                                            return http.execute(request).also { reply ->
                                                if (reply.status in 200..299) responseBody = reply.body
                                            }
                                        }
                                    }
                                    // Match the production initial OAuth feed source, then the
                                    // same loadBrowse route used by its continuation reads.
                                    if (section == LibrarySection.SUBSCRIPTIONS && cursor == null)
                                        loadSubscriptionsBrowse(context, inspecting).requireValue().feed.requireValue()
                                    else loadLibrary(section, context, cursor, inspecting).requireValue()
                                }
                            }
                            check(MediaSession.snapshot().ledgerScope() == initialScope) { "pagination_profile_changed" }
                            val ids = appIds(section, page)
                            val counts = observed.record(ids, cursor, page.nextKey)
                            operation.put("outcome", "ok").put("counts", counts)
                                .put("rawItems", page.items.size).put("itemKinds", kinds(page))
                                .put("coreHttpCallsSoFar", coreHttpCalls)
                                .put("cursorSources", describeCursorSources(responseBody, page.nextKey))
                            responseBody = null
                            save()
                            if (page.nextKey == null) {
                                operation.put("stop", "no_cursor")
                                break
                            }
                            if (!counts.getBoolean("cursorAdvanced") || counts.getBoolean("cursorSeenBefore")) {
                                operation.put("stop", "repeated_cursor")
                                break
                            }
                            if (index == 2) operation.put("stop", "continuation_budget")
                            cursor = page.nextKey
                        } catch (cancelled: CancellationException) {
                            operation.put("outcome", "cancelled")
                            throw cancelled
                        } catch (error: Exception) {
                            operation.put("outcome", "failed").put("failure", describeFailure(error))
                                .put("stop", "lane_failure").put("coreHttpCallsSoFar", coreHttpCalls)
                            break
                        } finally {
                            responseBody = null
                            save()
                        }
                    }
                }
            }
            output.put("outcome", if ((0 until operations.length()).any {
                operations.getJSONObject(it).optString("outcome") == "failed"
            }) "partial" else "completed")
        } finally { save() }
    }

    @Test fun onlyCountsAndSchemaPathsCanEscapeThePaginationDiagnostic() {
        val secret = "SECRET_ACCOUNT_CONTENT_AND_TOKEN"
        val body = """{"contents":{"sectionListRenderer":{"contents":[{"shelfRenderer":{
            "title":"$secret","continuations":[{"reloadContinuationData":{"continuation":"$secret"}}],
            "$secret":{"nextContinuationData":{"continuation":"$secret"}}
        }}]}},"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{"continuationItems":[]}}]}"""
        val sources = describeCursorSources(body, secret)
        assertFalse(sources.toString().contains(secret))
        assertEquals(2, sources.getInt("selectedSourceCount"))
        assertEquals(1, sources.getJSONObject("allSourceTypes").getInt("reloadContinuationData"))
        assertEquals(1, sources.getJSONObject("responseCommands").getInt("reloadContinuationItemsCommand"))
        assertTrue(sources.toString().contains("contents[0].shelfRenderer.continuations[0].reloadContinuationData"))
        assertTrue(sources.toString().contains(".field.nextContinuationData"))
        val counts = PaginationCounts()
        counts.record(listOf(secret, "other"), null, "cursor_a")
        val repeated = counts.record(listOf(secret, secret), "cursor_a", "cursor_b")
        assertEquals(0, repeated.getInt("newItems"))
        assertEquals(1, repeated.getInt("repeatedItems"))
        assertEquals(1, repeated.getInt("duplicatesWithinPage"))
        assertFalse(repeated.toString().contains(secret))
        assertTrue(counts.record(emptyList(), "cursor_b", "cursor_a").getBoolean("cursorSeenBefore"))
        assertFalse(describeFailure(IllegalStateException(secret)).toString().contains(secret))
    }

    private fun appIds(section: LibrarySection, page: BrowsePage): List<String> = page.items
        .filter { it.kind == if (section == LibrarySection.PLAYLISTS) "playlist" else "video" }
        .map { if (section == LibrarySection.PLAYLISTS) it.id.removePrefix("VL") else it.id }

    private fun kinds(page: BrowsePage): JSONObject = JSONObject().apply {
        // Renderer kinds are a closed core enum in practice; unexpected strings must never
        // become report keys in an account-response diagnostic.
        page.items.groupingBy { if (it.kind in itemKinds) it.kind else "other" }.eachCount()
            .forEach { (kind, count) -> put(kind, count) }
    }

    private class PaginationCounts {
        private val seenIds = hashSetOf<String>()
        private val seenCursors = hashSetOf<String>()
        fun record(ids: List<String>, requested: String?, next: String?): JSONObject {
            val unique = ids.toSet()
            val newItems = unique.count { it !in seenIds }
            val repeated = unique.size - newItems
            seenIds.addAll(unique)
            requested?.let(seenCursors::add)
            val seenCursor = next != null && next in seenCursors
            next?.let(seenCursors::add)
            return JSONObject().put("mappedItems", ids.size).put("distinctItems", unique.size)
                .put("newItems", newItems).put("repeatedItems", repeated)
                .put("duplicatesWithinPage", ids.size - unique.size).put("totalDistinctItems", seenIds.size)
                .put("cursorPresent", next != null).put("cursorAdvanced", next != null && next != requested)
                .put("cursorSeenBefore", seenCursor)
        }
    }

    private fun describeCursorSources(body: String?, selectedCursor: String?): JSONObject {
        val output = JSONObject().put("parsed", false)
        val root = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return output
        val types = JSONObject()
        val commands = JSONObject()
        val matches = JSONArray()
        var matchingCount = 0
        fun count(target: JSONObject, type: String) { target.put(type, target.optInt(type) + 1) }
        fun match(type: String, path: String, value: String?) {
            if (value.isNullOrBlank()) return
            count(types, type)
            if (selectedCursor != null && value == selectedCursor) {
                matchingCount++
                if (matches.length() < 12) matches.put(JSONObject().put("type", type).put("path", path))
            }
        }
        fun visit(node: Any?, path: String, depth: Int) {
            if (depth > 96) { output.put("depthLimited", true); return }
            when (node) {
                is JSONObject -> {
                    val endpoint = node.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                    match("continuationCommand", "$path.continuationEndpoint.continuationCommand", endpoint?.optString("token"))
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val name = keys.next()
                        val child = node.opt(name)
                        val safeName = if (name in pathFields) name else "field"
                        val childPath = "$path.$safeName"
                        if (name in sourceTypes && child is JSONObject)
                            match(name, childPath, child.optString("continuation"))
                        if (name in responseCommands && child is JSONObject) count(commands, name)
                        visit(child, childPath, depth + 1)
                    }
                }
                is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index), "$path[$index]", depth + 1)
            }
        }
        visit(root, "$", 0)
        return output.put("parsed", true).put("allSourceTypes", types).put("selectedSourceCount", matchingCount)
            .put("selectedSources", matches).put("selectedSourcesTruncated", matchingCount > matches.length())
            .put("responseCommands", commands)
    }

    private fun describeFailure(error: Exception) = JSONObject().put("type", when (error) {
        is MediaException -> "media"
        is java.io.IOException -> "io"
        else -> "other"
    }).apply {
        (error as? MediaException)?.failure?.let {
            put("kind", it.kind.name).put("status", it.status ?: JSONObject.NULL)
        }
    }

    private companion object {
        const val FILE_NAME = "msc-live-account-pagination.json"
        val itemKinds = setOf("video", "short", "playlist", "channel", "radio")
        val sourceTypes = setOf("nextContinuationData", "reloadContinuationData", "timedContinuationData", "invalidationContinuationData")
        val responseCommands = setOf("appendContinuationItemsAction", "reloadContinuationItemsCommand")
        // Only schema names are exported. Unknown object keys become a literal "field".
        val pathFields = sourceTypes + responseCommands + setOf("contents", "content", "items", "continuations", "continuationItems",
            "continuationContents", "onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints",
            "singleColumnBrowseResultsRenderer", "twoColumnBrowseResultsRenderer", "tabRenderer", "tabs", "tvBrowseRenderer",
            "tvSurfaceContentRenderer", "tvSurfaceContentContinuation", "tvSurfaceContentHeaderRenderer", "sectionListRenderer",
            "sectionListContinuation", "itemSectionRenderer", "itemSectionContinuation", "shelfRenderer", "shelfContinuation",
            "richShelfRenderer", "richItemRenderer", "richGridRenderer", "richGridContinuation", "gridRenderer", "gridContinuation",
            "horizontalListRenderer", "horizontalListContinuation", "expandedShelfContentsRenderer", "verticalListRenderer",
            "playlistListRenderer", "playlistListContinuation", "playlistVideoListRenderer", "playlistVideoListContinuation",
            "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "navigationEndpoint", "serviceEndpoint",
            "menu", "header", "secondaryContents", "engagementPanels", "chips", "replies", "sortFilterSubMenuRenderer",
            "subMenuItems", "onTap", "onSelectCommand", "commentRepliesRenderer", "liveChatRenderer")
    }
}
