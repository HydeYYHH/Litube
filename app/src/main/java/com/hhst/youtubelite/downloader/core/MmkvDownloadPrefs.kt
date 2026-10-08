package com.hhst.youtubelite.downloader.core

import com.google.gson.Gson
import com.tencent.mmkv.MMKV

/** Download preferences in MMKV under `download.*` keys. */
class MmkvDownloadPrefs(
    private val kv: MMKV,
    private val gson: Gson = Gson(),
) : DownloadPrefs {

    override fun wifiOnly(): Boolean = kv.decodeBool(WIFI, DownloadSettings.WIFI_ONLY_DEFAULT)

    override fun maxConnections(): Int = kv.decodeInt(CONNECTIONS, DownloadSettings.DEFAULT_CONNECTIONS)
        .coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)

    override fun defaultQuality(): String = kv.decodeString(QUALITY, "1080p") ?: "1080p"

    override fun lastConfig(): DownloadConfig {
        val json = kv.decodeString(LAST) ?: return DownloadConfig(videoQuality = defaultQuality())
        return runCatching { gson.fromJson(json, DownloadConfig::class.java) }
            .getOrNull()
            ?: DownloadConfig(videoQuality = defaultQuality())
    }

    override fun setWifiOnly(value: Boolean) {
        kv.encode(WIFI, value)
    }

    override fun setMaxConnections(value: Int) {
        kv.encode(
            CONNECTIONS,
            value.coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS),
        )
    }

    override fun setDefaultQuality(value: String) {
        kv.encode(QUALITY, value)
    }

    override fun setLastConfig(config: DownloadConfig) {
        kv.encode(LAST, gson.toJson(config))
    }

    private companion object {
        const val WIFI = "download.wifi_only"
        const val CONNECTIONS = "download.max_connections"
        const val CHUNK = "download.chunk_bytes"
        const val QUALITY = "download.default_quality"
        const val LAST = "download.last_config"
    }
}
