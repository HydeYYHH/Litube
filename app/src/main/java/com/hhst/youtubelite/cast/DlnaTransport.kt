package com.hhst.youtubelite.cast

import com.hhst.youtubelite.core.readCancellable
import com.yinnho.upnpcast.DLNACast
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class DlnaRequestException(
    val stage: String,
    val endpoint: HttpUrl,
    val status: Int? = null,
    val code: String? = null,
    detail: String,
) : IOException("$stage: $detail")

internal class DlnaTransport(private val client: OkHttpClient) {
    var renderer: DlnaRenderer? = null
        private set

    fun close() { client.connectionPool.evictAll() }

    suspend fun describe(location: HttpUrl): DlnaRenderer? {
        val request = Request.Builder().url(location).header("User-Agent", "Litube/1.0 UPnP/1.1")
            .header("Connection", "close").build()
        val (status, bytes) = read(request, "description")
        return try {
            DlnaDescription.parse(bytes, location)
        } catch (error: Exception) {
            throw DlnaRequestException("description", location, status,
                detail = "parse ${error.message ?: error.javaClass.simpleName}").also { it.initCause(error) }
        }
    }

    suspend fun cast(target: DlnaRenderer, url: String, title: String, mimeType: String): Boolean {
        renderer = target
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${xml(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:${xml(mimeType)}:*\">" +
            "${xml(url)}</res></item></DIDL-Lite>"
        action("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(metadata)}</CurrentURIMetaData>")
        return play()
    }

    suspend fun play(): Boolean { action("Play", "<Speed>1</Speed>"); return true }

    /**
     * SetURI/Play can acknowledge before opening the media (notably Kodi). Track duration
     * may be zero/NOT_IMPLEMENTED; track URIs can be omitted or rewritten by TVs.
     * Prefer a matching track, with stable PLAYING as the incomplete-metadata fallback.
     */
    suspend fun awaitMediaReady(url: String, timeoutMs: Long = 20_000) {
        var observed: DLNACast.PlaybackState? = null
        val ready = withTimeoutOrNull(timeoutMs) {
            var playingSince: Long? = null
            while (true) {
                observed = state()
                if (observed == DLNACast.PlaybackState.PLAYING) {
                    val since = playingSince ?: System.nanoTime().also { playingSince = it }
                    // Position information is a hint, not a prerequisite for playback control.
                    val track = try {
                        withTimeoutOrNull(1_000) { action("GetPositionInfo")["TrackURI"] }
                    } catch (_: IOException) { null }
                    val stableMs = (System.nanoTime() - since) / 1_000_000
                    val identified = track.isNullOrBlank() || track == "NOT_IMPLEMENTED" || track == url
                    if (stableMs >= if (identified) 500 else 2_000) return@withTimeoutOrNull true
                } else playingSince = null
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (ready != true) throw DlnaRequestException("media_ready", requireNotNull(renderer).control,
            detail = "Playback did not become ready within ${timeoutMs} ms; transport=$observed")
    }
    suspend fun pause(): Boolean { action("Pause"); return true }

    /** Some renderers accept Pause before a late media-open/seek resumes playback.
     * Confirm a stable paused state; retry only while still PLAYING. */
    suspend fun pauseAndConfirm(): Boolean = withTimeout(4_000) {
        pause()
        var lastRequest = System.nanoTime()
        var pausedSince: Long? = null
        while (true) {
            delay(150)
            val observed = state()
            val now = System.nanoTime()
            if (observed == DLNACast.PlaybackState.PAUSED) {
                val since = pausedSince ?: now.also { pausedSince = it }
                if (now - since >= 500_000_000L) return@withTimeout true
            } else {
                pausedSince = null
                if (observed == DLNACast.PlaybackState.PLAYING && now - lastRequest >= 500_000_000L) {
                    pause()
                    lastRequest = System.nanoTime()
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
    suspend fun stop(): Boolean {
        if (renderer == null) return false
        action("Stop")
        return true
    }
    suspend fun seek(positionMs: Long): Boolean {
        val seconds = positionMs.coerceAtLeast(0) / 1000
        val time = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        val parameters = "<Unit>REL_TIME</Unit><Target>$time</Target>"
        try {
            seekAction(parameters)
            return true
        } catch (error: DlnaRequestException) {
            if (error.code !in setOf("501", "701")) throw error
            val paused = try { state() == DLNACast.PlaybackState.PAUSED } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stateError: Exception) {
                error.addSuppressed(stateError)
                throw error
            }
            if (!paused) throw error
        }
        // Kodi rejects Seek unless PLAYING, including while PAUSED_PLAYBACK. Only after an
        // explicit state-related rejection, resume for the seek and restore the user's pause.
        var failure: Throwable? = null
        try {
            play()
            withTimeout(3_000) {
                while (state() != DLNACast.PlaybackState.PLAYING) delay(100)
            }
            seekAction(parameters)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                withContext(NonCancellable) { pause() }
            } catch (pauseError: Exception) {
                if (failure == null) throw pauseError
                failure.addSuppressed(pauseError)
            }
        }
        return true
    }

    private suspend fun seekAction(parameters: String) {
        repeat(3) { attempt ->
            try {
                action("Seek", parameters)
                return
            } catch (error: DlnaRequestException) {
                if (error.code !in setOf("701", "711") || attempt == 2) throw error
                delay(250L * (attempt + 1))
            }
        }
    }
    suspend fun progress(): Pair<Long, Long>? {
        val values = action("GetPositionInfo")
        val position = time(values["RelTime"]) ?: return null
        return position to (time(values["TrackDuration"]) ?: 0)
    }
    suspend fun state(): DLNACast.PlaybackState = when (action("GetTransportInfo")["CurrentTransportState"]) {
        "PLAYING" -> DLNACast.PlaybackState.PLAYING
        "PAUSED_PLAYBACK" -> DLNACast.PlaybackState.PAUSED
        "TRANSITIONING" -> DLNACast.PlaybackState.BUFFERING
        "STOPPED" -> DLNACast.PlaybackState.STOPPED
        "NO_MEDIA_PRESENT" -> DLNACast.PlaybackState.IDLE
        else -> DLNACast.PlaybackState.ERROR
    }

    private suspend fun action(name: String, parameters: String = ""): Map<String, String> {
        val target = renderer ?: throw IOException("No renderer selected")
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$name xmlns:u=\"${xml(target.serviceType)}\"><InstanceID>0</InstanceID>" +
            "$parameters</u:$name></s:Body></s:Envelope>"
        val request = Request.Builder().url(target.control)
            .header("SOAPAction", "\"${target.serviceType}#$name\"")
            // Many embedded renderers close SOAP sockets without advertising it; do not
            // reuse a description/SetURI socket for Play (or replay after a stale-socket EOF).
            .header("Connection", "close")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        val (status, bytes) = read(request, name)
        return try {
            DlnaDescription.values(bytes)
        } catch (error: Exception) {
            throw DlnaRequestException(name, request.url, status,
                detail = "parse ${error.message ?: error.javaClass.simpleName}").also { it.initCause(error) }
        }
    }

    private suspend fun read(request: Request, stage: String): Pair<Int, ByteArray> = try {
        client.newCall(request).readCancellable { response ->
            val bytes = response.body?.byteStream()?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= DlnaDescription.MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, DlnaDescription.MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            if (bytes.size > DlnaDescription.MAX_BYTES) throw IOException("Device response exceeds size limit")
            val fault = runCatching { DlnaDescription.values(bytes) }.getOrDefault(emptyMap())
            if (!response.isSuccessful || "errorCode" in fault || "faultcode" in fault) {
                throw DlnaRequestException(stage, request.url, response.code, fault["errorCode"],
                    fault["errorDescription"] ?: fault["faultstring"] ?: "HTTP ${response.code}")
            }
            response.code to bytes
        }
    } catch (error: DlnaRequestException) {
        throw error
    } catch (error: IOException) {
        throw DlnaRequestException(stage, request.url, detail = error.message ?: error.javaClass.simpleName).also { it.initCause(error) }
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    private fun time(value: String?): Long? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
        return ((h * 3600 + m * 60) * 1000 + s * 1000).toLong().coerceAtLeast(0)
    }
}
