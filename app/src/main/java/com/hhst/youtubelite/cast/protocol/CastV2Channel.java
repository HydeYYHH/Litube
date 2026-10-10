// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol;

import com.hhst.youtubelite.diagnostics.CastDiagnosticTrace;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONObject;

/**
 * One CASTV2 socket to one receiver: TLS to port 8009, length-prefixed {@link CastMessage} frames
 * both ways, plus the two transport-level namespaces every session needs - virtual connections
 * ({@code CONNECT}/{@code CLOSE}) and the PING/PONG heartbeat. Everything app-level (receiver
 * control, media) lives above this in {@link CastV2Session}.
 *
 * <p><b>Threading.</b> {@code open()} and every send are serialized on a single writer executor,
 * so callers may invoke any public method from any thread (including main - no network ever runs
 * on the caller). Inbound messages are decoded on a dedicated reader thread and dispatched to the
 * {@link Listener} <i>on that reader thread</i> - listeners must not block and must post to main
 * themselves if they touch UI.</p>
 *
 * <p><b>Lifecycle.</b> Exactly one terminal callback fires per channel: {@code onError} (socket
 * died, heartbeat timeout, handshake failure) or {@code onClosed} (local {@code close()}). All
 * dispatch paths are guarded so close-during-callback races collapse into that single terminal
 * event; late messages after the terminal state are dropped silently.</p>
 */
public class CastV2Channel {
    private volatile CastDiagnosticTrace mDiagnostics = new CastDiagnosticTrace();
    public void setDiagnostics(CastDiagnosticTrace diagnostics) { mDiagnostics = diagnostics; }
    public interface Authenticator {
        void authenticate(SSLSocket socket, InputStream input, OutputStream output) throws Exception;
    }
    private final Authenticator mAuthenticator;

    public interface Listener {
        /** TLS is up and the reader/heartbeat are running (writer thread). Send CONNECT etc. from here. */
        void onOpened();

        /** A non-heartbeat message arrived (reader thread - do not block, post to main for UI). */
        void onMessage(CastMessage message);

        /** The channel died: connect/handshake failure, socket error, heartbeat timeout. Terminal. */
        void onError(String reason);

        /** Local {@link #close()} finished. Terminal; not fired after {@code onError}. */
        void onClosed();
    }

    private static final String TAG = CastV2Channel.class.getSimpleName();

    /** Sender id on every outbound message; receivers address replies to it. */
    public static final String SENDER_ID = "sender-0";
    /** The platform receiver - the always-on destination for receiver-namespace commands. */
    public static final String RECEIVER_ID = "receiver-0";

    public static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    public static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";

    /** Cast receivers answer within one LAN round trip. A longer timeout only delays the picker for an unreachable address. */
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final long HEARTBEAT_INTERVAL_MS = 5_000;
    /** No inbound traffic for this long = the receiver is gone (3 missed heartbeat rounds). */
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;

    private final String mHost;
    private final int mPort;
    private final Listener mListener;

    /**
     * Single thread for connect + all writes: gives us free write synchronization AND guarantees
     * ordering (a send queued right after open() runs strictly after the handshake).
     */
    private final ScheduledExecutorService mWriter = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "CastV2Writer");
        thread.setDaemon(true);
        return thread;
    });

    /** Destinations that received CONNECT; CLOSE goes to each of them on teardown. */
    private final Set<String> mConnectedDestinations = ConcurrentHashMap.newKeySet();

    /** Terminal-state latch: set by exactly one of fail()/doClose(); gates all callbacks + sends. */
    private final AtomicBoolean mTerminated = new AtomicBoolean();

    @Nullable
    private volatile SSLSocket mSocket;
    @Nullable
    private volatile InputStream mIn;
    @Nullable
    private volatile OutputStream mOut;
    @Nullable
    private volatile Thread mReader;
    @Nullable
    private ScheduledFuture<?> mHeartbeat; // writer thread only
    private volatile boolean mOpened;
    /** Last time ANY frame arrived; heartbeat PONGs and app messages both count as liveness. */
    private volatile long mLastInboundMs;

    public CastV2Channel(String host, int port, Listener listener) {
        this(host, port, listener, CastDeviceAuth::authenticate);
    }

    public CastV2Channel(String host, int port, Listener listener, Authenticator authenticator) {
        mHost = host;
        mPort = port;
        mListener = listener;
        mAuthenticator = authenticator;
    }

    /** TLS connected and not yet terminated. */
    public boolean isOpen() {
        return mOpened && !mTerminated.get();
    }

    // ---------------------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------------------

    /** Connect asynchronously; {@code onOpened} or {@code onError} follows. Call once per instance. */
    public void open() {
        execute(this::doOpen);
    }

    private void doOpen() {
        if (mTerminated.get()) {
            return;
        }
        try {
            mDiagnostics.phase("channel_connect_started");
            SSLSocket socket = (SSLSocket) provisionalSocketFactory().createSocket();
            mSocket = socket;
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.connect(new InetSocketAddress(mHost, mPort), CONNECT_TIMEOUT_MS);
            mDiagnostics.phase("channel_tls_started");
            socket.startHandshake();
            mSocket = socket;
            mIn = new BufferedInputStream(socket.getInputStream());
            mOut = new BufferedOutputStream(socket.getOutputStream());
            mDiagnostics.phase("device_auth_started");
            mAuthenticator.authenticate(socket, mIn, mOut);
            mDiagnostics.phase("device_auth_succeeded");
            socket.setSoTimeout(0);
            mLastInboundMs = SystemClock.elapsedRealtime();
            mOpened = true;

            Thread reader = new Thread(this::readLoop, "CastV2Reader");
            reader.setDaemon(true);
            mReader = reader;
            reader.start();

            mHeartbeat = mWriter.scheduleWithFixedDelay(this::heartbeatTick,
                    HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

            mDiagnostics.phase("channel_opened");
            if (!mTerminated.get()) {
                dispatch(mListener::onOpened);
            }
        } catch (Exception e) {
            mDiagnostics.error("channel_connect_or_auth_failed", e);
            fail("Connect to " + mHost + ":" + mPort + " failed: " + e);
        }
    }

    /** Provisional TLS only. DeviceAuth binds this certificate before onOpened or media writes. */
    private static javax.net.ssl.SSLSocketFactory provisionalSocketFactory() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // Authentication occurs over this socket before any application traffic.
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return context.getSocketFactory();
    }

    // ---------------------------------------------------------------------------------
    // Send
    // ---------------------------------------------------------------------------------

    /**
     * Open a virtual connection to a destination. Required once for {@link #RECEIVER_ID} before any
     * receiver command and once per launched app transportId before media traffic. Idempotent.
     */
    public void connect(String destinationId) {
        if (destinationId != null && mConnectedDestinations.add(destinationId)) {
            send(SENDER_ID, destinationId, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
        }
    }

    /** Queue one message. Safe from any thread; silently dropped once the channel is terminated. */
    public void send(String sourceId, String destinationId, String namespace, String jsonPayload) {
        execute(() -> doSend(sourceId, destinationId, namespace, jsonPayload));
    }

    /** Writer thread only. */
    private void doSend(String sourceId, String destinationId, String namespace, String jsonPayload) {
        OutputStream out = mOut;
        if (mTerminated.get() || out == null) {
            return;
        }
        try {
            CastMessageCodec.writeFramed(out, CastMessage.utf8(sourceId, destinationId, namespace, jsonPayload));
            out.flush();
        } catch (Exception e) {
            mDiagnostics.error("channel_send_failed", e);
            fail("Send failed: " + e);
        }
    }

    // ---------------------------------------------------------------------------------
    // Reader + heartbeat
    // ---------------------------------------------------------------------------------

    private void readLoop() {
        try {
            InputStream in = mIn;
            while (in != null && !mTerminated.get()) {
                CastMessage message = CastMessageCodec.readFramed(in);
                if (message == null) {
                    fail("Receiver closed the connection");
                    return;
                }
                mLastInboundMs = SystemClock.elapsedRealtime();
                if (NS_HEARTBEAT.equals(message.getNamespace())) {
                    handleHeartbeat(message);
                    continue;
                }
                dispatch(() -> mListener.onMessage(message));
            }
        } catch (Exception e) {
            // Socket teardown from close() lands here as an exception - only report real deaths.
            if (!mTerminated.get()) {
                mDiagnostics.error("channel_read_failed", e);
                fail("Read failed: " + e);
            }
        }
    }

    /** Reply to an inbound PING with PONG. Some receivers probe the sender. An inbound PONG only counts as traffic. */
    private void handleHeartbeat(CastMessage message) {
        try {
            JSONObject payload = new JSONObject(message.getPayloadUtf8() != null ? message.getPayloadUtf8() : "{}");
            if ("PING".equals(payload.optString("type"))) {
                send(SENDER_ID, message.getSourceId(), NS_HEARTBEAT, "{\"type\":\"PONG\"}");
            }
        } catch (Exception e) {
            mDiagnostics.error("heartbeat_malformed", e);
        }
    }

    /** Writer thread, every 5s: liveness check first, then an outbound PING. */
    private void heartbeatTick() {
        if (mTerminated.get()) {
            return;
        }
        if (SystemClock.elapsedRealtime() - mLastInboundMs > HEARTBEAT_TIMEOUT_MS) {
            mDiagnostics.error("heartbeat_timeout", null);
            fail("Heartbeat timeout (no traffic for " + HEARTBEAT_TIMEOUT_MS + "ms)");
            return;
        }
        doSend(SENDER_ID, RECEIVER_ID, NS_HEARTBEAT, "{\"type\":\"PING\"}");
    }

    // ---------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------

    /**
     * Close locally: best-effort CLOSE to every connected destination, then kill the socket and
     * both threads. Idempotent; {@code onClosed} fires once (unless {@code onError} already did).
     */
    public void close() {
        execute(this::doClose);
    }

    /** Writer thread only. */
    private void doClose() {
        if (!mTerminated.compareAndSet(false, true)) {
            return;
        }
        OutputStream out = mOut;
        if (out != null) {
            for (String destination : mConnectedDestinations) {
                try {
                    CastMessageCodec.writeFramed(out,
                            CastMessage.utf8(SENDER_ID, destination, NS_CONNECTION, "{\"type\":\"CLOSE\"}"));
                } catch (Exception e) {
                    break; // socket already dead; the CLOSEs are courtesy only
                }
            }
            try {
                out.flush();
            } catch (Exception e) {
                // ignore
            }
        }
        teardown();
        dispatch(mListener::onClosed);
    }

    /** Any thread. Terminal error path; exactly-once with doClose() via mTerminated. */
    private void fail(String reason) {
        if (!mTerminated.compareAndSet(false, true)) {
            return;
        }
        mDiagnostics.error("channel_failed", null);
        dispatch(() -> mListener.onError(reason));
        teardown();
    }

    private void teardown() {
        ScheduledFuture<?> heartbeat = mHeartbeat;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        SSLSocket socket = mSocket;
        if (socket != null) {
            try {
                socket.close(); // also unblocks the reader's readFramed()
            } catch (Exception e) {
                // ignore
            }
        }
        Thread reader = mReader;
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
        mWriter.shutdown(); // lets an in-flight task (possibly this one) finish, accepts no more
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /** Queue on the writer. Rejection after shutdown means the channel has already terminated. */
    private void execute(Runnable task) {
        try {
            mWriter.execute(task);
        } catch (RejectedExecutionException e) {
        }
    }

    /** Listener exceptions must never kill the reader/writer threads. */
    private void dispatch(Runnable callback) {
        try {
            callback.run();
        } catch (Exception e) {
            mDiagnostics.error("channel_listener_failed", e);
        }
    }
}
