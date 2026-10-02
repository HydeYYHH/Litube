package com.hhst.youtubelite.downloader.notify

data class DownloadNotificationPayload(
    val batchId: String,
    val title: String,
    val text: String,
    val progress: Int,
    val ongoing: Boolean,
    val complete: Boolean,
    val showPause: Boolean,
    val showCancel: Boolean,
    val showResume: Boolean,
)

interface DownloadNotificationPort {
    fun notify(id: Int, payload: DownloadNotificationPayload)
    fun cancel(id: Int)
    fun areNotificationsEnabled(): Boolean
}

object NoOpNotificationPort : DownloadNotificationPort {
    override fun notify(id: Int, payload: DownloadNotificationPayload) = Unit
    override fun cancel(id: Int) = Unit
    override fun areNotificationsEnabled(): Boolean = true
}

class RecordingNotificationPort(
    var enabled: Boolean = true,
) : DownloadNotificationPort {
    val posted = mutableListOf<Pair<Int, DownloadNotificationPayload>>()
    val cancelled = mutableListOf<Int>()

    override fun notify(id: Int, payload: DownloadNotificationPayload) {
        if (!enabled) return
        posted += id to payload
    }

    override fun cancel(id: Int) {
        cancelled += id
    }

    override fun areNotificationsEnabled(): Boolean = enabled
}
