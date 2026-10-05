@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Immutable VOD manifests only. Live playlists always bypass this cache. */
internal class VodManifestCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val bytes: ByteArray, val until: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val lock = Any()

    fun factory(scope: String, expires: Long, current: () -> Boolean,
                upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private var source: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            private var capture: ByteArrayOutputStream? = null
            private var key = ""
            private var complete = false
            override fun addTransferListener(listener: TransferListener) { listeners += listener }
            override fun open(dataSpec: DataSpec): Long {
                if (!current()) throw IOException("MEDIA_SESSION_CHANGED")
                if (expires <= now()) throw IOException("MEDIA_URL_EXPIRED")
                key = "$scope:${dataSpec.uri}"
                val hit = synchronized(lock) {
                    entries.entries.removeAll { it.value.until <= now() }
                    entries[key]?.bytes
                }
                val delegate = if (hit == null) upstream.createDataSource() else ByteArrayDataSource(hit)
                source = delegate
                listeners.forEach(delegate::addTransferListener)
                if (hit == null && dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    capture = ByteArrayOutputStream()
                }
                return delegate.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!current()) throw IOException("MEDIA_SESSION_CHANGED")
                val count = requireNotNull(source).read(buffer, offset, length)
                capture?.let { bytes ->
                    if (count > 0) {
                        if (bytes.size() + count <= 256 * 1024) bytes.write(buffer, offset, count)
                        else capture = null
                    } else if (count == C.RESULT_END_OF_INPUT && !complete) {
                        complete = true
                        if (current() && bytes.size() > 0) synchronized(lock) {
                            entries[key] = Entry(bytes.toByteArray(), minOf(now() + 120_000, expires - 30_000))
                            var total = entries.values.sumOf { it.bytes.size }
                            while (total > 1024 * 1024) total -= entries.remove(entries.keys.first())!!.bytes.size
                        }
                        capture = null
                    }
                }
                return count
            }
            override fun getUri(): Uri? = source?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
            override fun close() { try { source?.close() } finally { source = null; capture = null; complete = false } }
        }
    }
}
