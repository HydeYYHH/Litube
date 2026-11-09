// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

import android.util.Log
import android.net.Network
import com.hhst.youtubelite.diagnostics.CastDiagnosticTrace
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Receiver + media layer of the cast stack: launches the Default Media Receiver
 * ([DEFAULT_MEDIA_RECEIVER_APP_ID]) over a [CastV2Channel], fed a URL from the phone-side
 * proxy, and drives playback on it. [load] is only meaningful after [Listener.onConnected];
 * the first MEDIA_STATUS supplies the `mediaSessionId` every transport command must carry.
 *
 * Receivers do NOT push MEDIA_STATUS continuously - only on state transitions; a periodic
 * media-namespace GET_STATUS keeps [Listener.onMediaStatus] ticking so the phone-side overlay
 * can show live position. All times crossing this API are milliseconds; the seconds-double
 * wire format stays confined to [MediaStatus] and the JSON builders here.
 *
 * Threading: public methods are safe from any thread (they queue onto the channel's writer);
 * listener callbacks fire on the channel's reader or this session's scheduler thread - post to
 * main before touching UI. Exactly one terminal callback fires ([Listener.onLaunchError],
 * [Listener.onChannelError] or [Listener.onClosed]).
 */
class CastV2Session(
    host: String,
    port: Int,
    private val appId: String,
    private val listener: Listener,
    authenticator: CastV2Channel.Authenticator = CastV2Channel.Authenticator { socket, input, output ->
        CastDeviceAuth.authenticate(socket, input, output)
    },
    network: Network? = null,
) {

    interface Listener {
        /** App launched and its transport connected - [load]/transport commands are live now. */
        fun onConnected()

        /** Raw Cast `playerState` ("PLAYING"/"PAUSED"/"BUFFERING"/"IDLE"); position/duration ms, -1 when unknown. */
        fun onMediaStatus(playerState: String?, positionMs: Long, durationMs: Long, idleReason: String?)

        /** The receiver refused/failed to launch the app (or launch timed out). Terminal. */
        fun onLaunchError(reason: String)

        /** The underlying channel died (socket error, heartbeat timeout). Terminal. */
        fun onChannelError(reason: String)

        /** Session ended without error: local [close] or the receiver closed the connection. Terminal. */
        fun onClosed()

        /** Receiver rejected/aborted a LOAD ("LOAD_FAILED"/"LOAD_CANCELLED" or timeout). NON-terminal: app and channel stay up. */
        fun onLoadFailed(type: String) {
        }
    }

    private var diagnostics = CastDiagnosticTrace()
    private val channel = CastV2Channel(host, port, ChannelListener(), authenticator, network)

    fun setDiagnostics(diagnostics: CastDiagnosticTrace) {
        this.diagnostics = diagnostics
        channel.setDiagnostics(diagnostics)
    }

    private val requestId = AtomicInteger(1)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastV2Session").apply { isDaemon = true }
    }

    /** Ensures exactly one terminal listener callback across all the async failure paths. */
    private val terminated = AtomicBoolean()

    /** Receiver app session (for receiver STOP). */
    @Volatile
    private var sessionId: String? = null

    /** Media destination (assigned by RECEIVER_STATUS). */
    @Volatile
    private var transportId: String? = null

    @Volatile
    private var mediaSessionId = -1

    @Volatile
    private var loadRequestId = 0

    @Volatile
    private var loadPending = false

    @Volatile
    private var playing = false

    @Volatile
    private var contentUrl: String? = null

    @Volatile
    private var connectedNotified = false

    init {
        channel.setDiagnostics(diagnostics)
    }

    /** Call once; [Listener.onConnected] or a terminal error follows. */
    fun start() {
        diagnostics.phase("receiver_start_requested")
        channel.open()
        schedule({
            if (!connectedNotified) {
                terminate { listener.onLaunchError("Timed out waiting for app $appId") }
                channel.close()
            }
        }, LAUNCH_TIMEOUT_MS)
    }

    /**
     * Tear the session down, sending a best-effort receiver STOP first: this sender owns
     * the Default Media Receiver, so leaving it up would strand the TV on an idle splash screen.
     */
    fun close() {
        val currentSessionId = sessionId
        if (currentSessionId != null && channel.isOpen()) {
            sendReceiver { json ->
                json.put("type", "STOP")
                json.put("sessionId", currentSessionId)
            }
        }
        channel.close() // queued behind the STOP on the writer; triggers onClosed via the listener
    }

    fun load(
        contentUrl: String,
        contentType: String,
        title: String?,
        imageUrl: String?,
        startPositionMs: Long,
        author: String?,
        autoplay: Boolean = true,
    ) {
        val transport = transportId
        if (transport == null) {
            Log.e(TAG, "load() before onConnected - ignored")
            return
        }
        try {
            val media = JSONObject().apply {
                put("contentId", contentUrl)
                put("streamType", "BUFFERED")
                put("contentType", contentType)
                if (title != null || imageUrl != null || author != null) {
                    put("metadata", buildMetadata(title, imageUrl, author))
                }
            }
            val request = requestId.getAndIncrement()
            loadRequestId = request
            loadPending = true
            this.contentUrl = contentUrl
            schedule({
                if (loadPending && loadRequestId == request) {
                    diagnostics.response("LOAD_TIMEOUT", request)
                    loadPending = false
                    dispatch { listener.onLoadFailed("LOAD_TIMEOUT") }
                }
            }, LOAD_TIMEOUT_MS)
            val message = JSONObject().apply {
                put("type", "LOAD")
                put("requestId", request)
                put("media", media)
                put("autoplay", autoplay)
                put("currentTime", startPositionMs / 1000.0)
            }
            diagnostics.request("LOAD", request)
            channel.send(CastV2Channel.SENDER_ID, transport, NS_MEDIA, message.toString())
        } catch (e: JSONException) {
            diagnostics.error("load_build_failed", e)
        }
    }

    private fun buildMetadata(title: String?, imageUrl: String?, author: String?): JSONObject =
        JSONObject().apply {
            if (author != null) put("subtitle", author)
            put("metadataType", 0) // GenericMediaMetadata
            if (title != null) put("title", title)
            if (imageUrl != null) {
                put("images", JSONArray().put(JSONObject().put("url", imageUrl)))
            }
        }

    fun play() {
        sendMediaCommand("PLAY")
    }

    fun setPlaybackRate(rate: Double) {
        sendMediaCommand("SET_PLAYBACK_RATE") { json -> json.put("playbackRate", rate) }
    }

    fun pause() {
        sendMediaCommand("PAUSE")
    }

    fun seekTo(positionMs: Long) {
        sendMediaCommand("SEEK") { json ->
            json.put("currentTime", positionMs / 1000.0)
            json.put("resumeState", if (playing) "PLAYBACK_START" else "PLAYBACK_PAUSE")
        }
    }

    /** Stop the current media (the app stays up; [close] stops the app itself). */
    fun stop() {
        sendMediaCommand("STOP")
    }

    private fun sendReceiver(fill: (JSONObject) -> Unit) {
        try {
            val json = JSONObject()
            fill(json)
            json.put("requestId", requestId.getAndIncrement())
            diagnostics.request(json.optString("type"), json.optInt("requestId"))
            channel.send(CastV2Channel.SENDER_ID, CastV2Channel.RECEIVER_ID, NS_RECEIVER, json.toString())
        } catch (e: JSONException) {
            diagnostics.error("receiver_command_build_failed", e)
        }
    }

    private fun sendMediaCommand(type: String, extra: ((JSONObject) -> Unit)? = null) {
        val transport = transportId
        val currentMediaSessionId = mediaSessionId
        if (transport == null || currentMediaSessionId < 0) {
            diagnostics.phase("command_deferred_no_media_session")
            return
        }
        try {
            val json = JSONObject().apply {
                put("type", type)
                put("mediaSessionId", currentMediaSessionId)
                put("requestId", requestId.getAndIncrement())
            }
            extra?.invoke(json)
            diagnostics.request(type, json.optInt("requestId"))
            channel.send(CastV2Channel.SENDER_ID, transport, NS_MEDIA, json.toString())
        } catch (e: JSONException) {
            diagnostics.error("media_command_build_failed", e)
        }
    }

    private fun pollMediaStatus() {
        diagnostics.sample()
        val transport = transportId
        if (transport == null || mediaSessionId < 0 || !channel.isOpen()) {
            return
        }
        try {
            val json = JSONObject().apply {
                put("type", "GET_STATUS")
                put("mediaSessionId", mediaSessionId)
                put("requestId", requestId.getAndIncrement())
            }
            channel.send(CastV2Channel.SENDER_ID, transport, NS_MEDIA, json.toString())
        } catch (e: JSONException) {
            diagnostics.error("status_build_failed", e)
        }
    }

    private inner class ChannelListener : CastV2Channel.Listener {
        override fun onOpened() {
            channel.connect(CastV2Channel.RECEIVER_ID)
            sendReceiver { json ->
                json.put("type", "LAUNCH")
                json.put("appId", appId)
            }
        }

        override fun onMessage(message: CastMessage) {
            val payloadUtf8 = message.payloadUtf8 ?: return // binary payloads are never part of the receiver/media protocol
            val payload = try {
                JSONObject(payloadUtf8)
            } catch (e: JSONException) {
                diagnostics.error("protocol_response_malformed", e)
                return
            }
            val type = payload.optString("type")
            if ("RECEIVER_STATUS" != type && "MEDIA_STATUS" != type) {
                diagnostics.response(type, payload.optInt("requestId", 0))
            }
            when (message.namespace) {
                NS_RECEIVER -> handleReceiverMessage(type, payload)
                NS_MEDIA -> handleMediaMessage(type, payload)
                CastV2Channel.NS_CONNECTION ->
                    // CLOSE from the app transport = the receiver app went away underneath us.
                    if ("CLOSE" == type && message.sourceId == transportId) {
                        terminate { listener.onClosed() }
                        channel.close()
                    }
            }
        }

        override fun onError(reason: String) {
            terminate { listener.onChannelError(reason) }
        }

        override fun onClosed() {
            terminate { listener.onClosed() }
        }
    }

    private fun handleReceiverMessage(type: String, payload: JSONObject) {
        if ("LAUNCH_ERROR" == type) {
            terminate { listener.onLaunchError("LAUNCH_ERROR: " + payload.optString("reason", "unknown")) }
            channel.close()
            return
        }
        if ("RECEIVER_STATUS" != type) {
            return
        }
        // Both the LAUNCH response and unsolicited broadcasts arrive as RECEIVER_STATUS; requestId
        // matching does not apply here. The app entry is the signal, wherever it appears.
        val app = findApp(payload, appId)
        if (app != null) diagnostics.response(type, payload.optInt("requestId", 0))
        if (app != null && transportId == null) {
            sessionId = app.sessionId
            transportId = app.transportId
            channel.connect(app.transportId)
            connectedNotified = true
            diagnostics.phase("receiver_app_ready")
            dispatch { listener.onConnected() }
            // Safe to start now: the poll no-ops until a mediaSessionId exists.
            scheduleAtFixedDelay({ pollMediaStatus() }, MEDIA_POLL_INTERVAL_MS)
        } else if (app == null && connectedNotified && payload.optJSONObject("status") != null) {
            // A status without this app means the receiver stopped it: close the session, not a transport error.
            terminate { listener.onClosed() }
            channel.close()
        }
    }

    private fun handleMediaMessage(type: String, payload: JSONObject) {
        when (type) {
            "MEDIA_STATUS" -> {
                val requestId = payload.optInt("requestId", 0)
                if (requestId != 0 && requestId < loadRequestId) return
                val statuses = payload.optJSONArray("status")
                val first = if (statuses != null && statuses.length() > 0) {
                    statuses.optJSONObject(0)
                } else {
                    null
                } ?: return // Empty status array: media session gone (finished LOAD teardown or rejected LOAD).

                val status = MediaStatus.parse(first) ?: return
                val media = first.optJSONObject("media")
                if (media != null && media.has("contentId") &&
                    (contentUrl == null || contentUrl != media.optString("contentId"))
                ) {
                    return
                }
                if (loadPending && requestId == 0 && (media == null || !media.has("contentId"))) return
                loadPending = false
                diagnostics.response(type, if (requestId != 0) requestId else loadRequestId)
                playing = "PLAYING" == status.playerState
                if (status.mediaSessionId >= 0) {
                    mediaSessionId = status.mediaSessionId
                }
                dispatch {
                    listener.onMediaStatus(
                        status.playerState,
                        status.positionMs,
                        status.durationMs,
                        status.idleReason,
                    )
                }
            }

            "LOAD_FAILED", "LOAD_CANCELLED" -> {
                // Not a channel death; the session manager's auto-fallback hangs off onLoadFailed.
                if (payload.optInt("requestId", loadRequestId) != loadRequestId) return
                loadPending = false
                dispatch { listener.onLoadFailed(type) }
            }

            else -> Unit
        }
    }

    private fun schedule(task: () -> Unit, delayMs: Long) {
        try {
            scheduler.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // already terminated
        }
    }

    private fun scheduleAtFixedDelay(task: () -> Unit, periodMs: Long) {
        try {
            scheduler.scheduleWithFixedDelay({ task() }, periodMs, periodMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // already terminated
        }
    }

    private fun terminate(terminalCallback: () -> Unit) {
        if (terminated.compareAndSet(false, true)) {
            diagnostics.finish()
            dispatch(terminalCallback)
            scheduler.shutdown()
        }
    }

    private fun dispatch(callback: () -> Unit) {
        try {
            callback()
        } catch (e: Exception) {
            diagnostics.error("session_listener_failed", e)
        }
    }

    companion object {
        private const val TAG = "CastV2Session"

        /** Google's stock Styled/Default Media Receiver - plays any URL, present on every Cast device. */
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        /** LAUNCH to first RECEIVER_STATUS that lists this app; the DMR cold-starts in a few seconds. */
        private const val LAUNCH_TIMEOUT_MS = 15_000L

        /** LOAD with no MEDIA_STATUS answer in this long = the receiver refused the stream. */
        private const val LOAD_TIMEOUT_MS = 15_000L

        private const val MEDIA_POLL_INTERVAL_MS = 5_000L

        fun forDefaultMediaReceiver(host: String, port: Int, listener: Listener, network: Network? = null) =
            CastV2Session(host, port, DEFAULT_MEDIA_RECEIVER_APP_ID, listener, network = network)

        internal fun findApp(receiverStatusPayload: JSONObject, appId: String): AppSession? {
            val status = receiverStatusPayload.optJSONObject("status") ?: return null
            val applications = status.optJSONArray("applications") ?: return null
            for (index in 0 until applications.length()) {
                val app = applications.optJSONObject(index) ?: continue
                if (appId != app.optString("appId")) continue
                val transportId = app.optString("transportId")
                val sessionId = app.optString("sessionId")
                if (transportId.isNotEmpty()) {
                    return AppSession(sessionId, transportId)
                }
            }
            return null
        }
    }

    /** The two ids a launched app hands back: receiver session + message transport. */
    internal class AppSession(val sessionId: String, val transportId: String)
}
