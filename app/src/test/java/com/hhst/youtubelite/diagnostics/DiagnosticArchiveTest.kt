package com.hhst.youtubelite.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.zip.ZipFile

class DiagnosticArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun row(sequence: Long, time: Long = 1_000_000, process: String = "main") = DiagnosticEvent(
        time = time, elapsedMs = sequence, sequence = sequence, process = process, pid = 42, session = "session",
        category = "PLAYER", level = DiagnosticLevel.ERROR, event = "stalled",
        context = DiagnosticContext(videoId = "Wh9klmWAm5s", taskId = "task-1"),
        fields = mapOf("phase" to "awaiting_first_frame"))
    @Test fun archiveIsParseableSortedAndContainsLineProvenance() {
        val target = temp.newFile("archive.zip"); val gson = Gson()
        val first = row(1).copy(incidentId = "incident-1")
        DiagnosticArchive.write(target, mapOf("a" to listOf(row(2), first, first.copy(recordKind = "incident_start")).joinToString("\n") { gson.toJson(it) },
            "b" to gson.toJson(row(1, 999_999, "ejs"))), mapOf("build_id" to "fixture"), emptyMap())
        ZipFile(target).use { zip ->
            for (name in listOf("summary.md", "timeline.jsonl", "context.json", "manifest.json", "incidents/incident-1.jsonl")) assertNotNull(zip.getEntry(name))
            val lines = zip.getInputStream(zip.getEntry("timeline.jsonl")).bufferedReader().readLines().map { JsonParser.parseString(it).asJsonObject }
            assertEquals(3, lines.size); assertEquals("ejs", lines.first().get("process").asString)
            val summary = zip.getInputStream(zip.getEntry("summary.md")).bufferedReader().readText()
            assertTrue(summary.contains("Wh9klmWAm5s")); assertTrue(summary.contains("awaiting_first_frame"))
            assertTrue(summary.contains("timeline.jsonl:2")); assertTrue(summary.contains("incidents/incident-1.jsonl:"))
        }
    }
    @Test fun legacyFilesDoNotCollideAndPayloadsAreRemovedReadOnly() {
        val target = temp.newFile("legacy.zip")
        val old = """{"time":123,"pid":1,"session":"old","category":"PLAYER","event":"failed","fields":{"video_id":"wDIrpvH8MzE","position_ms":32,"unknown":"secret"},"error":"java.io.IOException: {private-response-body}\nat a.b.method(SourceFile:12)"}"""
        DiagnosticArchive.write(target, mapOf("a.jsonl" to old, "b.jsonl" to old), emptyMap(), emptyMap())
        ZipFile(target).use { zip ->
            val timeline = zip.getInputStream(zip.getEntry("timeline.jsonl")).bufferedReader().readText()
            assertEquals(2, timeline.lines().count { it.isNotBlank() })
            assertFalse(timeline.contains("private-response-body")); assertFalse(timeline.contains("secret"))
            assertTrue(timeline.contains("a.b.method")); assertTrue(timeline.contains("source_line"))
            val manifest = JsonParser.parseString(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()).asJsonObject
            assertEquals(2, manifest.get("discarded_legacy_fields").asInt)
        }
    }
    @Test fun malformedAndOverlongRowsAreCountedAndV2IsRedactedAgain() {
        val target = temp.newFile("unsafe.zip"); val gson = Gson()
        val row = row(1).copy(fields = mapOf("cookie" to "credential", "url" to "https://host/path?sig=secret"),
            error = DiagnosticFailure("error-1", listOf(DiagnosticCause("java.io.IOException", null, "private-response-body", listOf("response text")))))
        DiagnosticArchive.write(target, mapOf("a" to gson.toJson(row) + "\n{}\n" + "a".repeat(64_001)), emptyMap(), emptyMap())
        ZipFile(target).use { zip ->
            val timeline = zip.getInputStream(zip.getEntry("timeline.jsonl")).bufferedReader().readText()
            assertFalse(timeline.contains("credential")); assertFalse(timeline.contains("private-response-body")); assertFalse(timeline.contains("sig=secret"))
            val manifest = JsonParser.parseString(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()).asJsonObject
            assertEquals(2, manifest.get("invalid_rows").asInt)
        }
    }
    @Test fun segmentsRotateAndActiveFilesSurviveOtherOwnersCleanup() {
        var time = 1_000_000L
        DiagnosticFiles(temp.root) { time }.use { first ->
            first.append(listOf(row(1)), false)
            DiagnosticFiles(temp.root) { time }.use { other ->
                other.prune(maxBytes = 0)
                assertEquals(1, first.files().size)
                time += 3_600_000
                first.append(listOf(row(2, time)), false)
                assertEquals(2, first.files().size)
                other.prune(maxBytes = first.files().last().length())
                assertEquals(1, first.files().size)
            }
        }
    }
    @Test fun quotaStaysBoundedAndSegmentsStayUnder512KiB() {
        DiagnosticFiles(temp.root).use { files ->
            repeat(40) { batch -> files.append((0..99).map { row((batch * 100 + it).toLong()).copy(fields = mapOf("data" to "a".repeat(6000))) }, false) }
            assertTrue(files.files().sumOf { it.length() } <= DiagnosticFiles.NORMAL_BYTES)
            assertTrue(files.files().all { it.length() <= DiagnosticFiles.SEGMENT_BYTES })
        }
    }
    @Test fun deadlineDoesNotWaitForeverForCrossProcessQuotaLock() {
        RandomAccessFile(temp.newFile("quota.lock"), "rw").use { handle -> handle.channel.lock().use {
            val started = System.nanoTime()
            val snapshot = DiagnosticFiles(temp.root).snapshot { System.nanoTime() - started > 50_000_000 }
            assertEquals(1, snapshot.second)
            assertTrue(System.nanoTime() - started < 500_000_000)
        } }
    }
    @Test fun diskFailureIsVisibleAndDoesNotEraseExistingFiles() {
        val target = temp.newFile("blocked")
        val failed = runCatching { DiagnosticFiles(target).append(listOf(row(1)), false) }
        assertTrue(failed.isFailure); assertTrue(target.isFile)
    }
}
