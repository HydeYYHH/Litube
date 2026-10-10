// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol;

import com.hhst.youtubelite.diagnostics.AppLog;
import java.util.Collections;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import android.util.Log;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * mDNS discovery of Google Cast receivers ({@code _googlecast._tcp}) via the platform
 * {@link NsdManager}. Play Services is not used, so the Cast v2 stack stays self-contained.
 * API mirrors other platform discovery: start/stop, device
 * callbacks on the MAIN thread, results stream in incrementally.
 *
 * <p><b>Resolve serialization:</b> {@code NsdManager.resolveService} allows only one in-flight
 * resolve per listener on many Android versions (concurrent calls fail with
 * FAILURE_ALREADY_ACTIVE), so found services queue up and resolve strictly one at a time.</p>
 *
 * <p>Devices are deduped by host IP: a Cast receiver advertises one service per device, but
 * rediscovery after a stop/start or a TXT update must not produce duplicate picker rows.</p>
 */
public class CastV2Discovery {

    public interface Listener {
        /** A Cast receiver was found and resolved (main thread). {@code port} is the CASTV2 TLS port. */
        void onDeviceFound(String name, String host, int port);
    }

    private static final String TAG = CastV2Discovery.class.getSimpleName();

    private static final String SERVICE_TYPE = "_googlecast._tcp";
    /** TXT record key carrying the user-visible friendly name ("Living Room TV"). */
    private static final String TXT_FRIENDLY_NAME = "fn";
    /** TXT record key carrying the decimal capabilities bitmask; bit 0 = VIDEO_OUT. */
    private static final String TXT_CAPABILITIES = "ca";
    /** {@code ca} bit 0: the device can render video. Audio-only receivers (soundbars) lack it. */
    private static final long CAPABILITY_VIDEO_OUT = 1;
    /** CASTV2 default; used only if a resolve somehow reports no port. */
    private static final int DEFAULT_CAST_PORT = 8009;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Pending resolves + in-flight flag; guarded by {@code mLock} (NsdManager callbacks are on a binder thread). */
    private final Object mLock = new Object();
    private final ArrayDeque<NsdServiceInfo> mResolveQueue = new ArrayDeque<>();
    private boolean mResolveInFlight;
    private final Set<String> mReportedHosts = new HashSet<>();

    @Nullable
    private NsdManager mNsdManager;
    @Nullable
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private volatile int mGeneration;
    /** Read on NsdManager's binder thread and main; volatile so stop() is seen everywhere. */
    @Nullable
    private volatile Listener mListener;

    public CastV2Discovery(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Begin discovery. Results stream to the listener until {@link #stop()}. Safe to call once per round. */
    public void start(Listener listener) {
        if (mDiscoveryListener != null) stop();
        final int generation = ++mGeneration;
        mListener = listener;
        synchronized (mLock) {
            mReportedHosts.clear();
            mResolveQueue.clear();
            mResolveInFlight = false;
        }

        NsdManager nsdManager = (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            AppLog.event(AppLog.Category.CAST, "discovery_unavailable", Collections.emptyMap(), null, true);
            return;
        }
        mNsdManager = nsdManager;

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                AppLog.event(AppLog.Category.CAST, "discovery_start_failed", Collections.singletonMap("error_code", errorCode), null, true);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                AppLog.event(AppLog.Category.CAST, "discovery_stop_failed", Collections.singletonMap("error_code", errorCode), null, true);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                AppLog.event(AppLog.Category.CAST, "discovery_started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                AppLog.event(AppLog.Category.CAST, "discovery_stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (generation == mGeneration) {
                    enqueueResolve(serviceInfo, generation);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // Rows persist for the round; a vanished device just fails at connect time.
            }
        };
        mDiscoveryListener = discoveryListener;

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            // SecurityException without local-network access; also covers rare binder failures.
            AppLog.event(AppLog.Category.CAST, "discovery_failed", Collections.emptyMap(), e);
            mDiscoveryListener = null;
        }
    }

    /** Stop discovery (picker dismissed). Idempotent; silences any late callbacks. */
    public void stop() {
        ++mGeneration;
        mListener = null;
        NsdManager nsdManager = mNsdManager;
        NsdManager.DiscoveryListener discoveryListener = mDiscoveryListener;
        mDiscoveryListener = null;
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                // already stopped / never started
            }
        }
        synchronized (mLock) {
            mResolveQueue.clear();
        }
    }

    // ---------------------------------------------------------------------------------
    // Serialized resolves
    // ---------------------------------------------------------------------------------

    private void enqueueResolve(NsdServiceInfo serviceInfo, int generation) {
        synchronized (mLock) {
            mResolveQueue.add(serviceInfo);
            if (mResolveInFlight) {
                return;
            }
            mResolveInFlight = true;
        }
        resolveNext(generation);
    }

    private void resolveNext(int generation) {
        NsdServiceInfo next;
        synchronized (mLock) {
            if (generation != mGeneration) return;
            next = mResolveQueue.poll();
            if (next == null) {
                mResolveInFlight = false;
                return;
            }
        }
        NsdManager nsdManager = mNsdManager;
        if (nsdManager == null) {
            return;
        }
        try {
            nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    AppLog.event(AppLog.Category.CAST, "device_resolve_failed", Collections.singletonMap("error_code", errorCode), null, true);
                    resolveNext(generation);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    if (generation == mGeneration) reportResolved(serviceInfo, generation);
                    resolveNext(generation);
                }
            });
        } catch (Exception e) {
            AppLog.event(AppLog.Category.CAST, "device_resolve_failed", Collections.emptyMap(), e);
            resolveNext(generation);
        }
    }

    @SuppressWarnings("deprecation") // getHost(): getHostAddresses() needs API 34; minSdk is far below
    private void reportResolved(NsdServiceInfo serviceInfo, int generation) {
        InetAddress address = serviceInfo.getHost();
        if (address == null) {
            return;
        }
        String host = address.getHostAddress();
        if (host == null) {
            return;
        }
        synchronized (mLock) {
            if (!mReportedHosts.add(host)) {
                return; // same device, dedupe
            }
        }
        int port = serviceInfo.getPort() > 0 ? serviceInfo.getPort() : DEFAULT_CAST_PORT;
        String name = friendlyName(serviceInfo);
        // Audio-only receivers (soundbars, speakers) are dropped here: they advertise Cast and
        // even accept a LOAD, but cannot render the video DASH. Dropping in discovery keeps the
        // Listener contract untouched.
        if (!hasVideoOut(attribute(serviceInfo, TXT_CAPABILITIES))) {
            AppLog.detail(AppLog.Category.CAST, "device_filtered", Collections.singletonMap("reason", "audio_only"));
            return;
        }
        Listener listener = mListener;
        if (listener == null) {
            return;
        }
        mMainHandler.post(() -> {
            Listener current = mListener; // re-check on main: stop() may have raced the post
            if (generation == mGeneration && current != null) {
                current.onDeviceFound(name, host, port);
            }
        });
    }

    /** TXT "fn" is the user-visible name ("Living Room TV"); the service name is a device-id fallback. */
    private static String friendlyName(NsdServiceInfo serviceInfo) {
        byte[] fn = attribute(serviceInfo, TXT_FRIENDLY_NAME);
        if (fn != null && fn.length > 0) {
            return new String(fn, StandardCharsets.UTF_8);
        }
        return serviceInfo.getServiceName();
    }

    /** Raw TXT attribute value; null when absent (or the platform throws on the lookup). */
    @Nullable
    private static byte[] attribute(NsdServiceInfo serviceInfo, String key) {
        try {
            Map<String, byte[]> attributes = serviceInfo.getAttributes();
            return attributes != null ? attributes.get(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Does the {@code ca} TXT capabilities bitmask claim VIDEO_OUT (bit 0)?
     *
     * <p>Fails OPEN: a missing or unparseable {@code ca} counts as video-capable - hiding a real
     * TV whose TXT record could not be read is far worse than listing a soundbar.</p>
     */
    static boolean hasVideoOut(@Nullable byte[] caValue) {
        if (caValue == null || caValue.length == 0) {
            return true;
        }
        try {
            long capabilities = Long.parseLong(new String(caValue, StandardCharsets.UTF_8).trim());
            return (capabilities & CAPABILITY_VIDEO_OUT) != 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
