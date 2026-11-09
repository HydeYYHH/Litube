package com.hhst.youtubelite.account

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaException
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaSearchRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MediaSubscriptionsRepository
import com.hhst.youtubelite.data.media.MediaWatchRepository
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Read-only signed-in menu census (menuServiceItemRenderer rows only);
 * never writes token material, cookies or authorization values.
 */
class MenuCollectionAndroidTest {

    @Test fun compareSignedInReadProfiles() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue(MediaSession.signedIn())
        val profiles = listOf(null, "WEB", "MWEB")
        val report = JSONArray()
        for (profile in profiles) {
            val records = JSONArray()
            val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
                val original = chain.request()
                val path = original.url.encodedPath.substringAfterLast('/')
                check(path in setOf("next", "guide", "browse", "token")) { "unexpected operation: $path" }
                val request = if (profile != null && path != "token") {
                    val buffer = okio.Buffer()
                    requireNotNull(original.body).writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    body.getJSONObject("context").getJSONObject("client")
                        .put("clientName", profile).put("clientVersion", "2.20260907.06.00")
                    original.newBuilder().header("X-Youtube-Client-Name", if (profile == "WEB") "1" else "2")
                        .header("X-Youtube-Client-Version", "2.20260907.06.00")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
                        .post(body.toString().toRequestBody(original.body?.contentType())).build()
                } else original
                chain.proceed(request).also { response ->
                    if (path != "token") {
                        val root = runCatching { JSONObject(response.peekBody(4L * 1024 * 1024).string()) }.getOrDefault(JSONObject())
                        records.put(JSONObject().put("path", path).put("status", response.code)
                            .put("analysis", analyze(path, mapOf(path to root)))
                            .put("structure", if (path == "guide" || path == "browse") shape(root) else JSONObject.NULL))
                    }
                }
            }.build()
            val client = MediaClient(http, MediaSession::freshContext)
            val row = JSONObject().put("profile", profile ?: "TVHTML5")
            try {
                val page = client.read { context, wire ->
                    com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch("GjjK6yD5I9I", context, http = wire)
                }
                row.put("watchOk", page.ok).put("related", page.value?.related?.size ?: 0)
            } catch (error: Exception) { row.put("failure", error.javaClass.simpleName) }
            try {
                val channels = MediaSubscriptionsRepository(client).subscribedChannelsPage()
                row.put("channels", channels.channels.size).put("unread", channels.channels.count { it.hasNewContent })
            } catch (error: Exception) { row.put("directoryFailure", error.javaClass.simpleName) }
            row.put("requests", records)
            report.put(row)
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "read-profiles.json").writeText(report.toString(2))
        assertTrue(report.getJSONObject(0).optBoolean("watchOk"))
    }

    @Test fun compareWatchSourceParameters() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue(MediaSession.signedIn())
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            check(chain.request().url.encodedPath.substringAfterLast('/') in setOf("browse", "next", "token"))
            chain.proceed(chain.request())
        }.build()
        val report = MediaClient(http, MediaSession::freshContext).read { context, wire ->
            val home = com.liskovsoft.youtubeapi.videoinfo.V2.loadHome(
                com.liskovsoft.youtubeapi.videoinfo.V2.HomeSection.HOME, context, http = wire)
            val videos = home.value?.items.orEmpty().filter { it.kind == "video" }
            fun sourceParams(video: com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem): String? =
                video.endpoint?.takeIf { it.name == "watchEndpoint" }?.payload?.let { payload ->
                    JSONObject(payload).optString("params").takeIf(String::isNotBlank)
                }
            val row = JSONObject().put("videos", videos.size)
                .put("sourceParams", videos.count { sourceParams(it) != null })
            val source = videos.firstOrNull { sourceParams(it) != null }
            if (source != null) {
                val variants = JSONArray()
                for (params in listOf(null, sourceParams(source))) {
                    val read = com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch(source.id, context, http = wire, params = params)
                    val related = read.value?.related.orEmpty()
                    variants.put(JSONObject().put("withParams", params != null).put("ok", read.ok)
                        .put("related", related.size).put("deferred", related.count { it.menuEndpoint != null })
                        .put("notInterested", related.count { video -> video.actions.any { it.kind == com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind.NotInterested } })
                        .put("dontRecommend", related.count { video -> video.actions.any { it.kind == com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind.DontRecommendChannel } }))
                }
                row.put("variants", variants)
            }
            row
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "watch-source-parameters.json").writeText(report.toString(2))
        assertTrue("The home did not return videos", report.getInt("videos") > 0)
    }

    @Test fun signedInHomeLoadsItsDeferredMenuWithoutWritingFeedback() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue("Device authorization has not completed", MediaSession.signedIn())
        val panels = JSONArray()
        var panelReads = 0
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            check(path !in setOf("feedback", "like", "dislike", "subscribe", "unsubscribe")) { "unexpected write" }
            val response = chain.proceed(chain.request())
            if (path == "get_panel") {
                panelReads++
                val root = JSONObject(response.peekBody(1024L * 1024).string())
                fun shape(node: Any?): Any = when (node) {
                    is JSONObject -> JSONObject().also { result -> node.keys().forEach { key ->
                        if (key !in setOf("clickTrackingParams", "trackingParams", "loggingDirectives")) result.put(key, shape(node.opt(key)))
                    } }
                    is JSONArray -> JSONArray().also { result -> for (i in 0 until minOf(node.length(), 20)) result.put(shape(node.opt(i))) }
                    is String -> "string:${node.length}"
                    is Boolean -> node
                    else -> "value"
                }
                panels.put(shape(root.opt("content")))
            }
            response
        }.build()
        val repository = MediaHomeRepository(MediaClient(http, MediaSession::freshContext))
        val video = repository.loadHome("All").items.filterIsInstance<com.hhst.youtubelite.home.domain.FeedItem.Video>()
            .first { it.menuEndpoint != null }
        val resolved = repository.menu(video)
        val readsBeforeCache = panelReads
        val cached = repository.menu(video)
        val report = JSONObject().put("signedIn", true).put("panelReads", panelReads)
            .put("notInterested", !resolved.notInterestedToken.isNullOrBlank())
            .put("dontRecommendChannel", !resolved.dontRecommendToken.isNullOrBlank()).put("panels", panels)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "home-menu-read.json").writeText(report.toString(2))
        assertTrue("The deferred menu exposed no video dismissal", !resolved.notInterestedToken.isNullOrBlank())
        assertTrue("The deferred menu exposed no channel dismissal", !resolved.dontRecommendToken.isNullOrBlank())
        assertTrue("The second read did not reuse the capabilities", cached == resolved && panelReads == readsBeforeCache)
        assertTrue("No deferred panel request was made", panelReads > 0)
    }

    @Test fun signedInWatchPreservesReturnedMenuCapabilitiesWithoutWritingFeedback() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue(MediaSession.signedIn())
        var panels = 0
        val cards = JSONArray()
        var returned = emptyList<com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem>()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            check(path !in setOf("feedback", "like", "dislike", "subscribe", "unsubscribe")) { "unexpected write" }
            if (path == "get_panel") panels++
            chain.proceed(chain.request()).also { response ->
                if (path == "next") {
                    val body = response.peekBody(4L * 1024 * 1024).string()
                    cards.put(cardShapes(JSONObject(body)))
                    returned = com.liskovsoft.youtubeapi.videoinfo.V2.watchPage(body).related
                }
            }
        }.build()
        val repository = MediaWatchRepository(MediaClient(http, MediaSession::freshContext))
        val page = repository.watch("GjjK6yD5I9I")
        val source = page.related
        val reportFile = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "watch-menu-read.json")
        reportFile.writeText(JSONObject().put("related", source.size)
            .put("endpoints", source.count { it.menuEndpoint != null })
            .put("inlineFeedback", source.count { !it.notInterestedToken.isNullOrBlank() || !it.dontRecommendToken.isNullOrBlank() })
            .put("cards", cards).toString(2))
        assertTrue("No related videos returned", source.isNotEmpty())
        source.forEach { video ->
            val raw = returned.first { it.id == video.id }
            fun token(kind: com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind) = raw.actions.firstOrNull { it.kind == kind }?.kindToken
            assertTrue(video.notInterestedToken == token(com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind.NotInterested))
            assertTrue(video.dontRecommendToken == token(com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind.DontRecommendChannel))
            assertTrue((video.menuEndpoint != null) == (raw.menuEndpoint != null))
        }
        source.firstOrNull { it.menuEndpoint != null }?.let { video ->
            val menu = repository.menu(video)
            val count = panels
            assertTrue(repository.menu(video) == menu && panels == count && count > 0)
        }
        Unit
    }

    @Test fun signedInDirectoryRecordsUnreadSignalsWithoutAccountWrites() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue(MediaSession.signedIn())
        val shapes = JSONArray()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            check(path in setOf("guide", "browse", "token")) { "unexpected operation: $path" }
            val response = chain.proceed(chain.request())
            if (path == "guide") {
                val root = JSONObject(response.peekBody(4L * 1024 * 1024).string())
                fun visit(node: Any?) {
                    when (node) {
                        is JSONObject -> node.keys().forEach { key ->
                            val value = node.opt(key)
                            if (key == "guideEntryRenderer" && value is JSONObject) {
                                val id = value.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                                if (id.startsWith("UC")) shapes.put(shape(value))
                            } else visit(value)
                        }
                        is JSONArray -> (0 until node.length()).forEach { visit(node.opt(it)) }
                    }
                }
                visit(root)
            }
            response
        }.build()
        val page = MediaSubscriptionsRepository(MediaClient(http, MediaSession::freshContext)).subscribedChannelsPage()
        val report = JSONObject().put("channels", page.channels.size).put("unread", page.channels.count { it.hasNewContent })
            .put("hasNext", page.nextKey != null).put("guideEntries", shapes)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "subscription-signals.json").writeText(report.toString(2))
        assertTrue("The subscribed-channel directory is empty", page.channels.isNotEmpty())
    }

    @Test fun collectSignedInCardMenus() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("menuCollection") == "1")
        if (!MediaSession.signedIn() && arguments.getString("awaitSignIn") == "1") {
            val service = com.hhst.youtubelite.account.data.MediaSignInService(GlobalContext.get().get(),
                com.hhst.youtubelite.data.media.PreferencesAuthStore(instrumentation.targetContext))
            val code = service.loadCode()
            File(instrumentation.targetContext.filesDir, "device-authorization-request.json").writeText(
                JSONObject().put("url", code.verificationUrl).put("code", code.userCode).toString())
            service.complete(service.poll(code))
        }
        assertTrue("Device authorization has not completed", MediaSession.signedIn())

        val captured = LinkedHashMap<String, JSONObject>()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val path = chain.request().url.encodedPath.substringAfterLast('/')
            if (path in setOf("browse", "search", "next")) {
                val body = runCatching { response.peekBody(8L * 1024 * 1024).string() }.getOrNull()
                if (body != null) synchronized(captured) { captured["$path:${captured.size}"] =
                    runCatching { JSONObject(body) }.getOrDefault(JSONObject()) }
            }
            response
        }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val surfaces = JSONObject()
        val failures = mutableListOf<String>()
        suspend fun collect(name: String, block: suspend () -> Unit) {
            synchronized(captured) { captured.clear() }
            try {
                block()
                synchronized(captured) { surfaces.put(name, analyze(name, captured)) }
            } catch (error: Exception) {
                val failure = (error as? MediaException)?.failure
                surfaces.put(name, JSONObject().put("ok", false)
                    .put("failure", failure?.kind?.name ?: error.javaClass.simpleName)
                    .put("code", failure?.code?.take(96)))
                failures += name
            }
        }
        var feedbackKinds: Pair<Int, Int>? = null
        var homeVideoCount = 0
        collect("home") {
            val page = MediaHomeRepository(client).loadHome("All")
            val videos = page.items.filterIsInstance<com.hhst.youtubelite.home.domain.FeedItem.Video>()
            homeVideoCount = videos.size
            feedbackKinds = videos.count { !it.notInterestedToken.isNullOrBlank() } to
                videos.count { !it.dontRecommendToken.isNullOrBlank() }
        }
        collect("related") { MediaWatchRepository(client).watch("GjjK6yD5I9I") }
        collect("search") { MediaSearchRepository(client).search("lofi hip hop",
            com.hhst.youtubelite.search.domain.SearchFilters()) }
        collect("subscriptions") { MediaSubscriptionsRepository(client)
            .subscriptions(SubscriptionsFilter.All) }
        val report = JSONObject().put("signedIn", true).put("surfaces", surfaces)
            .put("failures", JSONArray(failures))
            .put("homeFeedbackKinds", JSONObject().put("notInterested", feedbackKinds?.first)
                .put("dontRecommendChannel", feedbackKinds?.second).put("videos", homeVideoCount))
        File(instrumentation.targetContext.filesDir, "menu-collection.json").writeText(report.toString(2))
        assertTrue("Failed surfaces: ${failures.joinToString()}", failures.isEmpty())
        if (arguments.getString("assertFeedback") == "1") {
            assertTrue("The signed-in home exposed no card dismissal", (feedbackKinds?.first ?: 0) > 0)
            assertTrue("The signed-in home exposed no channel dismissal", (feedbackKinds?.second ?: 0) > 0)
        }
    }

    private fun analyze(surface: String, captured: Map<String, JSONObject>): JSONObject {
        val rows = LinkedHashMap<String, JSONObject>()
        val perRequest = JSONObject()
        for ((path, root) in captured) {
            val records = JSONArray()
            walkRows(root, null, records)
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                record.put("request", path)
                val signature = signature(record)
                rows.putIfAbsent(signature, record)
            }
            val parsed = com.liskovsoft.youtubeapi.videoinfo.V2.loadHome(http =
                object : com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient {
                    override fun execute(request: com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest) =
                        com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply(200, root.toString())
                }).value?.items.orEmpty()
            perRequest.put(path, JSONObject().put("menuRows", records.length())
                .put("cardShapes", cardShapes(root))
                .put("parsedItems", parsed.size)
                .put("parsedActions", JSONArray(parsed.map { item -> JSONObject()
                    .put("kind", item.kind).put("actions", JSONArray(item.actions.map { action ->
                        JSONObject().put("endpoint", action.endpoint.name).put("kind", action.kind?.name)
                            .put("hasToken", !action.kindToken.isNullOrBlank())
                    })) })))
        }
        val items = JSONArray()
        for (row in rows.values) items.put(row)
        return JSONObject().put("ok", true).put("requests", perRequest).put("items", items)
    }

    private fun cardShapes(root: JSONObject): JSONArray {
        val records = JSONArray()
        val seen = mutableSetOf<String>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val value = node.opt(key)
                    if (key in setOf("tileRenderer", "videoRenderer", "compactVideoRenderer", "lockupViewModel", "channelRenderer", "guideEntryRenderer") && value is JSONObject) {
                        val tree = shape(value)
                        val signature = "$key:$tree"
                        if (seen.add(signature) && records.length() < 8)
                            records.put(JSONObject().put("renderer", key).put("shape", tree))
                    } else visit(value)
                }
                is JSONArray -> (0 until node.length()).forEach { visit(node.opt(it)) }
            }
        }
        visit(root)
        return records
    }

    /** Desensitized row identity: structure/sizes only, never token material. */
    private fun signature(record: JSONObject): String = listOf(
        record.optString("iconType", ""),
        record.optInt("tokenCount"),
        record.optJSONArray("tokenLengths")?.join(",") ?: "",
        record.optJSONArray("tokenPaths")?.join(",") ?: "",
        record.optJSONArray("uiActionsKeys")?.join(",") ?: "",
        record.optInt("titleLength"),
    ).joinToString("|")

    private fun walkRows(node: Any?, key: String?, records: JSONArray, path: String = "") {
        when (node) {
            is JSONObject -> {
                if (key == "menuServiceItemRenderer") {
                    records.put(recordRow(node).put("path", path))
                    return
                }
                for (field in node.keys()) {
                    if (field == "clickTrackingParams") continue
                    walkRows(node.opt(field), field, records, "$path.$field")
                }
            }
            is JSONArray -> for (index in 0 until node.length()) walkRows(node.opt(index), key, records, "$path[]")
        }
    }

    private fun recordRow(row: JSONObject): JSONObject {
        val endpoint = row.optJSONObject("serviceEndpoint")
        val inner = endpoint?.optJSONObject("innertubeCommand") ?: endpoint
        val tokenPaths = tokenPaths(inner)
        return JSONObject()
            .put("itemRenderer", "menuServiceItemRenderer")
            .put("endpointField", if (endpoint != null) "serviceEndpoint" else JSONObject.NULL)
            .put("iconType", icon(row) ?: JSONObject.NULL)
            .put("tokenCount", tokenPaths.size)
            .put("tokenLengths", JSONArray(tokenPaths.map { it.second }))
            .put("tokenPaths", JSONArray(tokenPaths.map { it.first }))
            .put("uiActionsKeys", JSONArray(tokenPaths.map { it.third }.filterNotNull().distinct()))
            .put("titleLength", displayText(row)?.length ?: 0)
            .put("titleBlank", displayText(row).isNullOrBlank())
            .put("label", displayText(row)?.take(128) ?: JSONObject.NULL)
            .put("removedContainerKinds", JSONArray(removedContainerKinds(row)))
            .put("hideContainerFlags", JSONArray(hideContainerFlags(row)))
            .put("shape", shape(row))
    }

    private fun hideContainerFlags(node: Any?): List<Boolean> = when (node) {
        is JSONObject -> listOfNotNull((node.opt("hideEnclosingContainer") as? Boolean)) +
            node.keys().asSequence().flatMap { hideContainerFlags(node.opt(it)) }.toList()
        is JSONArray -> (0 until node.length()).flatMap { hideContainerFlags(node.opt(it)) }
        else -> emptyList()
    }

    private fun removedContainerKinds(node: Any?): List<String> = when (node) {
        is JSONObject -> {
            val removed = node.optJSONObject("removeItemAction")?.optString("childId")
            listOfNotNull(removed?.let { if (it.matches(Regex("UC[A-Za-z0-9_-]{22}"))) "channel" else "other" }) +
                node.keys().asSequence().flatMap { removedContainerKinds(node.opt(it)) }.toList()
        }
        is JSONArray -> (0 until node.length()).flatMap { removedContainerKinds(node.opt(it)) }
        else -> emptyList()
    }

    private fun shape(node: Any?): Any = when (node) {
        is JSONObject -> JSONObject().apply {
            node.keys().forEach { key ->
                if (key !in setOf("clickTrackingParams", "trackingParams")) put(key, shape(node.opt(key)))
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until minOf(node.length(), 8)) put(shape(node.opt(index)))
        }
        is Boolean -> "boolean"
        is Number -> "number"
        is String -> "string"
        else -> "null"
    }

    private fun icon(item: JSONObject): String? {
        for (key in listOf("icon", "leadingIcon", "iconImage")) {
            val icon = item.optJSONObject(key) ?: continue
            icon.optString("iconType").takeIf(String::isNotBlank)?.let { return it }
            icon.optString("name").takeIf(String::isNotBlank)?.let { return it }
        }
        return item.optString("iconType").takeIf(String::isNotBlank)
    }

    /** (path, length, uiActions key) for every feedback token under the payload. */
    private fun tokenPaths(node: Any?, path: String = "", uiActions: String? = null):
        List<Triple<String, Int, String?>> = when (node) {
            is JSONObject -> {
                if (node.has("feedbackToken")) listOf(Triple(path, node.optString("feedbackToken").length, uiActions))
                else node.keys().asSequence().flatMap { field ->
                    val nextUi = when {
                        field == "hideUnhideUis" -> field
                        field == "hideEnclosingContainerUis" -> field
                        uiActions != null -> uiActions
                        else -> null
                    }
                    tokenPaths(node.opt(field), if (path.isEmpty()) field else "$path.$field", nextUi)
                }.toList()
            }
            is JSONArray -> (0 until node.length()).flatMap {
                tokenPaths(node.opt(it), "$path[$it]", uiActions)
            }
            else -> emptyList()
        }

    /** Read-only Watch related-rail census across TV/WEB/MWEB; never records token material or URLs. */
    @Test fun compareWatchReadContextsAndContinuationCapabilities() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue("Device authorization has not completed", MediaSession.signedIn())
        val client = MediaClient(GlobalContext.get().get<OkHttpClient>(), MediaSession::freshContext)

        fun census(items: List<com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem>) = JSONObject()
            .put("cards", items.size)
            .put("deferred", items.count { it.menuEndpoint != null })
            .put("notInterested", items.count { item -> item.actions.any {
                it.kind == com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind.NotInterested && !it.kindToken.isNullOrBlank() } })
            .put("dontRecommend", items.count { item -> item.actions.any {
                it.kind == com.liskovsoft.youtubeapi.videoinfo.V2.MenuActionKind.DontRecommendChannel && !it.kindToken.isNullOrBlank() } })
            .put("kinds", JSONArray(items.map { it.kind }.distinct()))

        val report = JSONObject()
        val token = client.read { context, _ -> context.accessToken.orEmpty() }
        val tvFirst = client.read { context, http ->
            com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch("GjjK6yD5I9I", context, http = http).value
        } ?: throw AssertionError("The signed-in watch read returned no page")
        val tvSecond = tvFirst.nextKey?.let { key ->
            client.read { context, http ->
                com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch("GjjK6yD5I9I", context, continuation = key, http = http).value
            } ?: throw AssertionError("The continuation read returned no page")
        }
        report.put("tvFirst", census(tvFirst.related))
        report.put("tvSecond", tvSecond?.let { census(it.related) } ?: JSONObject.NULL)
        report.put("tvSecondNextKey", tvSecond?.nextKey != null)
        report.put("accessTokenPresent", token.isNotBlank())

        val version = "2.20260907.06.00"
        val desktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val mobile = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        fun raw(profile: String, version: String, clientId: Int, agent: String,
            withToken: Boolean, continuation: String? = null): JSONObject {
            val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", profile).put("clientVersion", version).put("hl", "en").put("gl", "US")))
            if (continuation == null) body.put("videoId", "GjjK6yD5I9I") else body.put("continuation", continuation)
            val builder = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", agent)
                .header("X-Youtube-Client-Name", clientId.toString())
                .header("X-Youtube-Client-Version", version)
                .post(body.toString().toRequestBody(null))
            if (withToken) builder.header("Authorization", "Bearer $token")
            GlobalContext.get().get<OkHttpClient>().newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val record = JSONObject().put("profile", profile).put("status", response.code).put("bytes", text.length)
                if (response.code == 200) {
                    runCatching { com.liskovsoft.youtubeapi.videoinfo.V2.watchPage(text) }.onSuccess { page ->
                        record.put("related", census(page.related)).put("hasKey", page.nextKey != null)
                    }.onFailure { record.put("parse", it.javaClass.simpleName) }
                } else {
                    val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    record.put("error", message?.take(160) ?: JSONObject.NULL)
                }
                return record
            }
        }

        val controls = JSONArray()
        controls.put(raw("TVHTML5", "7.20260901.15.00", 7, "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold", true))
        controls.put(raw("TVHTML5", "7.20260901.15.00", 7, "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold", false))
        controls.put(raw("WEB", version, 1, desktop, true))
        controls.put(raw("WEB", version, 1, desktop, false))
        controls.put(raw("MWEB", version, 2, mobile, true))
        controls.put(raw("MWEB", version, 2, mobile, false))
        report.put("rawProfiles", controls)
        tvFirst.nextKey?.let { key ->
            report.put("rawContinuationWeb", raw("WEB", version, 1, desktop, true, key))
        }

        val search = MediaSearchRepository(client).search("lofi hip hop",
            com.hhst.youtubelite.search.domain.SearchFilters())
        val videos = search.results
        report.put("search", JSONObject().put("videos", videos.size)
            .put("notInterested", videos.count { !it.notInterestedToken.isNullOrBlank() })
            .put("dontRecommend", videos.count { !it.dontRecommendToken.isNullOrBlank() })
            .put("deferred", videos.count { it.menuEndpoint != null }))

        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "watch-context-census.json")
            .writeText(report.toString(2))
        assertTrue("No related videos returned", tvFirst.related.isNotEmpty())
        assertTrue("A continuation page was never read", tvSecond != null)
    }

    /** Records feedback endpoint/menu shapes across watch contexts; never writes token values, titles or URLs. */
    @Test fun recordFeedbackEndpointShapesAcrossWatchContexts() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("menuCollection") == "1")
        assertTrue("Device authorization has not completed", MediaSession.signedIn())
        val client = MediaClient(GlobalContext.get().get<OkHttpClient>(), MediaSession::freshContext)
        val token = client.read { context, _ -> context.accessToken.orEmpty() }
        var tvBody = ""
        client.read { context, http ->
            val capture = object : com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient {
                override fun execute(request: com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest):
                    com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply {
                    val reply = http.execute(request)
                    tvBody = reply.body
                    return reply
                }
            }
            com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch("GjjK6yD5I9I", context, http = capture)
        }
        val report = JSONObject()

        fun census(root: JSONObject): JSONObject {
            val renderers = JSONObject()
            val feedback = JSONArray()
            val menuRows = JSONArray()
            val tilePaths = JSONArray()
            fun visit(node: Any?, path: String) {
                when (node) {
                    is JSONObject -> {
                        node.keys().forEach { key ->
                            val value = node.opt(key)
                            if (key.endsWith("Renderer") || key.endsWith("ViewModel")) renderers.put(key, renderers.optInt(key) + 1)
                            if (key == "tileRenderer" && tilePaths.length() < 3) tilePaths.put(path.takeLast(160))
                            if (key == "feedbackEndpoint" && value is JSONObject && feedback.length() < 120) {
                                val f = value
                                val actionKeys = f.optJSONArray("actions")?.let { a ->
                                    (0 until a.length()).flatMap { i -> a.optJSONObject(i)?.keys()?.asSequence()?.toList().orEmpty() }
                                }.orEmpty()
                                feedback.put(JSONObject().put("path", path.takeLast(120))
                                    .put("keys", JSONArray(f.keys().asSequence().toList()))
                                    .put("uiActions", JSONArray(f.optJSONObject("uiActions")?.keys()?.asSequence()?.toList().orEmpty()))
                                    .put("actionKeys", JSONArray(actionKeys))
                                    .put("tokenLength", f.optString("feedbackToken").length)
                                    .put("tokenArrayLength", f.optJSONArray("feedbackTokens")?.length() ?: 0))
                            }
                            if (key in setOf("menuServiceItemRenderer", "listItemViewModel") && value is JSONObject && menuRows.length() < 60) {
                                menuRows.put(JSONObject().put("renderer", key)
                                    .put("iconType", value.optJSONObject("icon")?.optString("iconType").orEmpty()
                                        .ifBlank { value.optJSONObject("leadingIcon")?.optString("iconType").orEmpty() })
                                    .put("hasFeedback", value.toString().contains("feedbackEndpoint"))
                                    .put("titleLength", value.optJSONObject("text")?.toString()?.length ?: 0))
                            }
                            visit(value, if (path.isEmpty()) key else "$path.$key")
                        }
                    }
                    is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), "$path[]")
                }
            }
            visit(root, "")
            return JSONObject().put("renderers", renderers).put("feedback", feedback).put("menuRows", menuRows)
                .put("tilePaths", tilePaths).put("topKeys", JSONArray(root.keys().asSequence().toList()))
                .put("contentsKeys", JSONArray(root.optJSONObject("contents")?.keys()?.asSequence()?.toList().orEmpty()))
                .put("singleColumnKeys", JSONArray(root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnWatchNextResults")?.keys()?.asSequence()?.toList().orEmpty()))
                .put("pivotKeys", JSONArray(root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnWatchNextResults")?.optJSONObject("pivot")?.keys()?.asSequence()?.toList().orEmpty()))
                .put("continuationKeys", JSONArray(root.optJSONObject("continuationContents")?.keys()?.asSequence()?.toList().orEmpty()))
        }

        val version = "2.20260907.06.00"
        val desktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        fun body(profile: String, clientVersion: String, clientId: Int, agent: String, continuation: String? = null): Pair<Int, String> {
            val payload = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", profile).put("clientVersion", clientVersion).put("hl", "en").put("gl", "US")))
            if (continuation == null) payload.put("videoId", "GjjK6yD5I9I") else payload.put("continuation", continuation)
            val request = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", agent)
                .header("X-Youtube-Client-Name", clientId.toString())
                .header("X-Youtube-Client-Version", clientVersion)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(null))
                .build()
            GlobalContext.get().get<OkHttpClient>().newCall(request).execute().use { response ->
                return response.code to response.body?.string().orEmpty()
            }
        }

        val tv = JSONObject(tvBody)
        report.put("tv", JSONObject().put("status", 200).put("census", census(tv)))
        val firstPageKey = com.liskovsoft.youtubeapi.videoinfo.V2.watchPage(tvBody, "GjjK6yD5I9I").nextKey
        report.put("tvContinuation", firstPageKey?.let { key ->
            val (status, text) = body("TVHTML5", "7.20260901.15.00", 7, "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold", key)
            JSONObject().put("status", status).put("bytes", text.length)
                .put("census", runCatching { census(JSONObject(text)) }.getOrElse { JSONObject().put("parse", "failed") })
                .put("related", runCatching { com.liskovsoft.youtubeapi.videoinfo.V2.watchPage(text).related.size }.getOrDefault(-1))
        } ?: JSONObject.NULL)
        for ((name, spec) in listOf("web" to Triple("WEB", version, 1 to desktop), "mweb" to Triple("MWEB", version, 2 to ""))) {
            val (profile, clientVersion, idAgent) = spec
            val (status, text) = body(profile, clientVersion, idAgent.first, idAgent.second.ifBlank { desktop })
            report.put(name, JSONObject().put("status", status).put("bytes", text.length)
                .put("census", runCatching { census(JSONObject(text)) }.getOrElse { JSONObject().put("parse", "failed") }))
        }
        report.put("feedbackCounts", JSONObject()
            .put("tv", (report.getJSONObject("tv").getJSONObject("census").getJSONArray("feedback")).length())
            .put("web", (report.optJSONObject("web")?.optJSONObject("census")?.optJSONArray("feedback")?.length() ?: -1)))
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "feedback-endpoint-shapes.json")
            .writeText(report.toString(2))
        assertTrue("The TV watch read returned nothing", tv.length() > 0)
    }

    private fun displayText(item: JSONObject): String? {
        for (key in listOf("text", "title", "accessibilityText")) {
            val value = item.opt(key)
            val text = when (value) {
                is String -> value
                is JSONObject -> value.optString("simpleText").takeIf(String::isNotBlank)
                    ?: (value.optJSONArray("runs")?.let { runs ->
                        (0 until runs.length()).joinToString("") { index ->
                            runs.optJSONObject(index)?.optString("text").orEmpty() } })
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

}
