package com.hhst.youtubelite.downloader.engine

/**
 * Foreground-service type selection for long-running download work.
 *
 * API 35+ long mux uses [MEDIA_PROCESSING]; save uses [DATA_SYNC]. Transfer on
 * API 34+ is a user-initiated data-transfer Job, not this FGS type. Below API 35
 * the best declared type is dataSync (API 29+); API 26–28 have no FGS types.
 */
object DownloadForegroundTypes {
    /** [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] */
    const val DATA_SYNC = 1

    /** [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING] (API 35) */
    const val MEDIA_PROCESSING = 0x00002000

    const val NONE = 0

    fun forWork(sdk: Int, kind: DownloadWorkKind): Int = when (kind) {
        DownloadWorkKind.TRANSFER -> {
            if (sdk >= 29) DATA_SYNC else NONE
        }
        DownloadWorkKind.MUX, DownloadWorkKind.FINALIZE -> {
            if (sdk >= 35) MEDIA_PROCESSING else if (sdk >= 29) DATA_SYNC else NONE
        }
        DownloadWorkKind.SAVE -> {
            if (sdk >= 29) DATA_SYNC else NONE
        }
    }
}
