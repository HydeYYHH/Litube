package com.hhst.youtubelite.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticProgressWatchdogTest {
    @Test fun noFramesAfterReadCompletionTriggersAtTenSecondsAndStacksAtFifteen() {
        val transitions = mutableListOf<String>(); var stacks = 0
        val monitor = DiagnosticProgressWatchdog(10_000, { state, _ -> transitions += state }, 15_000, { stacks++ })
        monitor.sample(0, 0, true); monitor.sample(9_999, 0, true)
        assertTrue(transitions.isEmpty())
        monitor.sample(10_000, 0, true); monitor.sample(15_000, 0, true); monitor.sample(20_000, 0, true)
        assertEquals(listOf("stalled"), transitions); assertEquals(1, stacks)
        monitor.sample(20_001, 1, true)
        assertEquals("recovered", transitions.last())
    }
    @Test fun switchCancelPauseAndNormalEndNeverReportRecovery() {
        for (reason in listOf("superseded", "cancelled", "deferred", "completed")) {
            val states = mutableListOf<String>()
            val monitor = DiagnosticProgressWatchdog(10_000, { state, _ -> states += state })
            monitor.sample(0, null, true); monitor.sample(10_000, null, true); monitor.reset(reason)
            monitor.sample(20_000, 1, true)
            assertEquals(listOf("stalled", reason), states)
        }
    }
    @Test fun downloadsOnlyStallDuringActualTransferAndNotNetworkWait() {
        val states = mutableListOf<String>()
        val monitor = DiagnosticProgressWatchdog(30_000, { state, _ -> states += state })
        monitor.sample(0, 0, false); monitor.sample(100_000, 0, false)
        assertTrue(states.isEmpty())
        monitor.sample(100_001, 0, true); monitor.sample(129_999, 0, true)
        assertTrue(states.isEmpty())
        monitor.sample(130_001, 0, true); monitor.sample(130_002, 0, false)
        assertEquals(listOf("stalled", "deferred"), states)
    }
    @Test fun bridgeAcceptsOnlyWhitelistedTypedFieldsAndCurrentGeneration() {
        val raw = """{"code":"selector_missing","generation":2,"video_id":"Wh9klmWAm5s","count":3,"reason":"selector_missing","cookie":"secret","script_body":"secret"}"""
        assertNull(DiagnosticJsEvents.parse(raw, 1))
        val report = DiagnosticJsEvents.parse(raw, 2)!!
        assertTrue(report.failed); assertEquals(3, report.fields["count"])
        assertFalse(report.fields.containsKey("cookie")); assertFalse(report.fields.containsKey("script_body"))
        assertNull(DiagnosticJsEvents.parse("""{"code":"console","generation":2}""", 2))
    }
}
