package com.hhst.youtubelite.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackFailureReportTest {
    private val context = DiagnosticContext(videoId = "GjjK6yD5I9I", generation = 7)

    @Test fun reportRetainsIdentityErrorChainAndOnlyItsOwnEvents() {
        val event = DiagnosticEvent(time = 1, elapsedMs = 20, sequence = 1, process = "app", pid = 1,
            session = "test", category = "EXTRACTOR", level = DiagnosticLevel.ERROR, event = "media",
            context = context.child(), fields = mapOf("http_status" to 403, "profile" to "WEB"))
        val other = event.copy(context = DiagnosticContext(videoId = "FEZS601QSVE"), event = "unrelated-video")
        val report = PlaybackFailureReport.create("Failed", IOException("Source failed", IOException("MEDIA_ERROR_BODY")),
            "playback", context, mapOf("android_sdk" to 35), mapOf("full_refreshes" to 1), listOf(event, other), 0)
        assertEquals("GjjK6yD5I9I", report.videoId)
        assertTrue(report.text.contains("1970-01-01T00:00:00.000Z"))
        listOf("Video: GjjK6yD5I9I", "Generation: 7", "Phase: playback", "MEDIA_ERROR_BODY", "http_status=403", "profile=WEB",
            "android_sdk: 35", "full_refreshes: 1", "java.io.IOException").forEach { assertTrue(it, report.text.contains(it)) }
        assertFalse(report.text.contains("unrelated-video"))
    }

    @Test fun credentialsAndSignedResourcesAreRemovedFromEveryReportSection() {
        val failure = IOException("https://cdn.example/video?sig=secret-signature token=secret-token\nCookie: secret-cookie\n/storage/emulated/0/private.txt")
        val report = PlaybackFailureReport.create("Failed", failure, "prepare", context,
            mapOf("authorization" to "secret-auth", "network" to mapOf("host" to "secret-host")),
            mapOf("request" to DiagnosticRedaction.resource("https://r1.googlevideo.com/videoplayback?itag=140&sig=secret-signature")),
            emptyList())
        listOf("secret-signature", "secret-token", "secret-cookie", "secret-auth", "secret-host", "/storage/emulated").forEach {
            assertFalse(it, report.text.contains(it))
        }
        assertTrue(report.text.contains("itag=140"))
        assertTrue(report.text.contains("host_family=googlevideo"))
    }

    @Test fun cyclicFailuresAndOversizedStacksRemainBounded() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        first.stackTrace = Array(40) { StackTraceElement("a".repeat(4000), "method", "File.kt", it) }
        val report = PlaybackFailureReport.create("Failed", first, "playback", context, emptyMap(), emptyMap(), emptyList())
        assertTrue(report.text.length <= 48_000)
        assertTrue(report.text.contains("[report truncated]"))
    }

    @Test fun resolutionAttemptsRemainReadableBeforeMediaPreparation() {
        val report = PlaybackFailureReport.create("Verification required", IOException("VERIFICATION:playability_bot_check"),
            "prepare", context, emptyMap(), mapOf("account_state" to "ANONYMOUS", "configuration_source" to "same-session-watch",
                "player_attempts" to listOf(mapOf("client" to "VISIONOS", "status" to "LOGIN_REQUIRED",
                    "outcome" to "playability_bot_check", "duration_ms" to 300))), emptyList())
        listOf("ANONYMOUS", "same-session-watch", "VISIONOS", "LOGIN_REQUIRED", "playability_bot_check", "300").forEach {
            assertTrue(it, report.text.contains(it))
        }
    }
}
