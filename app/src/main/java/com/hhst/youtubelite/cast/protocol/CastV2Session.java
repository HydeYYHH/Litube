// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol;

import com.hhst.youtubelite.diagnostics.CastDiagnosticTrace;

import androidx.annotation.Nullable;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The receiver + media layer of the cast stack: launches a receiver app over a
 * {@link CastV2Channel} and drives media playback on it. The intended app is the Default Media
 * Receiver ({@link #DEFAULT_MEDIA_RECEIVER_APP_ID}) fed a URL from the phone-side proxy.
 *
 * <p><b>Flow.</b> {@code start()} opens the channel, connects to {@code receiver-0}, LAUNCHes the
 * app, waits for a RECEIVER_STATUS carrying the app's {@code transportId}, connects a second
 * virtual connection to that transportId and fires {@link Listener#onConnected}. Only then is
 * {@code load()} meaningful; after LOAD the first MEDIA_STATUS supplies the {@code mediaSessionId}
 * every transport command (PLAY/PAUSE/SEEK/STOP) must carry.</p>
 *
 * <p><b>Position updates.</b> Receivers do NOT push MEDIA_STATUS continuously - only on state
 * transitions. A periodic media-namespace GET_STATUS (every 5s while a media session exists) keeps
 * {@link Listener#onMediaStatus} ticking so the phone-side overlay can show live position.</p>
 *
 * <p><b>Units.</b> All times crossing this API are <b>milliseconds</b>; the seconds-double wire
 * format is confined to {@link MediaStatus} and the JSON builders here.</p>
 *
 * <p><b>Threading.</b> All public control methods are safe from any thread (they queue onto the
 * channel's writer). Listener callbacks fire on the channel's reader thread or this session's
 * scheduler thread - post to main before touching UI. Exactly one terminal callback fires:
 * {@code onLaunchError}, {@code onChannelError} or {@code onClosed}.</p>
 */
public class CastV2Session {
    private CastDiagnosticTrace mDiagnostics = new CastDiagnosticTrace();
    public void setDiagnostics(CastDiagnosticTrace diagnostics) { mDiagnostics = diagnostics; mChannel.setDiagnostics(diagnostics); }

    public interface Listener {
        /** App launched and its transport connected - load()/transport commands are live now. */
        void onConnected();

        /**
         * A media status snapshot. {@code playerState} is the raw Cast state ("PLAYING", "PAUSED",
         * "BUFFERING", "IDLE"); position/duration are ms, -1 when unknown.
         */
        void onMediaStatus(String playerState, long positionMs, long durationMs, String idleReason);

        /** The receiver refused/failed to launch the app (or launch timed out). Terminal. */
        void onLaunchError(String reason);

        /** The underlying channel died (socket error, heartbeat timeout). Terminal. */
        void onChannelError(String reason);

        /** Session ended without error: local close() or the receiver closed the connection. Terminal. */
        void onClosed();

        /**
         * The receiver rejected/aborted a LOAD ("LOAD_FAILED"/"LOAD_CANCELLED", or no response
         * within the timeout). NON-terminal: the app and channel are still up; only this playback
         * attempt is dead.
         */
        default void onLoadFailed(String type) {
        }
    }

    private static final String TAG = CastV2Session.class.getSimpleName();

    /** Google's stock Styled/Default Media Receiver - plays any URL, present on every Cast device. */
    public static final String DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845";

    static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";

    /** LAUNCH to first RECEIVER_STATUS that lists this app; the DMR cold-starts in a few seconds. */
    private static final long LAUNCH_TIMEOUT_MS = 15_000;
    /** LOAD with no MEDIA_STATUS answer in this long = the receiver refused the stream. */
    private static final long LOAD_TIMEOUT_MS = 15_000;
    /** MEDIA_STATUS poll period while a media session exists (positions are not pushed). */
    private static final long MEDIA_POLL_INTERVAL_MS = 5_000;

    private final String mAppId;
    private final Listener mListener;
    private final CastV2Channel mChannel;
    private final AtomicInteger mRequestId = new AtomicInteger(1);
    /** Poll + timeout scheduler; single daemon thread, dies with the session. */
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "CastV2Session");
        thread.setDaemon(true);
        return thread;
    });
    /** Ensures exactly one terminal listener callback across all the async failure paths. */
    private final AtomicBoolean mTerminated = new AtomicBoolean();

    @Nullable
    private volatile String mSessionId;    // receiver app session (for receiver STOP)
    @Nullable
    private volatile String mTransportId;  // media destination (assigned by RECEIVER_STATUS)
    private volatile int mMediaSessionId = -1;
    private volatile int mLoadRequestId;
    private volatile boolean mLoadPending;
    private volatile boolean mPlaying;
    private volatile String mContentUrl;
    private volatile boolean mConnectedNotified;

    public CastV2Session(String host, int port, String appId, Listener listener) {
        this(host, port, appId, listener, CastDeviceAuth::authenticate);
    }

    public CastV2Session(String host, int port, String appId, Listener listener, CastV2Channel.Authenticator authenticator) {
        mAppId = appId;
        mListener = listener;
        mChannel = new CastV2Channel(host, port, new ChannelListener(), authenticator);
        mChannel.setDiagnostics(mDiagnostics);
    }

    /** Convenience constructor: session against the Default Media Receiver. */
    public static CastV2Session forDefaultMediaReceiver(String host, int port, Listener listener) {
        return new CastV2Session(host, port, DEFAULT_MEDIA_RECEIVER_APP_ID, listener);
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    /** Open the channel and launch the app. Call once; {@code onConnected} or a terminal error follows. */
    public void start() {
        mDiagnostics.phase("receiver_start_requested");
        mChannel.open();
        schedule(() -> {
            if (!mConnectedNotified) {
                terminate(() -> mListener.onLaunchError("Timed out waiting for app " + mAppId));
                mChannel.close();
            }
        }, LAUNCH_TIMEOUT_MS);
    }

    /**
     * Tear the session down. Sends a best-effort receiver STOP for this app session first. This
     * sender owns the Default Media Receiver app, so leaving it up after disconnect would strand the TV on an
     * idle splash screen.
     */
    public void close() {
        String sessionId = mSessionId;
        if (sessionId != null && mChannel.isOpen()) {
            sendReceiver(json -> {
                json.put("type", "STOP");
                json.put("sessionId", sessionId);
            });
        }
        mChannel.close(); // queued behind the STOP on the writer; triggers onClosed via the listener
    }

    // ---------------------------------------------------------------------------------
    // Media control (valid once onConnected fired)
    // ---------------------------------------------------------------------------------

    /**
     * Load a stream on the receiver and autoplay it.
     *
     * @param contentUrl      what the receiver fetches (the phone-proxy manifest URL)
     * @param contentType     MIME type, e.g. "application/dash+xml" or "video/mp4"
     * @param title           shown on the receiver's loading/idle UI; null to skip metadata
     * @param imageUrl        poster image for the receiver UI; null to skip
     * @param startPositionMs where playback begins
     * @param author          shown under the title on the receiver UI; null to skip
     */
    public void load(String contentUrl, String contentType, @Nullable String title,
                     @Nullable String imageUrl, long startPositionMs, @Nullable String author) {
        String transportId = mTransportId;
        if (transportId == null) {
            Log.e(TAG, "load() before onConnected - ignored");
            return;
        }
        try {
            JSONObject media = new JSONObject();
            media.put("contentId", contentUrl);
            media.put("streamType", "BUFFERED");
            media.put("contentType", contentType);
            if (title != null || imageUrl != null || author != null) {
                JSONObject metadata = new JSONObject();
                if (author != null) metadata.put("subtitle", author);
                metadata.put("metadataType", 0); // GenericMediaMetadata
                if (title != null) {
                    metadata.put("title", title);
                }
                if (imageUrl != null) {
                    metadata.put("images", new JSONArray().put(new JSONObject().put("url", imageUrl)));
                }
                media.put("metadata", metadata);
            }
            JSONObject load = new JSONObject();
            load.put("type", "LOAD");
            int requestId = mRequestId.getAndIncrement();
            mLoadRequestId = requestId;
            mLoadPending = true;
            mContentUrl = contentUrl;
            load.put("requestId", requestId);
            schedule(() -> {
                if (mLoadPending && mLoadRequestId == requestId) {
                    mDiagnostics.response("LOAD_TIMEOUT", requestId);
                    mLoadPending = false;
                    dispatch(() -> mListener.onLoadFailed("LOAD_TIMEOUT"));
                }
            }, LOAD_TIMEOUT_MS);
            load.put("media", media);
            load.put("autoplay", true);
            load.put("currentTime", startPositionMs / 1000d);
            mDiagnostics.request("LOAD", requestId);
            mChannel.send(CastV2Channel.SENDER_ID, transportId, NS_MEDIA, load.toString());
        } catch (JSONException e) {
            mDiagnostics.error("load_build_failed", e);
        }
    }

    public void play() {
        sendMediaCommand("PLAY", null);
    }

    public void setPlaybackRate(double rate) {
        sendMediaCommand("SET_PLAYBACK_RATE", json -> json.put("playbackRate", rate));
    }

    public void pause() {
        sendMediaCommand("PAUSE", null);
    }

    public void seekTo(long positionMs) {
        sendMediaCommand("SEEK", json -> {
            json.put("currentTime", positionMs / 1000d);
            json.put("resumeState", mPlaying ? "PLAYBACK_START" : "PLAYBACK_PAUSE");
        });
    }

    /** Stop the current media (the app stays up; {@link #close()} stops the app itself). */
    public void stop() {
        sendMediaCommand("STOP", null);
    }

    // ---------------------------------------------------------------------------------
    // Wire helpers
    // ---------------------------------------------------------------------------------

    private interface JsonFiller {
        void fill(JSONObject json) throws JSONException;
    }

    /** Receiver-namespace command to receiver-0 with a fresh requestId. */
    private void sendReceiver(JsonFiller filler) {
        try {
            JSONObject json = new JSONObject();
            filler.fill(json);
            json.put("requestId", mRequestId.getAndIncrement());
            mDiagnostics.request(json.optString("type"), json.optInt("requestId"));
            mChannel.send(CastV2Channel.SENDER_ID, CastV2Channel.RECEIVER_ID, NS_RECEIVER, json.toString());
        } catch (JSONException e) {
            mDiagnostics.error("receiver_command_build_failed", e);
        }
    }

    /** Media transport command - requires both a transportId and a mediaSessionId. */
    private void sendMediaCommand(String type, @Nullable JsonFiller extra) {
        String transportId = mTransportId;
        int mediaSessionId = mMediaSessionId;
        if (transportId == null || mediaSessionId < 0) {
            mDiagnostics.phase("command_deferred_no_media_session");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", type);
            json.put("mediaSessionId", mediaSessionId);
            json.put("requestId", mRequestId.getAndIncrement());
            if (extra != null) {
                extra.fill(json);
            }
            mDiagnostics.request(type, json.optInt("requestId"));
            mChannel.send(CastV2Channel.SENDER_ID, transportId, NS_MEDIA, json.toString());
        } catch (JSONException e) {
            mDiagnostics.error("media_command_build_failed", e);
        }
    }

    /** Media-namespace GET_STATUS - the position poll (see class javadoc). */
    private void pollMediaStatus() {
        mDiagnostics.sample();
        String transportId = mTransportId;
        if (transportId == null || mMediaSessionId < 0 || !mChannel.isOpen()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("type", "GET_STATUS");
            json.put("mediaSessionId", mMediaSessionId);
            json.put("requestId", mRequestId.getAndIncrement());
            mChannel.send(CastV2Channel.SENDER_ID, transportId, NS_MEDIA, json.toString());
        } catch (JSONException e) {
            mDiagnostics.error("status_build_failed", e);
        }
    }

    // ---------------------------------------------------------------------------------
    // Inbound
    // ---------------------------------------------------------------------------------

    private class ChannelListener implements CastV2Channel.Listener {
        @Override
        public void onOpened() {
            mChannel.connect(CastV2Channel.RECEIVER_ID);
            sendReceiver(json -> {
                json.put("type", "LAUNCH");
                json.put("appId", mAppId);
            });
        }

        @Override
        public void onMessage(CastMessage message) {
            String payloadUtf8 = message.getPayloadUtf8();
            if (payloadUtf8 == null) {
                return; // binary payloads are never part of the receiver/media protocol
            }
            JSONObject payload;
            try {
                payload = new JSONObject(payloadUtf8);
            } catch (JSONException e) {
                mDiagnostics.error("protocol_response_malformed", e);
                return;
            }
            String type = payload.optString("type");
            if (!"RECEIVER_STATUS".equals(type) && !"MEDIA_STATUS".equals(type)) mDiagnostics.response(type, payload.optInt("requestId", 0));
            switch (message.getNamespace()) {
                case NS_RECEIVER:
                    handleReceiverMessage(type, payload);
                    break;
                case NS_MEDIA:
                    handleMediaMessage(type, payload);
                    break;
                case CastV2Channel.NS_CONNECTION:
                    // CLOSE from the app transport = the receiver app went away underneath us.
                    if ("CLOSE".equals(type) && message.getSourceId().equals(mTransportId)) {
                        terminate(mListener::onClosed);
                        mChannel.close();
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onError(String reason) {
            terminate(() -> mListener.onChannelError(reason));
        }

        @Override
        public void onClosed() {
            terminate(mListener::onClosed);
        }
    }

    private void handleReceiverMessage(String type, JSONObject payload) {
        if ("LAUNCH_ERROR".equals(type)) {
            terminate(() -> mListener.onLaunchError("LAUNCH_ERROR: " + payload.optString("reason", "unknown")));
            mChannel.close();
            return;
        }
        if (!"RECEIVER_STATUS".equals(type)) {
            return;
        }
        // Both the LAUNCH response and unsolicited broadcasts arrive as RECEIVER_STATUS; requestId
        // matching does not apply here. The app entry is the signal, wherever it appears.
        AppSession app = findApp(payload, mAppId);
        if (app != null) mDiagnostics.response(type, payload.optInt("requestId", 0));
        if (app != null && mTransportId == null) {
            mSessionId = app.sessionId;
            mTransportId = app.transportId;
            mChannel.connect(app.transportId);
            mConnectedNotified = true;
            mDiagnostics.phase("receiver_app_ready");
            dispatch(mListener::onConnected);
            // Start the position poll now; it no-ops until a mediaSessionId exists.
            scheduleAtFixedDelay(this::pollMediaStatus, MEDIA_POLL_INTERVAL_MS);
        } else if (app == null && mConnectedNotified && payload.optJSONObject("status") != null) {
            // A status whose applications array no longer contains this app means
            // the receiver stopped it. Close the session; this is not a transport error.
            terminate(mListener::onClosed);
            mChannel.close();
        }
    }

    private void handleMediaMessage(String type, JSONObject payload) {
        switch (type) {
            case "MEDIA_STATUS": {
                int requestId = payload.optInt("requestId", 0);
                if (requestId != 0 && requestId < mLoadRequestId) return;
                JSONArray statuses = payload.optJSONArray("status");
                JSONObject first = statuses != null && statuses.length() > 0 ? statuses.optJSONObject(0) : null;
                if (first == null) {
                    // Empty status array: media session gone (finished LOAD teardown or rejected LOAD).
                    return;
                }
                MediaStatus status = MediaStatus.parse(first);
                JSONObject media = first.optJSONObject("media");
                if (media != null && media.has("contentId") && (mContentUrl == null || !mContentUrl.equals(media.optString("contentId")))) return;
                if (mLoadPending && requestId == 0 && (media == null || !media.has("contentId"))) return;
                mLoadPending = false;
                mDiagnostics.response(type, requestId != 0 ? requestId : mLoadRequestId);
                mPlaying = "PLAYING".equals(status.playerState);
                if (status.mediaSessionId >= 0) {
                    mMediaSessionId = status.mediaSessionId;
                }
                dispatch(() -> mListener.onMediaStatus(status.playerState, status.positionMs, status.durationMs, status.idleReason));
                break;
            }
            case "LOAD_FAILED":
            case "LOAD_CANCELLED":
                // Not a channel death, but the integrator must know playback never started. The
                // IDLE status keeps position/overlay consumers unstuck; onLoadFailed carries the
                // actual failure signal (the session manager's auto-fallback hangs off it).
                if (payload.optInt("requestId", mLoadRequestId) != mLoadRequestId) return;
                mLoadPending = false;
                dispatch(() -> mListener.onLoadFailed(type));
                break;
            case "INVALID_REQUEST":
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------------------------
    // RECEIVER_STATUS parsing
    // ---------------------------------------------------------------------------------

    /** The two ids a launched app hands back: receiver session + message transport. */
    static final class AppSession {
        final String sessionId;
        final String transportId;

        AppSession(String sessionId, String transportId) {
            this.sessionId = sessionId;
            this.transportId = transportId;
        }
    }

    /**
     * Find an app entry by appId in a RECEIVER_STATUS payload
     * ({@code {"type":"RECEIVER_STATUS","status":{"applications":[{...}]}}}) and return its ids.
     * Returns null when the app is absent from the status (not launched or already
     * stopped) or has no transportId yet (still starting).
     */
    @Nullable
    static AppSession findApp(JSONObject receiverStatusPayload, String appId) {
        JSONObject status = receiverStatusPayload.optJSONObject("status");
        if (status == null) {
            return null;
        }
        JSONArray applications = status.optJSONArray("applications");
        if (applications == null) {
            return null;
        }
        for (int i = 0; i < applications.length(); i++) {
            JSONObject app = applications.optJSONObject(i);
            if (app != null && appId.equals(app.optString("appId"))) {
                String transportId = app.optString("transportId");
                String sessionId = app.optString("sessionId");
                if (!transportId.isEmpty()) {
                    return new AppSession(sessionId, transportId);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Scheduling + terminal-state helpers
    // ---------------------------------------------------------------------------------

    private void schedule(Runnable task, long delayMs) {
        try {
            mScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // already terminated
        }
    }

    private void scheduleAtFixedDelay(Runnable task, long periodMs) {
        try {
            mScheduler.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // already terminated
        }
    }

    /** Fire a terminal callback exactly once and stop the scheduler. */
    private void terminate(Runnable terminalCallback) {
        if (mTerminated.compareAndSet(false, true)) {
            mDiagnostics.finish();
            dispatch(terminalCallback);
            mScheduler.shutdown();
        }
    }

    private void dispatch(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            mDiagnostics.error("session_listener_failed", e);
        }
    }
}
