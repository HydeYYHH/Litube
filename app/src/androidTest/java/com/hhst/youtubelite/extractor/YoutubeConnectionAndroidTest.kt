package com.hhst.youtubelite.extractor

import android.os.Bundle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Public, credential-free dial diagnostics; exports address families, never addresses. */
class YoutubeConnectionAndroidTest {
    @get:org.junit.Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)
    @Test fun connectionFamilies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = InstrumentationRegistry.getArguments().getString("target", "youtube")
        require(target in setOf("youtube", "google"))
        val host = if (target == "google") "www.google.com" else "www.youtube.com"
        val connectivity = instrumentation.targetContext.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val records = mutableListOf<Map<String, Any>>()
        for (family in listOf("default", "ipv4", "ipv6")) {
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val dns = object : Dns {
                override fun lookup(name: String) = Dns.SYSTEM.lookup(name).also { answers ->
                events += "dns:${answers.count { it is Inet4Address }}v4:${answers.count { it is Inet6Address }}v6"
                if (answers.any { val bytes = it.address; bytes.size == 4 && bytes[0].toInt() and 255 == 198 && bytes[1].toInt() and 254 == 18 }) {
                    events += "dns:benchmark-address-range"
                }
                }.filter { family == "default" || family == "ipv4" && it is Inet4Address || family == "ipv6" && it is Inet6Address }
            }
            val listener = object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    events += "connect:${if (inetSocketAddress.address is Inet6Address) "ipv6" else "ipv4"}:${proxy.type()}"
                }
                override fun secureConnectStart(call: Call) { events += "tls" }
            }
            val client = OkHttpClient.Builder().dns(dns).eventListener(listener).callTimeout(10, TimeUnit.SECONDS).build()
            val started = System.nanoTime()
            val record = linkedMapOf<String, Any>("target" to target, "family" to family,
                "vpnTransport" to (capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true))
            try {
                client.newCall(Request.Builder().url("https://$host/generate_204").build()).execute().use {
                    record["status"] = it.code
                }
            } catch (failure: IOException) { record["failure"] = failure.javaClass.simpleName }
            finally {
                record["elapsedMs"] = (System.nanoTime() - started) / 1_000_000
                record["events"] = events.toList()
                records += record
                instrumentation.sendStatus(2, Bundle().apply { putString("youtubeConnection", Gson().toJson(record)) })
                File(instrumentation.targetContext.filesDir, "$target-connection.json").writeText(Gson().toJson(records))
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
