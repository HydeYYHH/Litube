package com.hhst.youtubelite.data.media

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.hhst.youtubelite.post.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import java.io.StringReader

internal fun JsonElement.objects(name: String): List<JsonObject> = when {
    isJsonObject -> asJsonObject.entrySet().flatMap { (key, value) ->
        if (key == name && value.isJsonObject) listOf(value.asJsonObject) else value.objects(name)
    }
    isJsonArray -> asJsonArray.flatMap { it.objects(name) }
    else -> emptyList()
}
internal fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
internal fun JsonElement?.display(): String = when {
    this == null || isJsonNull -> ""
    isJsonPrimitive -> asString
    isJsonObject -> asJsonObject.string("simpleText") ?: asJsonObject.string("content")
        ?: asJsonObject.getAsJsonArray("runs")?.joinToString("") { it.asJsonObject.string("text").orEmpty() }.orEmpty()
    else -> ""
}
internal fun JsonObject.image(name: String): String = getAsJsonObject(name)?.getAsJsonArray("thumbnails")
    ?.lastOrNull()?.asJsonObject?.string("url").orEmpty()

class MediaPostRepository(private val client: MediaClient) : PostRepository {
    override suspend fun channelPosts(channelId: String): List<PostSummary> = client.read { context, http ->
        val first = loadChannel(channelId, context, http = http).requireValue()
        val tab = first.page.tabs.firstOrNull { it.title.equals("Posts", true) || it.title.equals("Community", true) }
            ?: return@read emptyList()
        val reply = http.execute(channelQuery(channelId, context, tab.params).request())
        if (reply.status !in 200..299) throw MediaException(RequestError(RequestFailure.HTTP, "http_${reply.status}", reply.status))
        parsePosts(JsonParser.parseString(reply.body), channelId)
    }

    override suspend fun post(identity: PostIdentity): PostDetail? = client.read { _, http ->
        val root = readPost(identity.postId, http)
        val post = parsePosts(root, identity.channelId).firstOrNull { it.identity.postId == identity.postId } ?: return@read null
        PostDetail(post.identity, post.author, post.age, post.text, post.images, post.likes, post.comments)
    }

    internal companion object {
        fun readPost(id: String, http: HttpClient): JsonElement {
            require(id.matches(Regex("[A-Za-z0-9_-]{6,200}")))
            val reply = http.execute(HttpRequest("GET", "https://www.youtube.com/post/$id?hl=en"))
            if (reply.status !in 200..299) throw MediaException(RequestError(RequestFailure.HTTP, "http_${reply.status}", reply.status))
            val marker = Regex("(?:var\\s+)?ytInitialData\\s*=\\s*|window\\[\"ytInitialData\"\\]\\s*=\\s*").find(reply.body)
                ?: throw MediaException(RequestError(RequestFailure.PARSE, "missing_post_data"))
            return JsonParser.parseReader(JsonReader(StringReader(reply.body.substring(marker.range.last + 1))))
        }

        fun parsePosts(root: JsonElement, channelId: String): List<PostSummary> = root.objects("backstagePostRenderer").mapNotNull { post ->
            val id = post.string("postId") ?: return@mapNotNull null
            val authorEndpoint = post.getAsJsonObject("authorEndpoint")?.getAsJsonObject("browseEndpoint")
            val authorId = authorEndpoint?.string("browseId") ?: channelId
            val images = post.get("backstageAttachment")?.objects("backstageImageRenderer").orEmpty().map { it.image("image") }.filter { it.isNotBlank() }
            PostSummary(PostIdentity(authorId, id), PostAuthor(authorId, post.get("authorText").display(), post.image("authorThumbnail")),
                post.get("publishedTimeText").display(), post.get("contentText").display(), images,
                post.get("voteCount").display(), post.get("replyCount").display())
        }.distinctBy { it.identity }
    }
}
