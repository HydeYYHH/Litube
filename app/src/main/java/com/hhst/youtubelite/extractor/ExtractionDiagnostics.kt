package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.diagnostics.AppLog
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext

/** Bounded local diagnostics containing only enums, counters and durations. */
class ExtractionDiagnostics : ExtractionContext.Diagnostics {
    data class Event(val sequence: Long, val stage: String, val profile: String, val detail: String,
                     val elapsedMillis: Long, val httpStatus: Int)
    private val events = ArrayDeque<Event>()
    private var sequence = 0L
    @Synchronized override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) {
        // Call sites supply fixed identifiers; never accept response bodies or exception messages.
        val safe = listOf(stage, profile, detail).map { it.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(80) }
        events.addLast(Event(++sequence, safe[0], safe[1], safe[2], elapsedMillis, httpStatus))
        AppLog.event(AppLog.Category.EXTRACTOR, safe[0],
            mapOf("sequence" to sequence, "profile" to safe[1], "detail" to safe[2], "duration_ms" to elapsedMillis, "http_status" to httpStatus),
            critical = httpStatus >= 400 || safe[2].contains("fail", true) || safe[2].contains("error", true))
        while (events.size > 128) events.removeFirst()
    }
    @Synchronized fun mark(): Long = sequence
    @Synchronized fun since(mark: Long): List<Event> = events.filter { it.sequence > mark }
    @Synchronized fun export(): String = "litube YouTube engine v1\n" + events.joinToString("\n") {
        "${it.stage} ${it.profile} ${it.detail} ms=${it.elapsedMillis} http=${it.httpStatus}"
    }
}
