package com.hhst.youtubelite.diagnostics

import com.google.gson.Gson
import java.util.ArrayDeque
import java.util.UUID

/** Bounded, platform-independent flight recorder. The sink must enqueue, never perform IO. */
internal class DiagnosticRecorder(
    private val process: String,
    private val pid: Int,
    private val session: String,
    private val wallClock: () -> Long,
    private val monotonicClock: () -> Long,
    private val sink: (List<DiagnosticEvent>, Boolean) -> Unit,
    private val snapshotProviders: () -> Map<String, Map<String, Any?>> = { emptyMap() },
) {
    private val gson = Gson()
    private data class Buffered(val record: DiagnosticEvent, val bytes: Int)
    private data class Aggregate(val first: DiagnosticEvent, var count: Long, var lastAt: Long, var last: DiagnosticEvent = first,
        val totals: MutableMap<String, Long> = linkedMapOf())
    private data class Incident(val id: String, val context: DiagnosticContext?, val key: String, var until: Long)
    private val ring = ArrayDeque<Buffered>()
    private val aggregates = linkedMapOf<String, Aggregate>()
    private val incidents = linkedMapOf<String, Incident>()
    private val lastIncidents = linkedMapOf<String, Long>()
    private val snapshots = linkedMapOf<String, DiagnosticEvent>()
    private val errors = linkedSetOf<String>()
    private val joinedTraces = linkedMapOf<String, MutableSet<String>>()
    private var ringBytes = 0
    private var sequence = 0L
    private var lastSummary = monotonicClock()
    var merged = 0L; private set
    var truncated = 0L; private set

    @Synchronized fun record(category: String, event: String, level: DiagnosticLevel,
        context: DiagnosticContext?, fields: Map<String, Any?>, failure: Throwable? = null,
        detail: Boolean = false, incident: Boolean = false, kind: String = "event") {
        val now = monotonicClock()
        expire(now)
        if (event == "shared_operation_joined" && context != null) {
            val worker = fields["worker_trace_id"] as? String
            if (worker != null && worker.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                joinedTraces.getOrPut(context.traceId) { linkedSetOf() }.apply { if (size < 8) add(worker) }
                joinedTraces.getOrPut(worker) { linkedSetOf() }.apply { if (size < 8) add(context.traceId) }
                while (joinedTraces.size > 128) joinedTraces.remove(joinedTraces.keys.first())
            }
        }
        var record = DiagnosticEvent(time = wallClock(), elapsedMs = now, sequence = ++sequence,
            process = process, pid = pid, session = session, category = category, level = level,
            event = event.take(100).replace(Regex("[^A-Za-z0-9_.-]"), "_"), context = context,
            fields = DiagnosticRedaction.fields(fields), error = failure?.let(DiagnosticRedaction::structuredFailure), recordKind = kind)
        record.error?.let { error ->
            if (!errors.add(error.errorId)) record = record.copy(error = error.copy(
                causes = error.causes.map { it.copy(frames = emptyList()) }, stackReference = true))
            while (errors.size > 256) errors.remove(errors.first())
        }
        if (gson.toJson(record).toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            truncated++
            record = record.copy(fields = mapOf("truncated" to true), error = record.error?.copy(
                causes = record.error.causes.take(2).map { it.copy(frames = it.frames.take(12)) }))
        }
        val key = "$category:${record.event}:${context?.operationId}:${record.fields["reason"] ?: record.fields["detail"] ?: ""}"
        if (incident || level >= DiagnosticLevel.ERROR) {
            val existing = incidents[key]
            if (existing != null) {
                record = record.copy(incidentId = existing.id)
            } else if (lastIncidents[key]?.let { now - it < INCIDENT_COOLDOWN_MS } != true) {
                val id = "$session-${UUID.randomUUID().toString().take(8)}"
                record = record.copy(incidentId = id)
                val captured = ring.map { it.record }.filter { related(it.context, context) }.map { it.copy(incidentId = id) }
                val state = snapshots.values.filter { it.context == null || related(it.context, context) }.map { it.copy(incidentId = id) } +
                    runCatching { snapshotProviders() }.getOrDefault(emptyMap()).entries.take(16).map { (module, fields) ->
                        record.copy(sequence = ++sequence, category = "APP", event = "module_snapshot", level = DiagnosticLevel.DEBUG,
                            fields = DiagnosticRedaction.fields(fields + mapOf("module" to module)), error = null, recordKind = "snapshot")
                    }
                sink(captured + state + record.copy(recordKind = "incident_start"), true)
                if (incidents.size >= 16) endIncident(incidents.values.first(), "capacity", now)
                incidents[key] = Incident(id, context, key, now + FOLLOW_UP_MS)
                lastIncidents[key] = now
                while (lastIncidents.size > 128) lastIncidents.remove(lastIncidents.keys.first())
            }
        }
        val bytes = gson.toJson(record).toByteArray(Charsets.UTF_8).size
        ring.addLast(Buffered(record, bytes)); ringBytes += bytes
        trim(now)
        if (kind == "snapshot") {
            snapshots["$category:${context?.operationId}"] = record
            while (snapshots.size > 64) snapshots.remove(snapshots.keys.first())
        }
        incidents.values.filter { related(it.context, context) }.forEach {
            sink(listOf(record.copy(incidentId = it.id)), true)
        }
        if (detail || level == DiagnosticLevel.DEBUG) {
            val aggregateKey = "$category:${record.event}:${context?.traceId}"
            aggregate(aggregateKey, record, now)
        } else if (level == DiagnosticLevel.WARN || level >= DiagnosticLevel.ERROR) {
            val prior = aggregates[key]
            if (prior == null) { sink(listOf(record), level >= DiagnosticLevel.ERROR); aggregate(key, record, now) }
            else { prior.count++; prior.lastAt = now; prior.last = record; merged++ }
        } else sink(listOf(record), false)
    }

    private fun aggregate(key: String, record: DiagnosticEvent, now: Long) {
        val prior = aggregates[key]
        if (prior != null) { prior.count++; prior.lastAt = now; prior.last = record; merged++ }
        else {
            if (aggregates.size >= 128) flushAggregate(aggregates.keys.first())
            aggregates[key] = Aggregate(record.copy(fields = emptyMap(), error = null), 1, now, record)
        }
        val aggregate = aggregates[key] ?: return
        if (record.event in setOf("request.end", "source.end", "media_load.completed")) {
            for (name in listOf("bytes_read", "bytes", "duration_ms")) (record.fields[name] as? Number)?.toLong()?.takeIf { it >= 0 }?.let { value ->
                val current = aggregate.totals.getOrDefault("total_$name", 0L)
                aggregate.totals["total_$name"] = if (Long.MAX_VALUE - current < value) Long.MAX_VALUE else current + value
            }
        }
    }

    @Synchronized fun tick() {
        val now = monotonicClock(); expire(now)
        aggregates.filterValues { it.first.level >= DiagnosticLevel.WARN && now - it.first.elapsedMs >= 10_000 }
            .keys.toList().forEach(::flushAggregate)
        if (now - lastSummary >= 30_000) { flushAggregates(); lastSummary = now }
    }

    @Synchronized fun flushAggregates() { aggregates.keys.toList().forEach(::flushAggregate) }

    @Synchronized fun finish(context: DiagnosticContext) {
        fun owned(other: DiagnosticContext?) = if (context.operationId == context.traceId) related(other, context)
            else other?.operationId == context.operationId || other?.parentOperationId == context.operationId
        aggregates.filterValues { it.first.level >= DiagnosticLevel.WARN && owned(it.first.context) }.keys.toList().forEach(::flushAggregate)
        if (context.operationId == context.traceId) aggregates.filterValues { related(it.first.context, context) }.keys.toList().forEach(::flushAggregate)
        incidents.values.filter { owned(it.context) }.toList().forEach { endIncident(it, "operation_end", monotonicClock()) }
        snapshots.entries.removeAll { owned(it.value.context) }
    }

    @Synchronized fun recent(): List<DiagnosticEvent> { trim(monotonicClock()); return ring.map { it.record } }
    @Synchronized fun currentSnapshots(): List<DiagnosticEvent> = snapshots.values.toList()
    @Synchronized fun statistics(): Map<String, Any> = mapOf("ring_events" to ring.size, "ring_bytes" to ringBytes, "merged" to merged, "truncated" to truncated)

    private fun flushAggregate(key: String) {
        val value = aggregates.remove(key) ?: return
        if (value.first.level >= DiagnosticLevel.WARN && value.count == 1L) return
        sink(listOf(value.last.copy(time = wallClock(), elapsedMs = monotonicClock(), sequence = ++sequence,
            event = value.first.event + ".aggregate", level = DiagnosticLevel.INFO, error = null,
            fields = value.last.fields + value.totals + mapOf("count" to value.count, "representative_sample" to true, "first_elapsed_ms" to value.first.elapsedMs,
                "last_elapsed_ms" to value.lastAt), incidentId = null)), false)
    }
    private fun expire(now: Long) {
        trim(now)
        incidents.values.filter { now >= it.until }.toList().forEach { endIncident(it, "window_end", now) }
    }
    private fun endIncident(incident: Incident, reason: String, now: Long) {
        incidents.remove(incident.key)
        sink(listOf(DiagnosticEvent(time = wallClock(), elapsedMs = now, sequence = ++sequence,
            process = process, pid = pid, session = session, category = "APP", level = DiagnosticLevel.INFO,
            event = "incident_end", context = incident.context, fields = mapOf("reason" to reason),
            recordKind = "incident_end", incidentId = incident.id)), true)
    }
    private fun trim(now: Long) {
        while (ring.isNotEmpty() && (ring.size > MAX_EVENTS || ringBytes > MAX_RING_BYTES || now - ring.first.record.elapsedMs > HISTORY_MS))
            ringBytes -= ring.removeFirst().bytes
    }
    private fun related(a: DiagnosticContext?, b: DiagnosticContext?): Boolean =
        if (b == null) true else a?.traceId == b.traceId || joinedTraces[b.traceId]?.contains(a?.traceId) == true

    companion object {
        const val HISTORY_MS = 30_000L
        const val FOLLOW_UP_MS = 15_000L
        const val INCIDENT_COOLDOWN_MS = 30_000L
        const val MAX_EVENTS = 1_000
        const val MAX_RING_BYTES = 2 * 1024 * 1024
        const val MAX_RECORD_BYTES = 32 * 1024
    }
}
