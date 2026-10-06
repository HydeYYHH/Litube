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
