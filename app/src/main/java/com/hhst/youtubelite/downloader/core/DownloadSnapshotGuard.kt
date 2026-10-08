package com.hhst.youtubelite.downloader.core

/**
 * Web/native snapshot limits. Exceeding either bound is an explicit error;
 * the payload is never silently truncated.
 */
object DownloadSnapshotGuard {

    private fun estimatedBytes(snapshot: BatchSnapshot): Int {
        var n = 64 + snapshot.name.length * 2 + snapshot.source.name.length
        snapshot.items.forEach { request ->
            n += 160
            n += 2 * (
                request.videoId.length +
                    request.title.length +
                    (request.author?.length ?: 0) +
                    (request.thumbnailUrl?.length ?: 0) +
                    request.config.fingerprint(request.videoId).length
                )
        }
        n += 2 * snapshot.config.fingerprint("").length
        return n
    }

    fun validate(snapshot: BatchSnapshot, payloadBytes: Int? = null): SnapshotReject? {
        if (snapshot.items.size > DownloadLimits.MAX_SNAPSHOT_ITEMS) {
            return SnapshotReject.TooManyItems(snapshot.items.size)
        }
        val bytes = payloadBytes ?: estimatedBytes(snapshot)
        if (bytes > DownloadLimits.MAX_SNAPSHOT_BYTES) {
            return SnapshotReject.TooLarge(bytes)
        }
        return null
    }
}

sealed class SnapshotReject {
    abstract val message: String

    data class TooManyItems(val count: Int) : SnapshotReject() {
        override val message: String =
            "batch snapshot exceeds ${DownloadLimits.MAX_SNAPSHOT_ITEMS} items; refusing without truncation"
    }

    data class TooLarge(val bytes: Int) : SnapshotReject() {
        override val message: String =
            "batch snapshot exceeds ${DownloadLimits.MAX_SNAPSHOT_BYTES} bytes; refusing without truncation"
    }
}
