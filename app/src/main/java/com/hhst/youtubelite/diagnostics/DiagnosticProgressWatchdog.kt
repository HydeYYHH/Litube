package com.hhst.youtubelite.diagnostics

/** Diagnostic deadlines never cancel or retry business work. Only measured progress recovers a stall. */
class DiagnosticProgressWatchdog(
    private val thresholdMs: Long,
    private val onTransition: (String, Long) -> Unit,
    private val stackAfterMs: Long? = null,
    private val onStack: () -> Unit = {},
) {
    private var lastValue: Long? = null
    private var lastProgressAt: Long? = null
    private var stalled = false
    private var stacked = false
    fun sample(now: Long, value: Long?, eligible: Boolean) {
        if (!eligible) { reset("deferred"); return }
        if (lastProgressAt == null || value != null && value != lastValue) {
            if (stalled) onTransition("recovered", now - (lastProgressAt ?: now))
            lastValue = value; lastProgressAt = now; stalled = false; stacked = false
            return
        }
        val duration = now - (lastProgressAt ?: now)
        if (!stalled && duration >= thresholdMs) { stalled = true; onTransition("stalled", duration) }
        if (stalled && !stacked && stackAfterMs != null && duration >= stackAfterMs) { stacked = true; onStack() }
    }
    fun reset(reason: String = "superseded") {
        if (stalled) onTransition(reason, 0)
        lastValue = null; lastProgressAt = null; stalled = false; stacked = false
    }
}
