package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.comments.domain.CommentSort as AppSort
import com.hhst.youtubelite.comments.domain.CommentTarget
import com.hhst.youtubelite.comments.domain.CommentThread
import com.hhst.youtubelite.comments.domain.CommentsPage
import com.hhst.youtubelite.comments.domain.RepliesPage
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.CommentPage
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.loadCommentReplies
import com.liskovsoft.youtubeapi.videoinfo.V2.loadComments
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Explicit public-network experiment: -e network 1 -e mscLiveComments 1.
 * Cold = empty Watch result cache (variants share one client/pool);
 * no performance threshold is asserted.
 */
class MscLiveCommentsAndroidTest {
    private lateinit var probe: MscLiveNetworkProbe
    private val outcomes = JSONArray()
    private val report = JSONObject()

    @Test fun comparePublicColdSharedAndReplyReads() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveComments") == "1")
        val proxyHost = args.getString("liveProxyHost")?.takeIf(String::isNotBlank)
        val proxyPort = args.getString("liveProxyPort")?.toIntOrNull() ?: 7890
        val context = RequestContext(language = "en", region = "US")
        probe = MscLiveNetworkProbe(GlobalContext.get().get<OkHttpClient>(), proxyHost, proxyPort) { context }
        val rounds = (args.getString("liveRounds")?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val candidates = args.getString("videoId")?.takeIf(String::isNotBlank)?.let(::listOf)
            ?: listOf("aqz-KE-bpKQ", "dQw4w9WgXc")
        report.put("experiment", "public_watch_comments_replies")
            .put("readOnly", true).put("signedIn", false).put("cookies", false).put("profile", "WEB")
            .put("rounds", rounds).put("candidates", JSONArray(candidates))
            .put("coldDefinition", "empty SharedWatchReads cache; shared connection pool across all variants")
            .put("retryPolicy", "both cold variants use the existing bounded idempotent MediaClient retry")
            .put("timingInterpretation", "small alternating samples; request reductions and network latency reported separately")
        try {
            var selected: String? = null
            var commentsOnly: String? = null
            for (id in candidates.take(2)) {
                val repository = MediaCommentsRepository(probe.client, SharedWatchReads())
                val page = observe("discovery_$id", { repository.comments(CommentTarget.Video(id), AppSort.Top, null) }, ::describeComments)
                if (page?.threads?.isNotEmpty() == true) {
                    if (commentsOnly == null) commentsOnly = id
                    if (replyThread(page) != null) { selected = id; break }
                }
            }
            selected = selected ?: commentsOnly
            report.put("selectedVideoId", selected ?: JSONObject.NULL)
            if (selected == null) {
                report.put("outcome", "no_usable_public_comments")
                save()
                assumeTrue("Public comments unavailable; inspect msc-live-comments.json for typed outcomes", false)
                return@runBlocking
            }
            val videoId = requireNotNull(selected)
            val target = CommentTarget.Video(videoId)
            var successfulVariants = 0
            var successfulReplyPages = 0
            repeat(rounds) { round ->
                // Alternate order so the legacy lane is not always the connection warmer.
                val order = when (round % 3) {
                    0 -> listOf("legacy_cold", "repository_cold", "shared_watch")
                    1 -> listOf("shared_watch", "repository_cold", "legacy_cold")
                    else -> listOf("repository_cold", "legacy_cold", "shared_watch")
                }
                for (variant in order) {
                    val label = "round_${round + 1}_$variant"
                    if (variant == "legacy_cold") {
                        val legacy = observe(label, { legacyNewest(videoId) }, ::describeLegacy)
                        if (legacy?.page?.comments?.isNotEmpty() == true) successfulVariants++
                    } else {
                        val shared = SharedWatchReads()
                        val repository = MediaCommentsRepository(probe.client, shared)
                        if (variant == "shared_watch") {
                            // Reported separately rather than charged to a supposedly faster cold opening.
                            val opened = observe("${label}_watch_prefetch", {
                                probe.client.readIdempotent { ctx, http ->
                                    shared.read(videoId, ctx, http, fresh = false) { id, session, transport ->
                                        loadWatch(id, session, http = transport)
                                    }.requireValue()
                                }
                            }) { page -> JSONObject().put("commentEntry", page.commentsKey != null)
                                .put("openingNewestEntry", page.commentSortOptions.any { it.title?.contains("newest", true) == true }) }
                            if (opened == null) continue
                        }
                        val page = observe(label, { repository.comments(target, AppSort.Newest, null) }, ::describeComments)
                        if (page?.threads?.isNotEmpty() == true) successfulVariants++
                        // Exercise one real reply page and at most one continuation per
                        // round, using a freshly returned key rather than a stored token.
                        if (variant == "shared_watch" && page != null) {
                            val thread = replyThread(page) ?: observe("${label}_reply_parent_top", {
                                repository.comments(target, AppSort.Top, null)
                            }, ::describeComments)?.let(::replyThread)
                            if (thread == null) {
                                outcomes.put(JSONObject().put("label", "${label}_replies").put("outcome", "no_reply_entry"))
                                save()
                            } else {
                                val first = observe("${label}_reply_first", { repository.repliesPage(target, thread, null) }, ::describeReplies)
                                if (first?.replies?.isNotEmpty() == true) successfulReplyPages++
                                val cursor = first?.nextCursor
                                if (cursor != null && cursor != thread.replyKey) {
                                    val next = observe("${label}_reply_next", { repository.repliesPage(target, thread, cursor) }, ::describeReplies)
                                    if (next?.replies?.isNotEmpty() == true) successfulReplyPages++
                                } else if (cursor != null) {
                                    outcomes.put(JSONObject().put("label", "${label}_reply_next").put("outcome", "repeated_opening_cursor"))
                                }
                            }
                        }
                    }
                }
            }
            // Exploratory, outside the alternating comparison: changing sort may re-fetch the
            // Top page to rediscover its advertised Newest key.
            val transitionRepository = MediaCommentsRepository(probe.client, SharedWatchReads())
            val top = observe("transition_top_preload", { transitionRepository.comments(target, AppSort.Top, null) }, ::describeComments)
            if (top != null) observe("transition_top_to_newest", {
                transitionRepository.comments(target, AppSort.Newest, null)
            }, ::describeComments)
            report.put("successfulCommentVariants", successfulVariants)
                .put("successfulReplyPages", successfulReplyPages)
                .put("outcome", when {
                    successfulVariants == 0 -> "comment_comparison_unavailable"
                    successfulVariants < rounds * 3 -> "partial_comment_comparison"
                    successfulReplyPages == 0 -> "comments_available_replies_unavailable"
                    else -> "completed"
                })
            assumeTrue("No usable comment comparison; inspect msc-live-comments.json", successfulVariants > 0)
        } finally {
            save()
        }
    }

    /** The core recipe before the repository's opening-sort shortcut; no writes. */
    private suspend fun legacyNewest(videoId: String): Legacy = probe.client.readIdempotent { context, http ->
        val opening = loadWatch(videoId, context, http = http).requireValue()
        var page = loadComments(videoId, context, http = http, opened = opening).requireValue()
        val option = page.sortOptions.firstOrNull { it.title?.contains("newest", true) == true }
        if (option != null && !option.selected)
            page = loadCommentReplies(option.key, context, http).requireValue().copy(total = page.total)
        Legacy(page, opening.commentSortOptions.any { it.title?.contains("newest", true) == true }, option != null)
    }

    private data class Legacy(val page: CommentPage, val openingNewest: Boolean, val newestOffered: Boolean)
    private fun describeLegacy(value: Legacy) = JSONObject().put("comments", value.page.comments.size)
        .put("next", value.page.nextKey != null).put("openingNewestEntry", value.openingNewest)
        .put("newestOffered", value.newestOffered).put("sortFallback", !value.newestOffered)
    private fun describeComments(page: CommentsPage) = JSONObject().put("comments", page.threads.size)
        .put("next", page.nextCursor != null).put("turnedOff", page.turnedOff).put("sortFallback", page.sortFallback)
        .put("contentOutcome", when {
            page.turnedOff -> "comments_off"
            page.threads.isNotEmpty() -> "comments"
            page.nextCursor != null -> "empty_with_continuation"
            else -> "no_comments_or_entry"
        })
        .put("replyEntries", page.threads.count { it.replyKey != null })
        .put("maxReplyCount", page.threads.maxOfOrNull { it.replyCount } ?: 0)
    private fun describeReplies(page: RepliesPage) = JSONObject().put("replies", page.replies.size)
        .put("next", page.nextCursor != null).put("uniqueReplies", page.replies.distinctBy { it.id }.size)
        .put("contentOutcome", if (page.replies.isNotEmpty()) "replies" else if (page.nextCursor != null) "empty_with_continuation" else "no_replies")
    private fun replyThread(page: CommentsPage): CommentThread? =
        page.threads.filter { it.replyKey != null }.maxByOrNull { it.replyCount }

    private suspend fun <T> observe(label: String, block: suspend () -> T, describe: (T) -> JSONObject): T? {
        return try {
            val value = probe.sample(label, block)
            outcomes.put(describe(value).put("label", label).put("outcome", "ok"))
            value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            outcomes.put(JSONObject().put("label", label).put("outcome", "failed")
                .put("failureType", failure.javaClass.simpleName).apply {
                    (failure as? MediaException)?.failure?.let { error ->
                        put("kind", error.kind.name).put("code", error.code).put("status", error.status ?: JSONObject.NULL)
                    }
                })
            null
        } finally {
            save()
        }
    }

    private fun save() {
        report.put("operations", outcomes).put("network", probe.report())
        DeviceEvidence.writeJson("msc-live-comments.json", report.toString(2))
    }
}
