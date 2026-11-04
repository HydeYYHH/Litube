package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActionMetadataTest {
    private val videoId = "abcdefghijk"
    private val key = "EgthYmNkZWZnaGlqayA-KAE%3D"

    @Test fun unconfirmedActionsExposeOnlyAllowlistedProtocolAndTargetStateDetails() {
        val context = RequestContext(accessToken = "secret-token", onBehalfOfUser = "private-account")
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, """{"responseContext":{"private":"secret-response"},
                "frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"entityKey":"$key",
                  "type":"ENTITY_MUTATION_TYPE_REPLACE","payload":{"likeStatusEntity":{
                    "key":"$key","likeStatus":"INDIFFERENT","private":"private-payload"}}}]}}}""")
        }
        val reply = voteVideo(videoId, VideoVote.LIKE, context, http).value!!
        assertFalse(reply.confirmed)
        assertEquals(mapOf("request_client" to "TVHTML5", "request_auth" to "oauth", "request_delegated" to "true",
            "request_client_version" to "7.20260901.15.00", "request_action_context" to "complete",
            "response_signed_in" to "unknown",
            "target_entity_kind" to "like_status", "target_entity_state" to "INDIFFERENT",
            "target_entity_mutation" to "replace"), reply.metadata)
        for (privateValue in listOf("secret-token", "private-account", "secret-response", "private-payload", key, videoId))
            assertFalse(reply.metadata.toString().contains(privateValue))
    }

    @Test fun clientDiagnosticsRejectUnexpectedValuesAndNeverExposeTheUserAgent() {
        val query = PlayerQuery("https://www.youtube.com/youtubei/v1/like/like",
            mapOf("Authorization" to "private-authorization", "Cookie" to "private-cookie", "User-Agent" to "private-agent"),
            """{"context":{"client":{"clientName":"private-client","clientVersion":"7.private-version"}}}""")
        val metadata = actionRequestMetadata(query)
        assertEquals("other", metadata["request_client"])
        assertEquals("other", metadata["request_client_version"])
        assertEquals("other", metadata["request_action_context"])
        assertEquals("other", metadata["request_auth"])
        assertFalse(metadata.toString().contains("private-"))
        for (version in listOf("", "1..2", "1.2\nprivate", "9".repeat(65))) {
            val fields = mapOf("clientName" to Json.Str("TVHTML5"), "clientVersion" to Json.Str(version))
            val body = Json.Obj(mapOf("context" to Json.Obj(mapOf("client" to Json.Obj(fields))))).encode()
            assertEquals("other", actionRequestMetadata(query.copy(body = body))["request_client_version"])
        }
    }

    @Test fun actionContextReportsCoverageWithoutChangingExplicitMalformedFields() {
        val query = PlayerQuery("https://www.youtube.com/youtubei/v1/like/like", emptyMap(),
            """{"context":{"client":{"clientName":"TVHTML5","clientVersion":"1.2.3"},"user":"private-user"}}""")
        assertEquals("partial", actionRequestMetadata(withTvActionContext(query))["request_action_context"])
        assertEquals("1.2.3", actionRequestMetadata(query)["request_client_version"])
        assertFalse(actionRequestMetadata(query).toString().contains("private-user"))
        assertEquals("other", actionRequestMetadata(query.copy(body = "invalid"))["request_action_context"])
    }

    @Test fun responseIdentityPrefersTheExplicitLoggedOutFlagOverServiceTracking() {
        for (loggedOut in listOf(true, false)) {
            val root = jsonObject("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":$loggedOut},
                "serviceTrackingParams":[{"params":[{"key":"logged_in","value":"${if (loggedOut) "1" else "0"}"}]}]}}""")!!
            assertEquals((!loggedOut).toString(), actionResponseMetadata(root)["response_signed_in"])
        }
    }

    @Test fun responseIdentityUsesOnlyRecognizedTrackingValuesAndOtherwiseStaysUnknown() {
        for ((value, expected) in listOf("1" to "true", "0" to "false", "private-value" to "unknown")) {
            val root = jsonObject("""{"responseContext":{"serviceTrackingParams":[{
                "params":[{"key":"logged_in","value":"$value"},{"key":"private-key","value":"private-token"}]}]}}""")!!
            assertEquals(mapOf("response_signed_in" to expected), actionResponseMetadata(root))
        }
        assertEquals("unknown", actionResponseMetadata(jsonObject("{}")!!)["response_signed_in"])
        assertEquals("unknown", actionResponseMetadata(jsonObject(
            """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":"private-value"}}}""")!!)["response_signed_in"])
    }

    @Test fun aGuestResponseIsDiagnosedWithoutConfirmingAnUnacknowledgedWrite() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200,
                """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":true}},"frameworkUpdates":{}}""")
        }
        val reply = voteVideo(videoId, VideoVote.LIKE, RequestContext(accessToken = "private-token"), http).value!!
        assertFalse(reply.confirmed)
        assertEquals("false", reply.metadata["response_signed_in"])
    }

    @Test fun missingAndDeletedTargetStatesAreExplicitlyUnknown() {
        for ((mutations, expected) in listOf("[]" to "missing",
            """[{"entityKey":"$key","type":"ENTITY_MUTATION_TYPE_DELETE"}]""" to "delete")) {
            val http = object : HttpClient {
                override fun execute(request: HttpRequest) = HttpReply(200,
                    """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":$mutations}}}""")
            }
            val reply = voteVideo(videoId, VideoVote.LIKE, RequestContext(accessToken = "token"), http).value!!
            assertFalse(reply.confirmed)
            assertEquals("unknown", reply.metadata["target_entity_state"])
            assertEquals(expected, reply.metadata["target_entity_mutation"])
        }
    }
}
