package com.hhst.youtubelite.post.data

import com.hhst.youtubelite.post.domain.ChannelPostsPage
import com.hhst.youtubelite.post.domain.PostAuthor
import com.hhst.youtubelite.post.domain.PostDetail
import com.hhst.youtubelite.post.domain.PostIdentity
import com.hhst.youtubelite.post.domain.PostRepository
import com.hhst.youtubelite.post.domain.PostSummary
import kotlinx.coroutines.delay

/**
 * Fixed local post fixtures. No post, feedback or comment request ever leaves the device.
 *
 * The same records back the channel Posts cards, the Home post card and the post detail page, so
 * a post keeps one identity everywhere it appears.
 */
class FixturePostRepository(private val scenario: PostScenario = PostScenario.Content) : PostRepository {
    override suspend fun post(identity: PostIdentity): PostDetail? {
        delay(120)
        if (scenario == PostScenario.Error) throw java.io.IOException("Local post error scenario")
        if (scenario == PostScenario.Empty) return null
        return sampleDetail(identity)
    }

    override suspend fun channelPosts(channelId: String): ChannelPostsPage {
        delay(120)
        if (scenario == PostScenario.Error) throw java.io.IOException("Local post error scenario")
        if (scenario == PostScenario.Empty) return ChannelPostsPage(emptyList())
        return ChannelPostsPage(samplePosts(channelId))
    }

    companion object {
        const val HOME_POST_ID = "post"

        fun author(channelId: String): PostAuthor = PostAuthor(
            channelId = channelId,
            name = when (channelId) {
                "studio" -> "Blender Studio"
                "northlight" -> "Northlight Films"
                else -> "Blender"
            },
            avatar = "bunny",
        )

        fun samplePosts(channelId: String): List<PostSummary> = listOf(
            PostSummary(
                identity = PostIdentity(channelId, if (channelId == "studio") HOME_POST_ID else "community-1"),
                author = author(channelId),
                age = if (channelId == "studio") "2 days ago" else "3 weeks ago",
                text = "A new story starts with a little curiosity. Which open movie would you watch again? " +
                    "The archive is open for everyone, and every frame is made with tools you can study and reuse. " +
                    "Tell us which scene still stays with you after the credits.",
                images = listOf("dream", "sintel", "bunny"),
                likes = "1.2K",
                comments = "42",
            ),
            PostSummary(
                identity = PostIdentity(channelId, "community-2"),
                author = author(channelId),
                age = "1 month ago",
                text = "Production update: this month the team is blocking the next sequence and sharing " +
                    "the first lighting studies. The small choices in silhouettes and colour are what make a world feel lived in.",
                images = listOf("sintel"),
                likes = "419K",
                comments = "7.4K",
            ),
            PostSummary(
                identity = PostIdentity(channelId, "community-3"),
                author = author(channelId),
                age = "2 months ago",
                text = "A quiet thank you to everyone who joined the open studio sessions. Your questions " +
                    "made this one of the most generous weeks we have had. We will publish the notes and artwork soon.",
                images = emptyList(),
                likes = "895",
                comments = "25",
            ),
        )

        fun sampleDetail(identity: PostIdentity): PostDetail? {
            val summary = samplePosts(identity.channelId).firstOrNull { it.identity == identity } ?: return null
            return PostDetail(
                identity = summary.identity,
                author = summary.author,
                age = summary.age,
                text = summary.text,
                images = summary.images,
                likes = summary.likes,
                comments = summary.comments,
            )
        }
    }
}

enum class PostScenario(val argument: String) {
    Content("content"), Loading("loading"), Empty("empty"), Error("error");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content
    }
}
