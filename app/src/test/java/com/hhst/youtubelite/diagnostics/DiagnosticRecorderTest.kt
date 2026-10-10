package com.hhst.youtubelite.diagnostics

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiagnosticRecorderTest {
    private class Fixture {
        var now = 0L
        val ordinary = mutableListOf<DiagnosticEvent>()
        val retained = mutableListOf<DiagnosticEvent>()
        val recorder = DiagnosticRecorder("main", 42, "session", { 1_000_000 + now }, { now }, { rows, priority ->
            (if (priority) retained else ordinary).addAll(rows)
        })
        val context = DiagnosticContext(videoId = "Wh9klmWAm5s", generation = 3)
        fun event(code: String, level: DiagnosticLevel = DiagnosticLevel.DEBUG, fields: Map<String, Any?> = emptyMap(),
            context: DiagnosticContext? = this.context, failure: Throwable? = null, kind: String = "event") =
            recorder.record("PLAYER", code, level, context, fields, failure, kind = kind)
    }
    @Test fun contextAndFieldTypesSurviveChildrenAndSerialization() {
        val f = Fixture(); val child = f.context.child("request-1")
        f.event("ready", DiagnosticLevel.INFO, mapOf("bytes" to 64L, "ready" to true, "unknown" to null), child)
        val row = f.ordinary.single()
        assertEquals(f.context.operationId, row.context?.parentOperationId)
        assertEquals(f.context.traceId, row.context?.traceId)
        assertEquals("Wh9klmWAm5s", row.context?.videoId)
        val json = com.google.gson.JsonParser.parseString(Gson().toJson(row)).asJsonObject
        assertTrue(json.getAsJsonObject("fields").get("bytes").asJsonPrimitive.isNumber)
        assertTrue(json.getAsJsonObject("fields").get("ready").asJsonPrimitive.isBoolean)
    }
    @Test fun ringRespectsAgeCountAndByteCaps() {
        val f = Fixture()
        repeat(1500) { f.event("progress", fields = mapOf("bytes" to it)) }
        assertEquals(1000, f.recorder.recent().size)
        repeat(1000) { f.event("large", fields = (0..20).associate { "item_$it" to "a".repeat(1024) }) }
        assertTrue((f.recorder.statistics()["ring_bytes"] as Int) <= DiagnosticRecorder.MAX_RING_BYTES)
        f.now = 30_001
        assertTrue(f.recorder.recent().isEmpty())
    }
    @Test fun largeRowsAreTruncatedAndCounted() {
        val f = Fixture()
        f.event("large", fields = (0..63).associate { "item_$it" to "a".repeat(1024) })
        assertEquals(1L, f.recorder.truncated)
        assertEquals(true, f.recorder.recent().single().fields["truncated"])
    }
    @Test fun thousandsOfSuccessfulChildRequestsProduceOneAggregate() {
        val f = Fixture()
        repeat(1000) { f.event("request.end", fields = mapOf("bytes_read" to 10), context = f.context.child(it.toString())) }
        assertTrue(f.ordinary.isEmpty())
        f.now = 30_000; f.recorder.tick()
        assertEquals(1, f.ordinary.size)
        assertEquals(1000L, f.ordinary.single().fields["count"])
        assertEquals(10000L, f.ordinary.single().fields["total_bytes_read"])
        assertEquals(999L, f.recorder.merged)
    }
    @Test fun repeatedWarningsFlushAfterTenSecondsAndAtOperationEnd() {
        val f = Fixture()
        repeat(5) { f.event("slow", DiagnosticLevel.WARN, mapOf("reason" to "first_byte")) }
        assertEquals(1, f.ordinary.size)
        f.now = 10_000; f.recorder.tick()
        assertEquals(5L, f.ordinary.last().fields["count"])
        repeat(2) { f.event("slow", DiagnosticLevel.WARN, mapOf("reason" to "connect")) }
        f.recorder.finish(f.context)
        assertEquals(2L, f.ordinary.last().fields["count"])
    }
    @Test fun incidentKeepsRelatedPrehistorySnapshotAndBoundedFollowup() {
        val f = Fixture()
        f.event("old_video", context = DiagnosticContext(videoId = "wDIrpvH8MzE"))
        f.event("first_bytes", context = f.context.child())
        f.event("state_snapshot", fields = mapOf("phase" to "awaiting_first_frame"), kind = "snapshot")
        f.now = 10_000; f.event("stalled", DiagnosticLevel.ERROR, mapOf("reason" to "no_frames"))
        assertTrue(f.retained.any { it.event == "first_bytes" })
        assertTrue(f.retained.any { it.recordKind == "snapshot" })
        assertFalse(f.retained.any { it.event == "old_video" })
        f.now = 20_000; f.event("stalled", DiagnosticLevel.ERROR, mapOf("reason" to "no_frames"))
        assertEquals(1, f.retained.count { it.recordKind == "incident_start" })
        f.now = 25_000; f.recorder.tick()
        assertTrue(f.retained.any { it.recordKind == "incident_end" && it.fields["reason"] == "window_end" })
    }
    @Test fun childEndDoesNotCloseItsParentIncident() {
        val f = Fixture(); val child = f.context.child()
        f.event("stalled", DiagnosticLevel.ERROR)
        f.recorder.finish(child)
        assertFalse(f.retained.any { it.recordKind == "incident_end" })
        f.recorder.finish(f.context)
        assertTrue(f.retained.any { it.recordKind == "incident_end" })
    }
    @Test fun sharedExtractionJoinRetainsTheWorkersPrehistory() {
        val f = Fixture(); val worker = DiagnosticContext(videoId = "Wh9klmWAm5s")
        f.event("worker_headers", fields = mapOf("status" to 200), context = worker)
        f.event("shared_operation_joined", fields = mapOf("worker_trace_id" to worker.traceId))
        f.event("stalled", DiagnosticLevel.ERROR)
        assertTrue(f.retained.any { it.event == "worker_headers" && it.context?.traceId == worker.traceId })
        assertTrue(f.retained.any { it.event == "shared_operation_joined" })
    }
    @Test fun processFailureKeepsAllActiveModuleContexts() {
        val f = Fixture(); val download = DiagnosticContext(videoId = "wDIrpvH8MzE", taskId = "task")
        f.event("player_snapshot", fields = mapOf("phase" to "awaiting_first_frame"), kind = "snapshot")
        f.event("download_snapshot", fields = mapOf("phase" to "transfer"), context = download, kind = "snapshot")
        f.event("uncaught", DiagnosticLevel.FATAL, context = null)
        assertTrue(f.retained.any { it.event == "player_snapshot" && it.context == f.context })
        assertTrue(f.retained.any { it.event == "download_snapshot" && it.context == download })
    }
    @Test fun repeatedCauseUsesOneErrorIdAndOneStack() {
        val f = Fixture(); val cause = IOException("MEDIA_SESSION_CHANGED")
        f.event("source_failed", DiagnosticLevel.ERROR, failure = cause)
        f.event("player_failed", DiagnosticLevel.ERROR, failure = IllegalStateException("private-body", cause))
        val errors = f.recorder.recent().mapNotNull { it.error }
        assertEquals(errors[0].errorId, errors[1].errorId)
        assertTrue(errors[0].causes.any { it.frames.isNotEmpty() })
        assertTrue(errors[1].stackReference)
        assertTrue(errors[1].causes.all { it.frames.isEmpty() })
        assertFalse(Gson().toJson(errors).contains("private-body"))
    }
    @Test fun operationHasExactlyOneTerminalEvenAfterCancellationAndFinally() {
        val terminal = mutableListOf<DiagnosticOutcome>()
        val operation = DiagnosticOperation(DiagnosticContext()) { result, _, _, _ -> terminal += result }
        operation.finish(DiagnosticOutcome.CANCELLED, "user_cancel")
        operation.finish(DiagnosticOutcome.SUCCESS)
        operation.finish(DiagnosticOutcome.SUPERSEDED)
        assertEquals(listOf(DiagnosticOutcome.CANCELLED), terminal)
    }
    @Test fun queueRetainsReservedFailuresAndCountsOverflow() {
        val f = Fixture(); f.event("success", DiagnosticLevel.INFO)
        val queue = DiagnosticQueue(normalLimit = 2, urgentLimit = 1)
        repeat(3) { queue.offer(f.ordinary, false) }
        queue.offer(f.ordinary.map { it.copy(event = "failed") }, true)
        queue.offer(f.ordinary, true)
        assertEquals(2L, queue.dropped)
        val rows = queue.drain()
        assertTrue(rows.first().priority)
        assertEquals(3, rows.size); assertEquals(0, queue.bytes)
    }
    @Test fun priorityEvictsOrdinaryBytesBeforeBeingDropped() {
        val f = Fixture(); f.event("row", DiagnosticLevel.INFO)
        val size = Gson().toJson(f.ordinary.single()).toByteArray().size + 1
        val queue = DiagnosticQueue(normalBytes = size * 2, totalBytes = size * 2)
        queue.offer(f.ordinary, false); queue.offer(f.ordinary, false); queue.offer(f.ordinary, true)
        assertEquals(1L, queue.dropped)
        assertEquals(2, queue.drain().size)
    }
    @Test fun loggingFailureStillCallsTheOriginalCrashHandler() {
        var called = false
        val failure = IOException("crash")
        DiagnosticCrashHandler(Thread.UncaughtExceptionHandler { _, original ->
            assertSame(failure, original); called = true
        }) { _, _ -> throw IOException("disk failure") }.uncaughtException(Thread.currentThread(), failure)
        assertTrue(called)
    }
}
