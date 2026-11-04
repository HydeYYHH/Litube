package com.hhst.youtubelite.downloader.core

import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.diagnostics.*
import kotlinx.serialization.json.Json

class PreferenceDownloadPrefs(
    private val kv: PreferenceStore,
) : DownloadPrefs {
    private val json = Json { ignoreUnknownKeys = true }

    init { AppLog.registerSnapshot("download_settings") { mapOf("wifi_only" to wifiOnly(), "max_connections" to maxConnections(), "default_quality" to defaultQuality()) } }

    override fun wifiOnly(): Boolean = kv.getBool(WIFI, DownloadSettings.WIFI_ONLY_DEFAULT)

    override fun maxConnections(): Int = kv.getInt(CONNECTIONS, DownloadSettings.DEFAULT_CONNECTIONS)
        .coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)

    override fun defaultQuality(): String = kv.getString(QUALITY, "1080p")

    override fun lastConfig(): DownloadConfig {
        val raw = kv.readString(LAST) ?: return DownloadConfig(videoQuality = defaultQuality())
        return runCatching { json.decodeFromString<DownloadConfig>(raw) }
            .onFailure { AppLog.event(AppLog.Category.STORAGE, "download_settings_corrupt", failure = it) }
            .getOrNull()
            ?: DownloadConfig(videoQuality = defaultQuality())
    }

    override fun setWifiOnly(value: Boolean) {
        val previous = wifiOnly()
        kv.putBool(WIFI, value)
        if (previous != value) changed(WIFI, previous, value)
    }

    override fun setMaxConnections(value: Int) {
        val previous = maxConnections()
        val next = value.coerceIn(DownloadSettings.MIN_CONNECTIONS, DownloadSettings.MAX_CONNECTIONS)
        kv.putInt(CONNECTIONS, next)
        if (previous != next) changed(CONNECTIONS, previous, next)
    }

    override fun setDefaultQuality(value: String) {
        val previous = defaultQuality()
        kv.putString(QUALITY, value)
        setLastConfig(lastConfig().copy(videoQuality = value, videoItagHint = null))
        if (previous != value) changed(QUALITY, previous, value)
    }

    override fun setLastConfig(config: DownloadConfig) {
        kv.putString(LAST, json.encodeToString(config))
    }

    private fun changed(key: String, before: Any, after: Any) = AppLog.event(AppLog.Category.EXTENSION,
        "setting_changed", mapOf("key" to key, "before" to before, "after" to after))

    private companion object {
        const val WIFI = "download.wifi_only"
        const val CONNECTIONS = "download.max_connections"
        const val QUALITY = "download.default_quality"
        const val LAST = "download.last_config"
    }
}
