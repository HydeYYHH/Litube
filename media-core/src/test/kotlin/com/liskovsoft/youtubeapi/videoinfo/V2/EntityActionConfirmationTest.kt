package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Normalized shapes from docs/validation/{probe-shorts-initial,watch-fix-next}.json. */
class EntityActionConfirmationTest {
    private val videoId = "abcdefghijk"
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val context = RequestContext(accessToken = "fixture-token")

    private fun key(id: String, kind: Int) = URLEncoder.encode(Base64.getEncoder().encodeToString(
        byteArrayOf(18, id.length.toByte()) + id.toByteArray(Charsets.UTF_8) + byteArrayOf(32, kind.toByte(), 40, 1)), "UTF-8")

    private fun mutation(name: String, fields: String, id: String, kind: Int,
        type: String = "ENTITY_MUTATION_TYPE_REPLACE", payloadKey: String = key(id, kind)) = """{
        "entityKey":"${key(id, kind)}","type":"$type","payload":{"$name":{"key":"$payloadKey",$fields}}}"""

    private fun response(mutations: String, extra: String = "") = """{"responseContext":{},
        "frameworkUpdates":{"entityBatchUpdate":{"mutations":[$mutations]}}$extra}"""

    private fun http(body: String) = object : HttpClient {
        override fun execute(request: HttpRequest) = HttpReply(200, body)
    }

    @Test fun matchingSubscriptionMutationConfirmsOnlyTheRequestedTargetState() {
        for (subscribed in listOf(false, true)) {
            val body = response(mutation("subscriptionStateEntity", "\"subscribed\":$subscribed", channelId, 51))
            val reply = subscribeChannel(channelId, subscribed, context, http = http(body)).value!!
            assertTrue(reply.confirmed)
            assertEquals(200, reply.httpStatus)
            // Framework state is confirmation only when the endpoint binds an expected target.
            assertFalse(actionReply(jsonObject(body)!!).value!!.confirmed)
        }
    }

    @Test fun matchingRatingMutationsConfirmAllFourExplicitCommands() {
        for (vote in VideoVote.values()) {
            val status = when (vote) {
                VideoVote.LIKE -> "LIKE"
                VideoVote.DISLIKE -> "DISLIKE"
                VideoVote.UNLIKE, VideoVote.UNDISLIKE -> "INDIFFERENT"
            }
            val body = response(mutation("likeStatusEntity", "\"likeStatus\":\"$status\"", videoId, 62))
            assertTrue(voteVideo(videoId, vote, context, http(body)).value!!.confirmed)
        }
    }

    @Test fun unrelatedMalformedOrOldFrameworkStatesNeverConfirmTheWrite() {
        val bodies = listOf(
            response(mutation("likeStatusEntity", "\"likeStatus\":\"LIKE\"", "other_video", 62)),
            response(mutation("likeStatusEntity", "\"likeStatus\":\"INDIFFERENT\"", videoId, 62)),
            response(mutation("likeStatusEntity", "\"likeStatus\":\"LIKE\"", videoId, 62,
                payloadKey = key("other_video", 62))),
            response(mutation("likeStatusEntity", "\"likeStatus\":\"LIKE\"", videoId, 62,
                type = "ENTITY_MUTATION_TYPE_DELETE")),
            response(mutation("likeStatusEntity", "\"likeStatus\":\"UNKNOWN\"", videoId, 62)),
            """{"responseContext":{},"frameworkUpdates":{"entityBatchUpdate":{"mutations":[]}}}""",
        )
        for (body in bodies) assertFalse(voteVideo(videoId, VideoVote.LIKE, context, http(body)).value!!.confirmed)
    }

    @Test fun frameworkStateCannotOverrideAPendingMalformedOrRefusedActionResult() {
        val state = mutation("subscriptionStateEntity", "\"subscribed\":true", channelId, 51)
        for (extra in listOf(",\"actionResult\":{\"status\":\"PENDING\"}",
            ",\"actionResults\":[{\"status\":\"STATUS_SUCCEEDED\"},42]", ",\"status\":\"PENDING\"")) {
            assertFalse(subscribeChannel(channelId, true, context, http = http(response(state, extra))).value!!.confirmed)
        }
        assertFalse(subscribeChannel(channelId, true, context,
            http = http(response(state, ",\"actionResult\":{\"status\":\"STATUS_FAILED\"}"))).ok)
    }

    @Test fun confirmationUsesTheLastMutationForTheTargetAndDoesNotReviveDeletedState() {
        val liked = mutation("likeStatusEntity", "\"likeStatus\":\"LIKE\"", videoId, 62)
        val neutral = mutation("likeStatusEntity", "\"likeStatus\":\"INDIFFERENT\"", videoId, 62)
        val deleted = """{"entityKey":"${key(videoId, 62)}","type":"ENTITY_MUTATION_TYPE_DELETE"}"""
        assertTrue(voteVideo(videoId, VideoVote.LIKE, context, http(response("$neutral,$liked"))).value!!.confirmed)
        assertFalse(voteVideo(videoId, VideoVote.LIKE, context, http(response("$liked,$neutral"))).value!!.confirmed)
        assertFalse(voteVideo(videoId, VideoVote.LIKE, context, http(response("$liked,$deleted"))).value!!.confirmed)
    }

    @Test fun watchReadUsesTheTargetedFrameworkStateInsteadOfAnOlderInlineRenderer() {
        val body = response(mutation("likeStatusEntity", "\"likeStatus\":\"LIKE\"", videoId, 62),
            ",\"likeButtonRenderer\":{\"likeStatus\":\"INDIFFERENT\"}")
        assertEquals("LIKE", watchPage(body, videoId).rating)
        assertEquals("INDIFFERENT", watchPage(body, "other_video").rating)
        val unrelated = response(mutation("likeStatusEntity", "\"likeStatus\":\"LIKE\"", "other_video", 62))
        assertNull(watchPage(unrelated, videoId).rating)
        val deleted = response("""{"entityKey":"${key(videoId, 62)}","type":"ENTITY_MUTATION_TYPE_DELETE"}""",
            ",\"likeButtonRenderer\":{\"likeStatus\":\"LIKE\"}")
        assertNull(watchPage(deleted, videoId).rating)
    }

    @Test fun channelReadUsesOnlyItsOwnFrameworkSubscriptionState() {
        val body = response(mutation("subscriptionStateEntity", "\"subscribed\":true", channelId, 51),
            ",\"subscribeButtonRenderer\":{\"subscribed\":false}")
        assertEquals(true, channelPage(body, channelId).subscribed)
        assertEquals(false, channelPage(body, "UCother").subscribed)
    }

    @Test fun legacyUnkeyedInlineFrameworkStateStillReadsButCannotConfirmAWritingAction() {
        val body = """{"frameworkUpdates":{"likeStatusEntity":{"likeStatus":"LIKE"}}}"""
        assertEquals("LIKE", watchPage(body, videoId).rating)
        assertFalse(voteVideo(videoId, VideoVote.LIKE, context, http(body)).value!!.confirmed)
    }
}
