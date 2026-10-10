package com.hhst.youtubelite.diagnostics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deterministic archive layout shared by tests and the Android export entry point. */
internal object DiagnosticArchive {
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val legacyFields = setOf("video_id", "operation", "task", "stage", "duration_ms", "http_status", "sequence",
        "profile", "detail", "state", "position_ms", "error_code", "decoder", "count", "fps", "expected_fps",
        "rendered", "dropped", "ratio", "reextract", "phase", "generation", "batch", "reason", "timestamp")
    fun write(target: File, sources: Map<String, String>, environment: Map<String, Any?>, statistics: Map<String, Any?>) {
        val records = mutableListOf<DiagnosticEvent>()
        var invalid = 0; var legacy = 0; var unknownFields = 0
        var legacySequence = 0L
        val sourceInfo = sources.map { (name, content) ->
            var rows = 0
            content.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                rows++
                runCatching {
                    require(line.toByteArray(Charsets.UTF_8).size <= 64_000)
                    val json = JsonParser.parseString(line).asJsonObject
                    require((json.get("time")?.asLong ?: 0) > 0)
                    if (json.get("schema_version")?.asInt == 2) {
                        val row = gson.fromJson(json, DiagnosticEvent::class.java)
                        require(row.time > 0 && row.sequence >= 0)
                        requireNotNull(row.level); requireNotNull(row.process); requireNotNull(row.session)
                        requireNotNull(row.event); requireNotNull(row.fields)
                        val origin = row.context?.let { c ->
                            requireNotNull(c.operationId); requireNotNull(c.traceId)
                            c.copy(operationId = identifier(c.operationId), traceId = identifier(c.traceId),
                                parentOperationId = c.parentOperationId?.let(::identifier),
                                videoId = c.videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) },
                                taskId = c.taskId?.let(::identifier), castSessionId = c.castSessionId?.let(::identifier),
                                requestId = c.requestId?.let(::identifier))
                        }
                        records += row.copy(process = identifier(row.process), session = identifier(row.session),
                            category = identifier(row.category), event = identifier(row.event), context = origin,
                            fields = DiagnosticRedaction.fields(row.fields), error = row.error?.let { error ->
                                error.copy(errorId = identifier(error.errorId), causes = error.causes.take(8).map { cause ->
                                    cause.copy(type = identifier(cause.type), code = DiagnosticRedaction.safeMessage(cause.code),
                                        message = DiagnosticRedaction.safeMessage(cause.message),
                                        frames = cause.frames.take(48).filter(::isFrame).map(DiagnosticRedaction::text))
                                })
                            })
                    } else {
                        legacy++
                        val values = json.getAsJsonObject("fields")?.entrySet().orEmpty()
                        unknownFields += values.count { it.key !in legacyFields }
                        val fields: Map<String, Any?> = values.filter { it.key in legacyFields }.associate { it.key to
                            if (!it.value.isJsonPrimitive) null else it.value.asJsonPrimitive.let { p ->
                                when { p.isBoolean -> p.asBoolean; p.isNumber -> p.asNumber; else -> p.asString }
                            } }
                        val pid = json.get("pid")?.asInt ?: 0
                        val session = json.get("session")?.asString ?: "unknown"
                        val operation = fields["operation"]?.toString() ?: fields["task"]?.toString()
                        val context = operation?.let { DiagnosticContext(operationId = "legacy:$session:$it",
                            videoId = fields["video_id"]?.toString()?.takeIf { vid -> vid.matches(Regex("[A-Za-z0-9_-]{11}")) },
                            taskId = fields["task"]?.toString()) }
                        val stack = json.get("error")?.asString
                        val frames = stack?.lineSequence()?.filter(::isFrame)?.take(64)?.map(DiagnosticRedaction::text)?.toList().orEmpty()
                        val code = stack?.lineSequence()?.mapNotNull { DiagnosticRedaction.safeMessage(it.substringAfter(": ", "")) }?.firstOrNull()
                        val sequence = ++legacySequence
                        records += DiagnosticEvent(time = json.get("time")?.asLong ?: 0, elapsedMs = 0,
                            sequence = sequence, process = "legacy:$pid", pid = pid, session = identifier(session),
                            category = json.get("category")?.asString ?: "APP",
                            level = if (stack != null || name.contains("critical")) DiagnosticLevel.ERROR else DiagnosticLevel.INFO,
                            event = json.get("event")?.asString?.take(100) ?: "legacy_event", context = context,
                            fields = DiagnosticRedaction.fields(fields + mapOf("legacy" to true, "source_file" to name,
                                "source_line" to index + 1, "identity_unverified" to true)),
                            error = stack?.let { DiagnosticFailure("legacy-$session-$sequence", listOf(DiagnosticCause("legacy_error", code, code, frames))) })
                    }
                }.onFailure { invalid++ }
            }
            mapOf("name" to name, "lines" to rows)
        }
        val comparator = compareBy<DiagnosticEvent> { it.time }.thenBy { it.process }.thenBy { it.session }.thenBy { it.sequence }
        val incidents = records.filter { it.incidentId?.matches(Regex("[A-Za-z0-9-]{1,80}")) == true }
            .groupBy { it.incidentId!! }.toSortedMap().mapValues { (_, value) ->
                value.distinctBy { listOf(it.process, it.session, it.sequence, it.recordKind) }.sortedWith(comparator)
            }
        val timeline = records.filter { it.level != DiagnosticLevel.DEBUG && it.recordKind == "event" }
            .groupBy { listOf(it.process, it.session, it.sequence) }.values.map { copies ->
                copies.firstOrNull { it.incidentId != null } ?: copies.first()
            }.sortedWith(comparator)
        val failures = timeline.withIndex().filter { it.value.level >= DiagnosticLevel.ERROR }
        val summary = buildString {
            appendLine("# Litube diagnostic report")
            appendLine()
            appendLine("Events: " + timeline.size + "; incidents: " + incidents.size + "; invalid rows: " + invalid)
            appendLine("Unknown/uncollected values are not evidence of success. Legacy identities were not verified at capture time.")
            appendLine()
            if (failures.isEmpty()) appendLine("No retained error events. Inspect context.json and active state snapshots.")
            failures.takeLast(100).forEach { (index, record) ->
                appendLine("## " + record.category + " / " + record.event)
                appendLine("- Time (Unix ms): " + record.time)
                appendLine("- VID: " + (record.context?.videoId ?: record.fields["video_id"] ?: "unknown") +
                    "; task: " + (record.context?.taskId ?: record.fields["task"] ?: "unknown") +
                    "; operation: " + (record.context?.operationId ?: "unknown"))
                appendLine("- Evidence: timeline.jsonl:" + (index + 1))
                val scene = record.incidentId?.let(incidents::get).orEmpty()
                val state = scene.lastOrNull { it.recordKind == "snapshot" && it.category == record.category }
                appendLine("- Failure stage: " + (record.fields["phase"] ?: record.fields["stage"] ?: state?.fields?.get("phase") ?: "unknown"))
                appendLine("- Session: " + (record.context?.castSessionId ?: record.session))
                record.incidentId?.let { id ->
                    val start = incidents[id]?.indexOfFirst { it.recordKind == "incident_start" } ?: -1
                    appendLine("- Incident: incidents/" + id + ".jsonl:" + (start + 1).coerceAtLeast(1))
                }
                appendLine("- Fields: " + gson.toJson(record.fields))
                appendLine("- Known facts: " + gson.toJson(state?.fields ?: emptyMap<String, Any?>()))
                appendLine("- Missing information: " + listOf("phase", "pending_requests", "last_progress_ms").filter {
                    record.fields[it] == null && state?.fields?.get(it) == null
                }.joinToString().ifBlank { "none of the standard snapshot fields" })
                appendLine("- Error codes: " + record.error?.causes?.mapNotNull { it.code }?.joinToString().orEmpty().ifBlank { "unknown" })
                appendLine()
            }
        }
        val manifest = mapOf("schema_version" to 2, "exported_at" to System.currentTimeMillis(),
            "build_id" to environment["build_id"], "time_start" to timeline.minOfOrNull { it.time },
            "time_end" to timeline.maxOfOrNull { it.time }, "event_count" to timeline.size,
            "incident_count" to incidents.size, "processes" to records.map { it.process }.distinct().sorted(),
            "source_files" to sourceInfo, "invalid_rows" to invalid, "legacy_rows" to legacy,
            "discarded_legacy_fields" to unknownFields, "statistics" to statistics,
            "statistics_by_process" to records.groupBy { it.process + ":" + it.session }.mapValues { (_, rows) ->
                rows.lastOrNull { it.event == "logger_health" }?.let { it.fields + mapOf("as_of_time" to it.time,
                    "as_of_sequence" to it.sequence) } ?: mapOf("state" to "unknown")
            },
            "retention" to mapOf("days" to 3, "total_bytes" to DiagnosticFiles.MAX_BYTES, "ordinary_bytes" to DiagnosticFiles.NORMAL_BYTES),
            "files" to (mapOf("timeline.jsonl" to timeline.size) + incidents.mapKeys { "incidents/" + it.key + ".jsonl" }.mapValues { it.value.size }))
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("summary.md", summary)
            entry("timeline.jsonl", timeline.joinToString("", transform = { gson.toJson(it) + "\n" }))
            incidents.forEach { (id, rows) -> entry("incidents/$id.jsonl", rows.joinToString("", transform = { gson.toJson(it) + "\n" })) }
            entry("context.json", gson.toJson(environment))
            entry("manifest.json", gson.toJson(manifest))
        }
    }
    private fun identifier(value: String): String = value.take(160).replace(Regex("[^A-Za-z0-9_.:$-]"), "_")
    private fun isFrame(value: String): Boolean = value.trim().removePrefix("at ")
        .matches(Regex("[A-Za-z0-9_.$/]+\\.[A-Za-z0-9_$<>]+\\([^\\r\\n]{0,180}\\)"))
}

