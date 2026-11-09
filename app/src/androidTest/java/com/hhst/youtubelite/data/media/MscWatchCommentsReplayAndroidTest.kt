package com.hhst.youtubelite.data.media

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.CommentPage
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WatchPage
import com.liskovsoft.youtubeapi.videoinfo.V2.loadCommentReplies
import com.liskovsoft.youtubeapi.videoinfo.V2.loadComments
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** A fixed response, one warmup and five measured reads, matching the Channel replay protocol. */
class MscWatchCommentsReplayAndroidTest {
    @Test fun onePublicResponseMeasuresCoreProcessingWithoutNetwork() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mscWatchCommentsReplay") == "1")
        val sample = args.getString("replaySample") ?: "watch"
        val variant = args.getString("replayVariant") ?: "original"
        check(sample in setOf("watch", "top", "reply")) { "replay_sample_invalid" }
        check(variant in setOf("original", "compact")) { "replay_variant_invalid" }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val requested = args.getString("replayFixturePath") ?: "msc-watch-comments-replay/$sample-anonymous.json"
        val fixture = File(requested).let { if (it.isAbsolute) it else File(cache, requested) }.canonicalFile
        check(fixture.path.startsWith(cache.path + File.separator) && fixture.isFile) { "replay_fixture_missing_or_outside_cache" }
        val bytes = fixture.readBytes()
        val raw = String(bytes, Charsets.UTF_8)
        // Preserve every token and escape; org.json serialization can normalize numeric values.
        val compact = compactWhitespace(raw)
        val selected = if (variant == "original") raw else compact
        val opposite = if (variant == "original") compact else raw
        val context = RequestContext()
        val transport = FakeMediaTransport(selected)
        val report = JSONObject().put("schema", 1).put("sample", sample).put("variant", variant)
            .put("readOnly", true).put("signedIn", false).put("realHttpRequests", 0)
            .put("iterations", 5).put("inProcessWarmups", 1)
            .put("timingsMs", JSONArray()).put("cpuTimingsMs", JSONArray())
            .put("fixtureSha256", digest(bytes)).put("fixtureBytes", bytes.size)
            .put("selectedBytes", selected.toByteArray(Charsets.UTF_8).size)
            .put("compactBytes", compact.toByteArray(Charsets.UTF_8).size)
            .put("compactMethod", "remove_json_whitespace_outside_strings")
            .put("timingScope", "one_synchronous_core_load_including_fake_transport_excluding_io_compaction_hashes_validation")
            .put("cpuScope", "current_instrumentation_thread")
        try {
            val expected = load(sample, context, transport)
            val fullHash = fullModelHash(expected)
            val expectedShape = shape(expected)
            var totalElapsedNanos = 0L
            var totalCpuNanos = 0L
            repeat(5) {
                val cpuStarted = Debug.threadCpuTimeNanos()
                val started = SystemClock.elapsedRealtimeNanos()
                val result = load(sample, context, transport)
                val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
                val cpuNanos = Debug.threadCpuTimeNanos() - cpuStarted
                totalElapsedNanos += elapsedNanos
                totalCpuNanos += cpuNanos
                report.getJSONArray("timingsMs").put(elapsedNanos / 1_000_000.0)
                report.getJSONArray("cpuTimingsMs").put(cpuNanos / 1_000_000.0)
                // I/O, hashing and typed equivalence checks remain outside measured intervals.
                assertEquals(expected, result)
                assertEquals(fullHash, fullModelHash(result))
                assertEquals(expectedShape.toString(), shape(result).toString())
            }
            report.put("totalMeasuredElapsedMs", totalElapsedNanos / 1_000_000.0)
                .put("totalMeasuredCpuMs", totalCpuNanos / 1_000_000.0)
                .put("measuredFakeResponses", 5).put("warmupFakeResponses", 1)
                .put("fakeResponsesBeforeValidation", transport.calls)
            assertEquals(6, transport.calls)

            // Validate the other representation only after timing, so it cannot warm the timed path.
            val validation = FakeMediaTransport(opposite, maxCalls = 1)
            val equivalent = load(sample, context, validation)
            assertEquals(1, validation.calls)
            assertEquals(expected, equivalent)
            assertEquals(fullHash, fullModelHash(equivalent))
            assertEquals(expectedShape.toString(), shape(equivalent).toString())
            report.put("fullModelSha256", fullHash)
                .put("structureSha256", digest(expectedShape.toString().toByteArray(Charsets.UTF_8)))
                .put("structure", expectedShape).put("oppositeVariantValidationFakeResponses", validation.calls)
                .put("originalCompactFullModelEqual", true).put("outcome", "completed")
        } catch (failure: Throwable) {
            report.put("outcome", "failed").put("failureType", failure.javaClass.simpleName)
            // Assertion payloads may contain public comment text or opaque continuation capabilities.
            throw AssertionError("watch_comments_replay_failed")
        } finally {
            DeviceEvidence.writeJson("msc-watch-comments-replay.json", report.toString(2))
        }
    }

    private fun load(sample: String, context: RequestContext, http: HttpClient): Any = when (sample) {
        "watch" -> loadWatch(VIDEO, context, http = http).requireValue()
        // A known opening capability isolates one comments response without adding a Watch read.
        "top" -> loadComments(VIDEO, context, continuation = REPLAY_KEY, http = http).requireValue()
        else -> loadCommentReplies(REPLAY_KEY, context, http).requireValue()
    }

    private class FakeMediaTransport(private val body: String, private val maxCalls: Int = 6) : HttpClient {
        var calls = 0
            private set
        override fun execute(request: HttpRequest): HttpReply {
            check(++calls <= maxCalls && request.method == "POST" && request.url.substringBefore('?') == NEXT) { "unexpected_replay_request" }
            check(request.headers.keys.none { it.equals("Authorization", true) || it.equals("Cookie", true) }) { "anonymous_replay_required" }
            return HttpReply(200, body)
        }
    }

    private fun fullModelHash(model: Any): String {
        // Public models hash by data-class toString, but MenuEndpoint alone redacts
        // toString; related MenuEndpoint fields need this supplement.
        val menus = if (model is WatchPage) JSONArray(model.related.map { item ->
            item.menuEndpoint?.let { JSONArray(listOf(it.panelId, it.params)) } ?: JSONObject.NULL
        }).toString() else "[]"
        return digest((model.toString() + "\n" + menus).toByteArray(Charsets.UTF_8))
    }

    private fun shape(model: Any): JSONObject = when (model) {
        is WatchPage -> JSONObject().put("model", "WatchPage").put("related", model.related.size)
            .put("chapters", model.chapters.size).put("queueVideos", model.queue?.videos?.size ?: 0)
            .put("sortOptions", model.commentSortOptions.size).put("next", model.nextKey != null)
            .put("commentsEntry", model.commentsKey != null).put("chatEntry", model.chatKey != null)
            .put("commentCreateEntry", model.commentCreateKey != null).put("commentsTurnedOff", model.commentsTurnedOff)
        is CommentPage -> JSONObject().put("model", "CommentPage").put("comments", model.comments.size)
            .put("runs", model.comments.sumOf { it.runs.size }).put("sortOptions", model.sortOptions.size)
            .put("replyEntries", model.comments.count { it.replyKey != null }).put("next", model.nextKey != null)
            .put("createEntry", model.createKey != null).put("turnedOff", model.turnedOff)
        else -> error("unexpected_replay_model")
    }

    private fun compactWhitespace(body: String): String = buildString(body.length) {
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted) {
                append(char)
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else if (char == '"') {
                append(char)
                quoted = true
            } else if (char != ' ' && char != '\t' && char != '\r' && char != '\n') append(char)
        }
        check(!quoted && !escaped) { "invalid_replay_json_string" }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val VIDEO = "aqz-KE-bpKQ"
        const val REPLAY_KEY = "fixed-public-response-replay"
        const val NEXT = "https://www.youtube.com/youtubei/v1/next"
    }
}
