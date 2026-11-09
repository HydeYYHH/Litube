package com.hhst.youtubelite.post.data

import com.hhst.youtubelite.comments.domain.CommentAuthor
import com.hhst.youtubelite.comments.domain.CommentReply
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.CommentsRepository
import kotlinx.coroutines.delay

class FixturePostCommentsRepository : CommentsRepository {
    override suspend fun comments(target: com.hhst.youtubelite.comments.domain.CommentTarget, sort: CommentSort, cursor: String?): CommentsPage {
        require(target is com.hhst.youtubelite.comments.domain.CommentTarget.Post)
        delay(100)
        val ordered = if (sort == CommentSort.Top) samples else samples.reversed()
        val start = cursor?.toIntOrNull() ?: 0
        if (start >= ordered.size) return CommentsPage(TOTAL, emptyList(), null)
        val end = minOf(start + PAGE_SIZE, ordered.size)
        return CommentsPage(
            TOTAL,
            ordered.subList(start, end),
            if (end < ordered.size) end.toString() else null,
        )
    }

    private companion object {
        const val PAGE_SIZE = 3
        const val TOTAL = "184"

        val studio = CommentAuthor("Blender Studio", "@BlenderStudio", "dream")
        val mira = CommentAuthor("Mira Chen", "@miracreates", "sintel")
        val owen = CommentAuthor("Owen Park", "@owenpark", "bunny")
        val june = CommentAuthor("June Alvarez", "@junealvarez", "dream")

        val samples = listOf(
            CommentThread(
                id = "p1",
                author = mira,
                age = "3 days ago",
                body = "This update is exactly what I hoped for. The production notes make the whole plan feel concrete.",
                likes = "342",
                replyCount = 2,
                replies = listOf(
                    CommentReply("p1r1", studio, "2 days ago", "Thanks for following along — more notes soon.", "88"),
                    CommentReply("p1r2", owen, "2 days ago", "The fundraising breakdown was especially useful.", "31"),
                ),
                authorChannelId = "blender",
            ),
            CommentThread(
                id = "p2",
                author = owen,
                age = "5 days ago",
                body = "Love that the team shared the parts that did not go to plan. That honesty is why I support these projects.",
                likes = "221",
                replyCount = 0,
                authorChannelId = "studio",
            ),
            CommentThread(
                id = "p3",
                author = june,
                age = "1 week ago",
                body = "The still frames in the post look incredible. Can't wait to see how the next stage develops.",
                likes = "164",
                replyCount = 0,
                authorChannelId = "blender",
            ),
            CommentThread(
                id = "p4",
                author = studio,
                age = "1 week ago",
                body = "We're reading every comment here. Thank you for the thoughtful questions and support.",
                likes = "410",
                replyCount = 0,
                authorChannelId = "studio",
            ),
            CommentThread(
                id = "p5",
                author = mira,
                age = "2 weeks ago",
                body = "The behind-the-scenes photos add so much context to the announcement.",
                likes = "76",
                replyCount = 0,
                authorChannelId = "studio",
            ),
        )
    }
}
