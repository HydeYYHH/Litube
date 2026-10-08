package com.hhst.youtubelite.downloader.engine

/** Test double recording notification port traffic. Lives in the test source set. */
class RecordingNotificationPort(
    var enabled: Boolean = true,
) : DownloadNotificationPort {
    val posted = mutableListOf<Pair<Int, DownloadNotificationPayload>>()

    override fun notify(id: Int, payload: DownloadNotificationPayload) {
        if (!enabled) return
        posted += id to payload
    }

    override fun cancel(id: Int) = Unit

    override fun areNotificationsEnabled(): Boolean = enabled
}
