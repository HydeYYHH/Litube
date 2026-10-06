package com.hhst.youtubelite.downloader.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.extractor.Promise
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** Relays connectivity to [DownloadCoordinator.onNetworkRestored] without unpausing users. */
object DownloadNetworkRestore {
    fun start(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val koin = GlobalContext.getOrNull() ?: return
                    Promise.DEFAULT_SCOPE.launch {
                        koin.get<DownloadCoordinator>().onNetworkRestored()
                    }
                }
            },
        )
    }
}
