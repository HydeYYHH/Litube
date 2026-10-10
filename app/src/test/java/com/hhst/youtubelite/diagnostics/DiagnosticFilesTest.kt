package com.hhst.youtubelite.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun pruningExpiresOldFilesAndPreservesCriticalBeforeOrdinary() {
        val now = 1_000_000_000L
        val expired = temp.newFile("crash-critical-expired.jsonl").apply { writeText("old"); setLastModified(now - DiagnosticFiles.RETENTION_MS - 1) }
        val regular = temp.newFile("player-events-recent.jsonl").apply { writeText("0123456789"); setLastModified(now - 1) }
        val critical = temp.newFile("player-critical-old.jsonl").apply { writeText("0123456789"); setLastModified(now - 100) }
        DiagnosticFiles(temp.root) { now }.prune(maxBytes = 10)
        assertFalse(expired.exists()); assertFalse(regular.exists()); assertTrue(critical.exists())
    }
    @Test fun exceptionReportsKeepCallSitesWithoutResponseBodies() {
        val failure = IllegalStateException("{\"body\":\"private response contents\"}", IllegalArgumentException("secret token"))
        val report = DiagnosticRedaction.failure(failure)
        assertTrue(report.contains("IllegalStateException")); assertTrue(report.contains("IllegalArgumentException"))
        assertTrue(report.contains("DiagnosticFilesTest")); assertFalse(report.contains("private response contents"))
        assertFalse(report.contains("secret token"))
    }
    @Test fun redactsCredentialsQueriesAndPrivatePaths() {
        val raw = "Authorization: Bearer abc_secret Cookie: SID=account; token=secret https://host/path?sig=signed&token=secret /data/user/0/pkg/file"
        val redacted = DiagnosticRedaction.text(raw)
        assertFalse(redacted.contains("abc_secret")); assertFalse(redacted.contains("account")); assertFalse(redacted.contains("signed"))
        assertFalse(redacted.contains("/data/user"))
        assertEquals("[redacted]", DiagnosticRedaction.field("accessToken", "secret"))
    }
    @Test fun ipv6AddressesAndPrivatePathsRemainCorrelatableWithoutDisclosure() {
        val raw = "receiver [fe80::1234:abcd%wlan0] and 192.168.1.8 /data/user/0/pkg/private"
        val redacted = DiagnosticRedaction.text(raw)
        assertFalse(redacted.contains("fe80")); assertFalse(redacted.contains("wlan0"))
        assertFalse(redacted.contains("192.168")); assertFalse(redacted.contains("/data/user"))
        assertEquals(redacted, DiagnosticRedaction.text(raw))
    }
    @Test fun oversizedLegacySegmentsAreReportedAsPartialInsteadOfSilentlyTruncated() {
        temp.newFile("legacy-events.jsonl").writeText("{}\n".repeat(800_000))
        DiagnosticFiles(temp.root).use { files ->
            val snapshot = files.snapshot { false }
            assertEquals(1, snapshot.second)
            assertTrue(files.snapshotTruncatedBytes > 0)
            assertTrue(snapshot.first.values.single().toByteArray().size <= 2 * 1024 * 1024)
        }
    }
}
