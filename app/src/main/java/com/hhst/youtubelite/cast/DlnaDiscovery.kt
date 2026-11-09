package com.hhst.youtubelite.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.hhst.youtubelite.diagnostics.AppLog
import com.yinnho.upnpcast.DLNACast
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

internal class RealDlnaEngine(private val context: Context, private val onNetworkChanged: (() -> Unit)? = null) : DlnaEngine {
    private var targetHost: String? = null
    private var networkHandle: Long? = null
    private var transport: DlnaTransport? = null
    private val resolved = linkedMapOf<String, DlnaRenderer>()

    override suspend fun search(timeoutMs: Long): List<DLNACast.Device> = coroutineScope {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks = LocalCastNetwork.networks(manager).ifEmpty { listOf(null) }
        val clients = networks.associateWith { DlnaTransport(LocalCastNetwork.httpClient(it)) }
        val legacy = async { bestEffort { DLNACast.search(timeoutMs).map { it.id } }.orEmpty() }
        val probe = async { networks.map { network ->
            async { bestEffort { discoverLocations(timeoutMs, network) }.orEmpty() }
        }.awaitAll().flatten() }
        val locations = (legacy.await() + probe.await()).distinct().take(MAX_LOCATIONS)
        val renderers = try {
            collectDescribed(locations, DESCRIBE_CONCURRENCY, DESCRIPTION_BUDGET_MS) { value ->
                bestEffort { DlnaDescription.location(value)?.let { location ->
                    val network = LocalCastNetwork.select(manager, location.host)
                    val client = clients[network] ?: DlnaTransport(LocalCastNetwork.httpClient(network))
                    client.describe(location)
                } }
            }
        } finally { clients.values.forEach { it.close() } }
        resolved.clear()
        renderers.forEach { resolved[it.location.toString()] = it }
        displayName(renderers).mapIndexed { index, name ->
            val renderer = renderers[index]
            DLNACast.Device(renderer.location.toString(), name, renderer.location.host, true)
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun cast(device: DLNACast.Device, url: String, title: String, mimeType: String): Boolean {
        val location = DlnaDescription.location(device.id) ?: error("Invalid device description address")
        val renderer = resolved[device.id] ?: DlnaTransport(LocalCastNetwork.httpClient(
            LocalCastNetwork.select(context.getSystemService(ConnectivityManager::class.java), location.host)
        )).describe(location)
            ?: throw DlnaRequestException("description", location, detail = "No AVTransport service in device description")
        targetHost = renderer.control.host
        // A newly selected receiver may deliberately be on another interface; only a
        // change during an existing session should tear that session down.
        val connection = transport(notifyNetworkChange = false)
        return connection.cast(renderer, url, title, mimeType)
    }
    override suspend fun awaitMediaReady(url: String) = transport().awaitMediaReady(url)
    override suspend fun play() = transport().play()
    override suspend fun pause() = transport().pauseAndConfirm()
    override suspend fun stop() = transport?.stop() ?: false
    override suspend fun seek(positionMs: Long) = transport().seek(positionMs)
    override suspend fun progress() = transport().progress()
    override suspend fun transportState() = transport().state()
    override fun clearProgressCache() = Unit

    /** Rebinds the HTTP client when the preferred local network changes, so a Wi-Fi switch
     *  cannot keep issuing commands through a stale socket factory; old-network description
     *  addresses are dropped. */
    private fun transport(notifyNetworkChange: Boolean = true): DlnaTransport {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = LocalCastNetwork.select(manager, targetHost)
        val handle = active?.networkHandle
        val cached = transport
        if (cached != null && handle == networkHandle) return cached
        if (notifyNetworkChange && cached != null && handle != networkHandle) {
            AppLog.event(AppLog.Category.CAST, "dlna_network_changed",
                fields = mapOf("had_session_binding" to true))
            onNetworkChanged?.invoke()
        }
        networkHandle = handle
        resolved.clear()
        val client = LocalCastNetwork.httpClient(active)
        cached?.close()
        return DlnaTransport(client).also { transport = it }
    }

    private suspend fun <T> bestEffort(action: suspend () -> T): T? = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AppLog.event(AppLog.Category.CAST, "dlna_discovery_candidate_failed", failure = error)
        null
    }

    companion object {
        const val MAX_LOCATIONS = 64
        const val DESCRIBE_CONCURRENCY = 4
        const val DESCRIPTION_BUDGET_MS = 8_000L
    }
}

/** Verified renderers stream in as their descriptions complete under one overall budget, so a
 *  wall of offline advertisements can never delay the usable devices past [budgetMs]. */
internal suspend fun collectDescribed(
    locations: List<String>,
    concurrency: Int,
    budgetMs: Long,
    describe: suspend (String) -> DlnaRenderer?,
): List<DlnaRenderer> = coroutineScope {
    val deadline = System.nanoTime() + budgetMs * 1_000_000
    val channel = Channel<DlnaRenderer>(Channel.UNLIMITED)
    val permits = Semaphore(concurrency)
    val workers = locations.map { value -> launch {
        permits.withPermit {
            describe(value)?.let { channel.trySend(it) }
        }
    } }
    val found = ArrayList<DlnaRenderer>()
    while (System.nanoTime() < deadline) {
        if (workers.none { it.isActive }) break
        val slice = minOf(deadline - System.nanoTime(), 250_000_000L)
        withTimeoutOrNull(slice / 1_000_000) { channel.receive() }?.let { found.add(it) } ?: continue
    }
    // Workers may finish between the activity check and the receive: drain what landed.
    workers.forEach { it.cancel() }
    channel.close()
    while (true) channel.tryReceive().getOrNull()?.let { found.add(it) } ?: break
    found
}

/** Duplicate friendly names get a factual suffix: the model when it separates them, else the
 *  description host; URLs and guessed device types never enter the picker label. */
internal fun displayName(renderers: List<DlnaRenderer>): List<String> {
    val grouped = renderers.groupBy { it.name }
    return renderers.map { renderer ->
        val duplicates = grouped[renderer.name].orEmpty()
        if (duplicates.size <= 1) renderer.name
        else {
            val model = renderer.model
            if (model.isNotBlank() && duplicates.count { it.model == model } == 1) "${renderer.name} ($model)"
            else "${renderer.name} (${renderer.location.host})"
        }
    }
}

internal suspend fun discoverLocations(timeoutMs: Long, network: Network? = null): List<String> = withContext(Dispatchers.IO) {
    MulticastSocket(null).use { socket ->
        network?.bindSocket(socket)
        socket.bind(InetSocketAddress(0))
        socket.soTimeout = 250
        socket.timeToLive = 2
        val address = InetAddress.getByName("239.255.255.250")
        for (target in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1", "ssdp:all")) {
            val bytes = ssdpSearch(target).toByteArray(Charsets.US_ASCII)
            socket.send(java.net.DatagramPacket(bytes, bytes.size, address, 1900))
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val buffer = ByteArray(16 * 1024)
        val packet = java.net.DatagramPacket(buffer, buffer.size)
        val locations = linkedSetOf<String>()
        while (System.nanoTime() < deadline && locations.size < RealDlnaEngine.MAX_LOCATIONS) {
            coroutineContext.ensureActive()
            try {
                packet.length = buffer.size
                socket.receive(packet)
                ssdpLocation(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))?.let(locations::add)
            } catch (_: SocketTimeoutException) {
                // Poll cancellation between receive deadlines.
            }
        }
        locations.toList()
    }
}

internal fun ssdpSearch(target: String): String = listOf(
    "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", "MAN: \"ssdp:discover\"", "MX: 2", "ST: $target", "", "",
).joinToString("\r\n")

internal fun ssdpLocation(message: String): String? {
    if (!message.lineSequence().first().startsWith("HTTP/1.1 200", true)) return null
    return message.lineSequence().drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon < 0 || !line.substring(0, colon).trim().equals("location", true)) null
        else DlnaDescription.location(line.substring(colon + 1).trim())?.toString()
    }.firstOrNull()
}
