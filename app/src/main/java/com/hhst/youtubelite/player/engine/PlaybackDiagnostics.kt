package com.hhst.youtubelite.player.engine

/** Renderer samples are transient; only anomaly transitions reach persistent logging. */
data class PlaybackDiagnostics(
    val nominalFps: Float? = null, val renderedFps: Float? = null, val expectedFps: Float? = null,
    val videoCodec: String? = null, val audioCodec: String? = null,
    val videoDecoder: String? = null, val audioDecoder: String? = null,
    val width: Int? = null, val height: Int? = null, val videoBitrate: Int? = null, val audioBitrate: Int? = null,
    val renderedFrames: Int = 0, val droppedFrames: Int = 0, val droppedRatio: Float? = null,
    val firstFrameMs: Long? = null, val bufferingMs: Long = 0, val bufferCount: Int = 0,
    val playbackState: String = "idle", val sampledAt: Long = 0,
)

class PlaybackPerformanceMonitor(private val emit: (String, String, Map<String, Any>) -> Unit) {
    private var graceUntil = 0L
    private var windowAt: Long? = null
    private var windowRendered = 0; private var windowDropped = 0
    private var lastRendered = 0; private var lastFrameAt = 0L
    private var lowWindows = 0
    private var bufferAt: Long? = null
    private val buffers = ArrayDeque<Long>()
    private val anomalies = mutableMapOf<String, Long>()
    private data class FrameSample(val at: Long, val rendered: Int)
    private val samples = ArrayDeque<FrameSample>()
    var fps: Float? = null; private set
    var bufferDuration = 0L; private set
    val bufferCount: Int get() = buffers.size

    fun stabilize(now: Long) {
        graceUntil = now + 3000; windowAt = null; lowWindows = 0; fps = null; samples.clear(); lastFrameAt = now
    }
    fun reset(now: Long) {
        anomalies.keys.toList().forEach { transition(it, false, now) }
        bufferAt = null; buffers.clear(); bufferDuration = 0; lastRendered = 0
        stabilize(now)
    }
    fun sample(now: Long, rendered: Int, dropped: Int, expected: Float?, eligible: Boolean, buffering: Boolean) {
        if (buffering && bufferAt == null) { bufferAt = now; buffers.addLast(now) }
        if (!buffering) bufferAt = null
        while (buffers.isNotEmpty() && now - buffers.first() > 60_000) buffers.removeFirst()
        bufferDuration = bufferAt?.let { now - it } ?: 0
        transition("long_buffer", buffering && bufferDuration > 5000, now, mapOf("duration_ms" to bufferDuration))
        transition("frequent_buffer", buffers.size >= 3, now, mapOf("count" to buffers.size))
        if (!eligible || buffering || now < graceUntil) {
            windowAt = null; lowWindows = 0; fps = null; samples.clear(); lastFrameAt = now; lastRendered = rendered
            listOf("low_fps", "dropped_frames", "render_stall").forEach { transition(it, false, now) }
            return
        }
        if (samples.lastOrNull()?.rendered?.let { rendered < it } == true) samples.clear()
        samples.addLast(FrameSample(now, rendered))
        while (samples.size > 1 && now - samples.elementAt(1).at >= 5000) samples.removeFirst()
        val oldest = samples.first()
        fps = if (now - oldest.at >= 5000) (rendered - oldest.rendered) * 1000f / (now - oldest.at) else null
        if (rendered != lastRendered) { lastRendered = rendered; lastFrameAt = now }
        transition("render_stall", now - lastFrameAt >= 2000, now)
        val start = windowAt
        if (start == null || rendered < windowRendered || dropped < windowDropped) {
            windowAt = now; windowRendered = rendered; windowDropped = dropped; return
        }
        if (now - start < 5000) return
        val output = rendered - windowRendered; val lost = dropped - windowDropped
        val windowFps = output * 1000f / (now - start)
        val ratio = if (output + lost > 0) lost.toFloat() / (output + lost) else 0f
        lowWindows = if (expected != null && expected > 0 && windowFps < expected * 0.8f) lowWindows + 1 else 0
        val metrics = mapOf<String, Any>("render_fps" to windowFps, "expected_fps" to (expected ?: -1f), "dropped" to lost, "drop_ratio" to ratio)
        transition("low_fps", lowWindows >= 2, now, metrics)
        transition("dropped_frames", lost >= 10 && ratio >= 0.05f, now, metrics)
        windowAt = now; windowRendered = rendered; windowDropped = dropped
    }
    private fun transition(kind: String, active: Boolean, now: Long, fields: Map<String, Any> = emptyMap()) {
        val previous = anomalies[kind]
        when {
            active && previous == null -> { anomalies[kind] = now; emit(kind, "start", fields) }
            active && now - (previous ?: now) >= 30_000 -> { anomalies[kind] = now; emit(kind, "ongoing", fields) }
            !active && previous != null -> { anomalies.remove(kind); emit(kind, "recovered", fields) }
        }
    }
    companion object {
        fun expectedFps(nominal: Float?, speed: Float, refresh: Float): Float? = nominal?.takeIf { it > 0 && it.isFinite() }
            ?.let { minOf(it * speed, refresh.takeIf { hz -> hz > 0 } ?: 60f) }
    }
}
