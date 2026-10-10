package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import java.util.WeakHashMap
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext

/** Bounded local diagnostics containing only enums, counters and durations. */
class ExtractionDiagnostics : ExtractionContext.Diagnostics {
    data class Event(val sequence: Long, val stage: String, val profile: String, val detail: String,
                     val elapsedMillis: Long, val httpStatus: Int)
    private val events = ArrayDeque<Event>()
    private var sequence = 0L
    private val contexts = WeakHashMap<ExtractionContext, DiagnosticContext>()
    @Synchronized fun bind(context: ExtractionContext, diagnostic: DiagnosticContext) { contexts[context] = diagnostic }
    @Synchronized fun contextOf(context: ExtractionContext): DiagnosticContext? = contexts[context]
    private class Scoped(val context: DiagnosticContext, val owner: ExtractionDiagnostics) : ExtractionContext.Diagnostics {
        override fun event(stage: String, profile: String, detail: String, elapsed: Long, status: Int) = owner.record(stage, profile, detail, elapsed, status, context)
    }
    fun scoped(context: DiagnosticContext): ExtractionContext.Diagnostics = Scoped(context, this)
    companion object { fun diagnosticContext(context: ExtractionContext): DiagnosticContext? = (context.diagnostics as? Scoped)?.context }
    fun forContext(context: ExtractionContext): ExtractionContext.Diagnostics = contextOf(context)?.let(::scoped) ?: this
    override fun event(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int) =
        record(stage, profile, detail, elapsedMillis, httpStatus, null)
    @Synchronized private fun record(stage: String, profile: String, detail: String, elapsedMillis: Long, httpStatus: Int, context: DiagnosticContext?) {
        // Call sites supply fixed identifiers; never accept response bodies or exception messages.
        val safe = listOf(stage, profile, detail).map { it.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(80) }
        events.addLast(Event(++sequence, safe[0], safe[1], safe[2], elapsedMillis, httpStatus))
        val fields = mapOf("sequence" to sequence, "profile" to safe[1], "detail" to safe[2], "duration_ms" to elapsedMillis,
            "http_status" to httpStatus.takeIf { it in 100..599 })
        val failed = httpStatus >= 400 || safe[2].equals("ERROR", true) || safe[2].endsWith("FAILED") || safe[2].endsWith(":IOException")
        if (failed) AppLog.event(AppLog.Category.EXTRACTOR, safe[0], fields, critical = true, context = context)
        else AppLog.detail(AppLog.Category.EXTRACTOR, safe[0], fields, context)
        while (events.size > 128) events.removeFirst()
    }
    @Synchronized fun mark(): Long = sequence
    @Synchronized fun since(mark: Long): List<Event> = events.filter { it.sequence > mark }
    @Synchronized fun export(): String = "litube YouTube engine v1\n" + events.joinToString("\n") {
        "${it.stage} ${it.profile} ${it.detail} ms=${it.elapsedMillis} http=${it.httpStatus}"
    }
}
