package com.hhst.youtubelite.downloader.work

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
            // HTTP byte transfer: dataSync is the closest FGS type on API 29–33.
            // API 34+ transfer is UIDT (see BackgroundDownloadScheduler).
            if (sdk >= 29) DATA_SYNC else NONE
        }
        DownloadWorkKind.MUX, DownloadWorkKind.FINALIZE -> {
            // Long mux/verify: mediaProcessing is required on API 35+.
            if (sdk >= 35) MEDIA_PROCESSING else if (sdk >= 29) DATA_SYNC else NONE
        }
        DownloadWorkKind.SAVE -> {
            // MediaStore / local copy is dataSync on API 29+.
            if (sdk >= 29) DATA_SYNC else NONE
        }
    }
}
