package com.hhst.youtubelite.downloader.core

import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import com.hhst.youtubelite.diagnostics.*

/** Download preferences in MMKV under `download.*` keys. */
class MmkvDownloadPrefs(
    private val kv: MMKV,
    private val gson: Gson = Gson(),
) : DownloadPrefs {
    init { AppLog.registerSnapshot("download_settings") { mapOf("wifi_only" to wifiOnly(), "max_connections" to maxConnections(), "default_quality" to defaultQuality()) } }

    override fun wifiOnly(): Boolean = kv.decodeBool(WIFI, DownloadSettings.WIFI_ONLY_DEFAULT)

    override fun maxConnections(): Int = kv.decodeInt(CONNECTIONS, DownloadSettings.DEFAULT_CONNECTIONS)
        .coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)

    override fun defaultQuality(): String = kv.decodeString(QUALITY, "1080p") ?: "1080p"

    override fun lastConfig(): DownloadConfig {
        val json = kv.decodeString(LAST) ?: return DownloadConfig(videoQuality = defaultQuality())
        return runCatching { gson.fromJson(json, DownloadConfig::class.java) }
            .onFailure { AppLog.event(AppLog.Category.STORAGE, "download_settings_corrupt", failure = it) }
            .getOrNull()
            ?: DownloadConfig(videoQuality = defaultQuality())
    }

    override fun setWifiOnly(value: Boolean) {
        val previous = wifiOnly()
        if (!kv.encode(WIFI, value)) AppLog.event(AppLog.Category.STORAGE, "setting_write_failed", mapOf("key" to WIFI), critical = true)
        else if (previous != value) changed(WIFI, previous, value)
    }

    override fun setMaxConnections(value: Int) {
        val previous = maxConnections()
        val next = value.coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)
        if (!kv.encode(
            CONNECTIONS,
            next,
        )) AppLog.event(AppLog.Category.STORAGE, "setting_write_failed", mapOf("key" to CONNECTIONS), critical = true)
        else if (previous != next) changed(CONNECTIONS, previous, next)
    }

    override fun setDefaultQuality(value: String) {
        val previous = defaultQuality()
        if (!kv.encode(QUALITY, value)) AppLog.event(AppLog.Category.STORAGE, "setting_write_failed", mapOf("key" to QUALITY), critical = true)
        else if (previous != value) changed(QUALITY, previous, value)
    }

    override fun setLastConfig(config: DownloadConfig) {
        if (!kv.encode(LAST, gson.toJson(config))) AppLog.event(AppLog.Category.STORAGE, "setting_write_failed", mapOf("key" to LAST), critical = true)
    }
    private fun changed(key: String, before: Any, after: Any) = AppLog.event(AppLog.Category.EXTENSION,
        "setting_changed", mapOf("key" to key, "before" to before, "after" to after))

    private companion object {
        const val WIFI = "download.wifi_only"
        const val CONNECTIONS = "download.max_connections"
        const val CHUNK = "download.chunk_bytes"
        const val QUALITY = "download.default_quality"
        const val LAST = "download.last_config"
    }
}
