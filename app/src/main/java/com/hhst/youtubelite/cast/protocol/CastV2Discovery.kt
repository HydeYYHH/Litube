// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.hhst.youtubelite.diagnostics.AppLog
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.HashSet

/**
 * mDNS discovery of Google Cast receivers via [NsdManager]; Play Services is not used so the
 * stack stays self-contained. [NsdManager.resolveService] allows only one in-flight resolve per
 * listener on many Android versions, so found services resolve strictly one at a time.
 * Devices are deduped by host IP to avoid duplicate picker rows after stop/start or TXT updates.
 */
class CastV2Discovery(context: Context) {

    fun interface Listener {
        /** Found and resolved on the main thread; `port` is the CASTV2 TLS port. */
        fun onDeviceFound(name: String, host: String, port: Int)
    }

    private val context: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending resolves + in-flight flag; guarded by [lock] (NsdManager callbacks are on a binder thread). */
    private val lock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolveInFlight = false
    private val reportedHosts = HashSet<String>()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var generation = 0

    /** Read on NsdManager's binder thread and main; volatile so [stop] is seen everywhere. */
    @Volatile
    private var listener: Listener? = null

    /** Begin discovery until [stop]; safe to call once per round. */
    fun start(listener: Listener) {
        if (discoveryListener != null) stop()
        val startedGeneration = ++generation
        this.listener = listener
        synchronized(lock) {
            reportedHosts.clear()
            resolveQueue.clear()
            resolveInFlight = false
        }

        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            AppLog.event(AppLog.Category.CAST, "discovery_unavailable", critical = true)
            return
        }
        nsdManager = manager

        val discovered = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.event(
                    AppLog.Category.CAST,
                    "discovery_start_failed",
                    mapOf("error_code" to errorCode),
                    critical = true,
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.event(
                    AppLog.Category.CAST,
                    "discovery_stop_failed",
                    mapOf("error_code" to errorCode),
                    critical = true,
                )
            }

            override fun onDiscoveryStarted(serviceType: String) {
                AppLog.event(AppLog.Category.CAST, "discovery_started")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.event(AppLog.Category.CAST, "discovery_stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (startedGeneration == generation) {
                    enqueueResolve(serviceInfo, startedGeneration)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Rows persist for the round; a vanished device just fails at connect time.
            }
        }
        discoveryListener = discovered

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovered)
        } catch (e: Exception) {
            // SecurityException without local-network access; also rare binder failures.
            AppLog.event(AppLog.Category.CAST, "discovery_failed", failure = e)
            discoveryListener = null
        }
    }

    /** Idempotent; silences any late callbacks. */
    fun stop() {
        ++generation
        listener = null
        val manager = nsdManager
        val discovered = discoveryListener
        discoveryListener = null
        if (manager != null && discovered != null) {
            try {
                manager.stopServiceDiscovery(discovered)
            } catch (e: Exception) {
                // already stopped / never started
            }
        }
        synchronized(lock) {
            resolveQueue.clear()
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo, serviceGeneration: Int) {
        val owned: Boolean
        synchronized(lock) {
            resolveQueue.add(serviceInfo)
            owned = !resolveInFlight
            if (owned) resolveInFlight = true
        }
        if (owned) resolveNext(serviceGeneration)
    }

    @Suppress("DEPRECATION") // resolveService is the only path below API 34; minSdk is far below
    private fun resolveNext(serviceGeneration: Int) {
        val next: NsdServiceInfo?
        synchronized(lock) {
            if (serviceGeneration != generation) return
            next = resolveQueue.poll()
            if (next == null) {
                resolveInFlight = false
                return
            }
        }
        val manager = nsdManager ?: return
        try {
            manager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        AppLog.event(
                            AppLog.Category.CAST,
                            "device_resolve_failed",
                            mapOf("error_code" to errorCode),
                            critical = true,
                        )
                        resolveNext(serviceGeneration)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        try {
                            if (serviceGeneration == generation) {
                                reportResolved(serviceInfo, serviceGeneration)
                            }
                        } finally {
                            // A listener exception must not strand the in-flight latch and stall later resolves.
                            resolveNext(serviceGeneration)
                        }
                    }
                },
            )
        } catch (e: Exception) {
            AppLog.event(AppLog.Category.CAST, "device_resolve_failed", failure = e)
            resolveNext(serviceGeneration)
        }
    }

    private fun reportResolved(serviceInfo: NsdServiceInfo, serviceGeneration: Int) {
        @Suppress("DEPRECATION") // getHostAddresses() needs API 34; minSdk is far below
        val address: InetAddress = serviceInfo.host ?: return
        val host: String = address.hostAddress ?: return
        synchronized(lock) {
            if (!reportedHosts.add(host)) {
                return
            }
        }
        val port = if (serviceInfo.port > 0) serviceInfo.port else DEFAULT_CAST_PORT
        val name = friendlyName(serviceInfo)
        // Audio-only receivers advertise Cast and accept LOAD but cannot render video DASH;
        // dropping in discovery keeps the Listener contract untouched.
        if (!hasVideoOut(attribute(serviceInfo, TXT_CAPABILITIES))) {
            AppLog.detail(AppLog.Category.CAST, "device_filtered", mapOf("reason" to "audio_only"))
            return
        }
        listener?.let { found ->
            mainHandler.post {
                // Re-check on main: stop() may have raced the post.
                if (serviceGeneration == generation) {
                    this.listener?.let { current -> current.onDeviceFound(name, host, port) }
                }
            }
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_googlecast._tcp"

        /** TXT record key for the user-visible friendly name. */
        private const val TXT_FRIENDLY_NAME = "fn"

        /** TXT record key for the decimal capabilities bitmask; bit 0 = VIDEO_OUT. */
        private const val TXT_CAPABILITIES = "ca"

        /** `ca` bit 0: can render video; audio-only receivers lack it. */
        private const val CAPABILITY_VIDEO_OUT = 1L

        /** CASTV2 default; used only if a resolve somehow reports no port. */
        private const val DEFAULT_CAST_PORT = 8009

        /** TXT "fn" is the user-visible name; the service name is a device-id fallback. */
        private fun friendlyName(serviceInfo: NsdServiceInfo): String {
            val friendly = attribute(serviceInfo, TXT_FRIENDLY_NAME)
            if (friendly != null && friendly.isNotEmpty()) {
                return String(friendly, StandardCharsets.UTF_8)
            }
            return serviceInfo.serviceName
        }

        private fun attribute(serviceInfo: NsdServiceInfo, key: String): ByteArray? =
            try {
                serviceInfo.attributes?.get(key)
            } catch (e: Exception) {
                null
            }

        /** Fails open: a missing or unparseable `ca` counts as video-capable — hiding a real TV
         * whose TXT record could not be read is worse than listing a soundbar. */
        internal fun hasVideoOut(caValue: ByteArray?): Boolean {
            if (caValue == null || caValue.isEmpty()) {
                return true
            }
            val capabilities = String(caValue, StandardCharsets.UTF_8).trim().toLongOrNull()
                ?: return true
            return (capabilities and CAPABILITY_VIDEO_OUT) != 0L
        }
    }
}
