package com.hhst.youtubelite.downloader.io

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

enum class NetworkKind {
    NONE,
    WIFI,
    CELLULAR,
    OTHER,
}

/**
 * Wi-Fi-only is opt-in. Default allows the current network.
 * Network loss is reported as [NetworkKind.NONE] so transport can wait without
 * consuming retry budget.
 */
object DownloadNetworkPolicy {
    fun canTransfer(wifiOnly: Boolean, kind: NetworkKind): Boolean {
        if (kind == NetworkKind.NONE) return false
        if (!wifiOnly) return true
        return kind == NetworkKind.WIFI || kind == NetworkKind.OTHER
    }

    fun waitingNetwork(wifiOnly: Boolean, kind: NetworkKind): Boolean =
        !canTransfer(wifiOnly, kind)
}

fun interface NetworkMonitor {
    fun current(): NetworkKind
}

object AssumeAvailableNetwork : NetworkMonitor {
    override fun current(): NetworkKind = NetworkKind.WIFI
}

class AndroidNetworkMonitor(
    private val context: Context,
) : NetworkMonitor {
    override fun current(): NetworkKind {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return NetworkKind.OTHER
        val network = cm.activeNetwork ?: return NetworkKind.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            else -> NetworkKind.OTHER
        }
    }
}
