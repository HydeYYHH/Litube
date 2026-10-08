package com.hhst.youtubelite.downloader.core

/**
 * Download settings: the constants plus the optional prefs hook that
 * transport/finalizer/scheduler read.
 */
object DownloadSettings {
    const val MAX_CONCURRENT_ITEMS = 2
    const val MIN_CONNECTIONS = 1
    const val MAX_CONNECTIONS = 16
    const val DEFAULT_CONNECTIONS = 4
    const val CHUNK_BYTES: Long = 4L * 1024L * 1024L
    const val MAX_CONCURRENT_MUX = 1
    const val MAX_RETRIES = 3
    const val WIFI_ONLY_DEFAULT = false
    val BACKOFF_MS: List<Long> = listOf(1_000L, 3_000L, 10_000L)
}

interface DownloadPrefs {
    fun wifiOnly(): Boolean = DownloadSettings.WIFI_ONLY_DEFAULT
    fun maxConnections(): Int = DownloadSettings.DEFAULT_CONNECTIONS
    fun defaultQuality(): String = "1080p"
    fun lastConfig(): DownloadConfig = DownloadConfig(videoQuality = defaultQuality())

    fun setWifiOnly(value: Boolean) {}
    fun setMaxConnections(value: Int) {}
    fun setDefaultQuality(value: String) {}
    fun setLastConfig(config: DownloadConfig) {}
}

object DefaultDownloadPrefs : DownloadPrefs

/** Foreground resume/confirm counts as a legal UIDT user interaction. */
fun interface DownloadInteraction {
    fun noteUserInitiated()
}
