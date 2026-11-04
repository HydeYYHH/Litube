package com.hhst.youtubelite.diagnostics

import com.hhst.youtubelite.data.media.MediaException
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import org.junit.Assert.*
import org.junit.Test

class ActionFailureReportTest {
    @Test fun ambiguousResponsesCarryHttpStatusAndReadbackDetails() {
        val report = ActionFailureReport.create("watch_rate", UnconfirmedActionException(200,
            mapOf("response_shape" to "responseContext", "observed_rating" to "unknown")))
        assertTrue(report.text.contains("HTTP status: 200"))
        assertTrue(report.text.contains("response_shape: responseContext"))
        assertTrue(report.text.contains("observed_rating: unknown"))
    }
    @Test fun classifiesOutcomeFromTheServerReplyShape() {
        fun report(error: Throwable) = ActionFailureReport.create("test_op", error)
        assertEquals("unconfirmed", report(UnconfirmedActionException()).summary)
        assertEquals("sign_in_required", report(SignInRequiredException()).summary)
        assertEquals("refused", report(MediaException(
            RequestError(RequestFailure.HTTP, "action_failed", status = 200))).summary)
        assertEquals("rate_limited", report(MediaException(
            RequestError(RequestFailure.HTTP, "rate_limit_exceeded", status = 429, retryAfterMs = 3000L))).summary)
        assertEquals("rate_limited", report(MediaException(
            RequestError(RequestFailure.HTTP, "rate_limit_exceeded", status = 429))).summary)
        assertEquals("refused", report(MediaException(
            RequestError(RequestFailure.HTTP, "service_unavailable", status = 503, retryAfterMs = 3000L))).summary)
        assertEquals("transport", report(MediaException(
            RequestError(RequestFailure.TRANSPORT, "timeout", cause = "SocketTimeout"))).summary)
        assertEquals("unparsed_response", report(MediaException(
            RequestError(RequestFailure.PARSE, "missing_response"))).summary)
        assertEquals("unexpected:IOException", report(java.io.IOException("offline")).summary)
    }

    @Test fun carriesTheHttpStatusAndTheOperation() {
        val report = ActionFailureReport.create("comment_SubmitComment",
            MediaException(RequestError(RequestFailure.HTTP, "action_failed", status = 400)),
            detail = mapOf("video_id" to "abc123"))
        assertTrue(report.text.contains("Operation: comment_SubmitComment"))
        assertTrue(report.text.contains("HTTP status: 400"))
        assertTrue(report.text.contains("Reason: action_failed"))
        assertTrue(report.text.contains("video_id: abc123"))
        assertTrue(report.id.isNotBlank())
    }

    @Test fun includesTheFailureTimeForLogCorrelation() {
        val report = ActionFailureReport.create("save", UnconfirmedActionException(), timestamp = 0L)
        assertTrue(report.text.contains("Time: 1970-01-01T00:00:00.000Z"))
    }

    @Test fun neverEmitsCredentialMaterial() {
        val report = ActionFailureReport.create("comment_SubmitComment", java.io.IOException(
            "Authorization: Bearer very-secret-token Cookie: SID=secret visitor_data=abc"))
        assertFalse(report.text.contains("very-secret-token"))
        assertFalse(report.text.contains("SID=secret"))
        assertFalse(report.text.contains("visitor_data=abc"))
    }
}
