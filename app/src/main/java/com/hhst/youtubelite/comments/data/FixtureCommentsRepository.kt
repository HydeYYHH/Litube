package com.hhst.youtubelite.comments.data

import com.hhst.youtubelite.comments.domain.CommentAuthor
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.CommentsRepository
import java.io.IOException
import kotlinx.coroutines.delay

/** Packaged English sample threads; no network, account or session access. */
class FixtureCommentsRepository(
    private val scenario: CommentsScenario = CommentsScenario.Content,
) : CommentsRepository {
    override suspend fun comments(target: com.hhst.youtubelite.comments.domain.CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
        delay(120)
        if (scenario == CommentsScenario.Error) throw IOException("Local comments error")
        if (scenario == CommentsScenario.Disabled) {
            return CommentsPage(Total, emptyList(), nextCursor = null, turnedOff = true)
        }
        if (scenario == CommentsScenario.Empty) return CommentsPage(Total, emptyList(), nextCursor = null)
        val ordered = if (sort == CommentSort.Top) samples else samples.sortedByDescending { it.recency }
        val start = cursor?.toIntOrNull() ?: 0
        if (start >= ordered.size) return CommentsPage(Total, emptyList(), null)
        if (scenario == CommentsScenario.LoadMoreError && start > 0) throw IOException("Local pagination error")
        val end = minOf(start + PageSize, ordered.size)
        return CommentsPage(
            total = Total,
            threads = ordered.subList(start, end).map { it.thread },
            nextCursor = if (end < ordered.size) end.toString() else null,
        )
    }

    private data class Sample(val thread: CommentThread, val recency: Int)

    companion object {
        const val PageSize = 4
        const val Total = "5.2K"

        private val maya = CommentAuthor("Maya K.", "@mayak", "sintel")
        private val theo = CommentAuthor("Theo Lang", "@theolang", "bunny")
        private val ines = CommentAuthor("Ines Duarte", "@inesduarte", "dream")
        private val sam = CommentAuthor("Sam Okafor", "@samokafor", "bunny")
        private val rui = CommentAuthor("Rui Chen", "@ruichen", "sintel")
        private val nora = CommentAuthor("Nora Vidal", "@noravidal", "dream")
        private val eli = CommentAuthor("Eli Moreno", "@elimoreno", "bunny")
        private val jun = CommentAuthor("Jun Park", "@junpark", "sintel")

        private fun reply(id: String, author: CommentAuthor, age: String, body: String, likes: String) =
            CommentReply(id = id, author = author, age = age, body = body, likes = likes, authorChannelId = "blender")

        private val samples = listOf(
            Sample(
                CommentThread(
                    id = "c1",
                    author = maya,
                    age = "7 years ago",
                    body = "The lighting still holds up. Every rewatch turns up a detail I missed the first time.",
                    likes = "2.7K",
                    replyCount = 21,
                    replies = listOf(
                        reply("c1r1", theo, "6 years ago", "The forest scene is the one I keep coming back to.", "60"),
                        reply("c1r2", ines, "6 years ago", "Same here, the grass shading looks better than a lot of new shorts.", "16"),
                        reply("c1r3", sam, "5 years ago", "It was also used to show off new monitors at the time.", "29"),
                    ),
                    authorChannelId = "blender",
                ),
                recency = 3,
            ),
            Sample(
                CommentThread(
                    id = "c2",
                    author = theo,
                    age = "2 years ago",
                    body = "Watching this in a browser tab and it still looks like a film print.",
                    likes = "1.9K",
                    replyCount = 14,
                    replies = listOf(
                        reply("c2r1", rui, "2 years ago", "The colour grade does most of the work.", "12"),
                    ),
                    authorChannelId = "studio",
                ),
                recency = 8,
            ),
            Sample(
                CommentThread(
                    id = "c3",
                    author = ines,
                    age = "9 years ago",
                    body = "I saw this playing on televisions at a tech store to show off the panel quality.\n\nNot surprised.",
                    likes = "1.8K",
                    replyCount = 15,
                    replies = listOf(
                        reply("c3r1", nora, "8 years ago", "That is exactly how I first saw it too.", "8"),
                        reply("c3r2", eli, "7 years ago", "The screens were always tuned far too bright.", "5"),
                    ),
                    authorChannelId = "northlight",
                ),
                recency = 2,
            ),
            Sample(
                CommentThread(
                    id = "c4",
                    author = sam,
                    age = "1 year ago",
                    body = "The short that made me install a 3D editor as a teenager.",
                    likes = "980",
                    replyCount = 4,
                    replies = listOf(
                        reply("c4r1", jun, "1 year ago", "Same, it was the first tutorial I followed.", "22"),
                    ),
                    authorChannelId = "handmade",
                ),
                recency = 7,
            ),
            Sample(
                CommentThread(
                    id = "c5",
                    author = rui,
                    age = "4 years ago",
                    body = "The sound design is underrated. Listen with headphones.",
                    likes = "640",
                    replyCount = 0,
                    authorChannelId = "openfield",
                ),
                recency = 5,
            ),
            Sample(
                CommentThread(
                    id = "c6",
                    author = nora,
                    age = "6 months ago",
                    body = "That pan across the meadow is still my favourite shot.",
                    likes = "410",
                    replyCount = 0,
                    authorChannelId = "quietplace",
                ),
                recency = 9,
            ),
            Sample(
                CommentThread(
                    id = "c7",
                    author = eli,
                    age = "3 years ago",
                    body = "A friend sent me the file instead of a streaming link. Best way to watch it.",
                    likes = "275",
                    replyCount = 0,
                    authorChannelId = "studio",
                ),
                recency = 4,
            ),
            Sample(
                CommentThread(
                    id = "c8",
                    author = jun,
                    age = "5 months ago",
                    body = "The making-of notes are worth reading if you are learning rigging.",
                    likes = "188",
                    replyCount = 0,
                    authorChannelId = "blender",
                ),
                recency = 6,
            ),
            Sample(
                CommentThread(
                    id = "c9",
                    author = maya,
                    age = "2 weeks ago",
                    body = "Rewatching after the remaster and the grain looks cleaner without losing the texture.",
                    likes = "64",
                    replyCount = 0,
                    authorChannelId = "blender",
                ),
                recency = 10,
            ),
        )

        fun sampleThreads(sort: CommentSort): List<CommentThread> =
            (if (sort == CommentSort.Top) samples else samples.sortedByDescending { it.recency }).map { it.thread }

        fun thread(id: String): CommentThread = samples.first { it.thread.id == id }.thread
    }
}

/** Debug-only entry points for pinned panel states. */
enum class CommentsScenario(val argument: String) {
    Content("content"),
    Empty("empty"),
    Disabled("disabled"),
    Error("error"),
    LoadMoreError("more-error");

    fun loadsComments(): Boolean = this != CommentsScenario.Disabled

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Content
    }
}
