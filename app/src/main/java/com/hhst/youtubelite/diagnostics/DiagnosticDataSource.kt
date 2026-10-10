@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.diagnostics

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.UUID

/** Covers cached reads too; the immutable tag reaches the HTTP request without global state. */
internal class DiagnosticDataSource(private val inner: DataSource, private val parent: DiagnosticContext, private val type: Int? = null,
    private val category: AppLog.Category = AppLog.Category.PLAYER) : DataSource by inner {
    private var context = parent.child(UUID.randomUUID().toString())
    private var resource = emptyMap<String, Any?>()
    private var bytes = 0L
    private var expected = C.LENGTH_UNSET.toLong()
    private var started = 0L
    private var lastSample = 0L
    private var ended = true
    private var eof = false
    override fun open(dataSpec: DataSpec): Long {
        context = parent.child(UUID.randomUUID().toString())
        expected = C.LENGTH_UNSET.toLong()
        bytes = 0; started = SystemClock.elapsedRealtime(); lastSample = started; ended = false; eof = false
        resource = DiagnosticRedaction.resource(dataSpec.uri.toString()) + mapOf("data_type" to type,
            "position" to dataSpec.position, "requested_length" to dataSpec.length.takeIf { it >= 0 })
        AppLog.detail(category, "source.open", resource, context)
        return try {
            inner.open(dataSpec.buildUpon().setCustomData(context).build()).also { expected = it }
        } catch (failure: Throwable) { finish("FAILURE", failure); throw failure }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val size = try { inner.read(buffer, offset, length) } catch (failure: Throwable) {
            finish("FAILURE", failure); throw failure
        }
        if (size == C.RESULT_END_OF_INPUT) { eof = true; finish("SUCCESS") }
        else if (size > 0) {
            val first = bytes == 0L
            bytes += size
            if (first) AppLog.detail(category, "source.first_bytes", state(), context)
            val now = SystemClock.elapsedRealtime()
            if (now - lastSample >= 5_000) { lastSample = now; AppLog.snapshot(category, context, state()) }
        }
        return size
    }
    override fun close() {
        try { inner.close() } finally { finish(if (eof || expected >= 0 && bytes >= expected) "SUCCESS" else "CANCELLED") }
    }
    private fun state() = resource + mapOf("bytes_read" to bytes, "expected_bytes" to expected.takeIf { it >= 0 },
        "duration_ms" to (SystemClock.elapsedRealtime() - started))
    private fun finish(outcome: String, failure: Throwable? = null) {
        if (ended) return
        ended = true
        if (failure != null) AppLog.event(category, "source.failed", state() + mapOf("outcome" to outcome), failure, true, context)
        else AppLog.detail(category, "source.end", state() + mapOf("outcome" to outcome), context)
        AppLog.endContext(context)
    }
}

