package com.hhst.youtubelite.diagnostics

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat

/** Snapshot only public capability flags; device addresses and notification contents never enter logs. */
object AndroidDiagnosticState {
    @Volatile private var networkAvailable: Boolean? = null
    @Volatile private var foreground = false
    @Volatile private var networkState = emptyMap<String, Any?>()
    fun install(app: Application) {
        var started = 0
        app.registerActivityLifecycleCallbacks(@Suppress("UNUSED_PARAMETER") object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (!foreground) { foreground = true; AppLog.event(AppLog.Category.APP, "foreground") }
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) { foreground = false; AppLog.event(AppLog.Category.APP, "background") }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        fun update() {
            val network = connectivity.activeNetwork
            val caps = network?.let(connectivity::getNetworkCapabilities)
            networkAvailable = network != null && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val next = mapOf("connected" to networkAvailable, "validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                "wifi" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), "cellular" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                "vpn" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN), "metered" to connectivity.isActiveNetworkMetered)
            if (next != networkState) { networkState = next; AppLog.event(AppLog.Category.APP, "network_changed", next) }
        }
        runCatching {
            update()
            val callback = @Suppress("UNUSED_PARAMETER") object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update()
                override fun onLost(network: Network) = update()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
            }
            if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(callback)
            else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        }.onFailure { AppLog.event(AppLog.Category.APP, "network_monitor_failed", failure = it) }
        AppLog.registerSnapshot("application") { networkState + mapOf("foreground" to foreground,
            "notification_permission" to NotificationManagerCompat.from(app).areNotificationsEnabled(), "available_storage_bytes" to app.filesDir.usableSpace) }
        val main = Handler(Looper.getMainLooper())
        val acknowledged = AtomicLong(SystemClock.elapsedRealtime())
        val pending = AtomicBoolean()
        val responsiveness = DiagnosticProgressWatchdog(5_000, { state, duration ->
            AppLog.event(AppLog.Category.APP, "main_thread.$state", mapOf("reason" to "heartbeat_not_acknowledged",
                "duration_ms" to duration, "system_anr_confirmed" to false,
                "frames" to if (state == "stalled") Looper.getMainLooper().thread.stackTrace.take(32).map { it.toString() } else emptyList<String>()),
                critical = state == "stalled", level = if (state == "stalled") DiagnosticLevel.WARN else DiagnosticLevel.INFO)
        })
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "diagnostic-main-heartbeat").apply { isDaemon = true } }
            .scheduleAtFixedRate({ runCatching {
                if (pending.compareAndSet(false, true)) main.post { acknowledged.set(SystemClock.elapsedRealtime()); pending.set(false) }
                responsiveness.sample(SystemClock.elapsedRealtime(), acknowledged.get(), foreground && !Debug.isDebuggerConnected())
            } }, 1, 1, TimeUnit.SECONDS)
    }
}
