package com.hhst.youtubelite.cast

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Dns

/** Local receiver traffic must not follow the Internet/VPN route or an unrelated second WLAN. */
internal object LocalCastNetwork {
    fun networks(manager: ConnectivityManager?): List<Network> = manager?.allNetworks.orEmpty().filter { network ->
        manager?.getNetworkCapabilities(network)?.let { capabilities ->
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true
    }

    fun select(manager: ConnectivityManager?, host: String? = null): Network? {
        val target = numericAddress(host)
        val active = manager?.activeNetwork
        return choose(networks(manager).map { network ->
            Candidate(network, network == active, manager?.getLinkProperties(network)?.routes.orEmpty()
                .filter { Build.VERSION.SDK_INT < 33 || it.type == RouteInfo.RTN_UNICAST }
                .map { Subnet(it.destination.address, it.destination.prefixLength) })
        }, target)
    }

    fun httpClient(network: Network?): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY).followRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS)
        .apply {
            network?.let {
                socketFactory(it.socketFactory)
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = it.getAllByName(hostname).toList()
                })
            }
        }.build()

    internal data class Subnet(val address: InetAddress, val prefixLength: Int) {
        fun contains(target: InetAddress): Boolean {
            val left = address.address
            val right = target.address
            if (left.size != right.size || prefixLength !in 0..left.size * 8) return false
            return left.indices.all { index ->
                val bits = (prefixLength - index * 8).coerceIn(0, 8)
                val mask = (0xff shl (8 - bits)) and 0xff
                (left[index].toInt() and mask) == (right[index].toInt() and mask)
            }
        }
    }

    internal data class Candidate<T>(val network: T, val active: Boolean, val routes: List<Subnet>)

    internal fun <T> choose(candidates: List<Candidate<T>>, target: InetAddress?): T? = candidates.maxWithOrNull(
        compareBy<Candidate<T>> { candidate ->
            if (target == null) -1 else candidate.routes.filter { it.contains(target) }.maxOfOrNull { it.prefixLength } ?: -1
        }.thenBy { it.active }
    )?.network

    // Discovery supplies numeric IPs. Never perform a blocking DNS lookup on the UI thread.
    private fun numericAddress(host: String?): InetAddress? = host?.takeIf {
        it.contains(':') || it.matches(Regex("[0-9]+(\\.[0-9]+){3}"))
    }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
}
