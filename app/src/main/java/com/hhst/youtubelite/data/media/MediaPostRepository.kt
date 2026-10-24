package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.post.domain.*
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannelPosts
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPost

/**
 * Raw post documents (browse responses and the /post page) parse inside MSC common;
 * this repository only maps the shared PostItem rows onto the app's post domain.
 */
class MediaPostRepository(private val client: MediaClient) : PostRepository {
    override suspend fun channelPosts(channelId: String): ChannelPostsPage = client.read { context, http ->
        val first = loadChannel(channelId, context, http = http).requireValue()
        val tab = first.page.tabs.firstOrNull { it.title.equals("Posts", true) || it.title.equals("Community", true) }
            ?: return@read ChannelPostsPage(emptyList())
        val page = loadChannelPosts(channelId, context, params = tab.params, http = http).requireValue()
        ChannelPostsPage(page.posts.map { it.summary() }, page.nextKey)
    }

    override suspend fun channelPostsNext(channelId: String, cursor: String): ChannelPostsPage = client.read { context, http ->
        val page = loadChannelPosts(channelId, context, continuation = cursor, http = http).requireValue()
        ChannelPostsPage(page.posts.map { it.summary() }, page.nextKey)
    }

    override suspend fun post(identity: PostIdentity): PostDetail? = client.read { _, http ->
        val page = loadPost(identity.postId, http).requireValue()
        val post = page.posts.firstOrNull { it.postId == identity.postId } ?: return@read null
        val authorId = post.authorId ?: identity.channelId
        PostDetail(PostIdentity(authorId, post.postId), PostAuthor(authorId, post.author.orEmpty(), post.authorAvatar.orEmpty()),
            post.published.orEmpty(), post.text.orEmpty(), post.images, post.likes.orEmpty(), post.replies.orEmpty())
    }
}

private fun PostItem.summary() = PostSummary(PostIdentity(authorId.orEmpty(), postId),
    PostAuthor(authorId.orEmpty(), author.orEmpty(), authorAvatar.orEmpty()),
    published.orEmpty(), text.orEmpty(), images, likes.orEmpty(), replies.orEmpty())
