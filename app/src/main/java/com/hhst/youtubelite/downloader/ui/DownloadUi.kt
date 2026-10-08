package com.hhst.youtubelite.downloader.ui

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.hhst.youtubelite.downloader.core.BatchSnapshot
import com.hhst.youtubelite.downloader.core.DownloadSnapshotGuard
import com.hhst.youtubelite.downloader.core.SnapshotReject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Call-site API. Queue toolbar, player overflow, web injection,
 * extension nav, and system share all open confirm sheets or the manager
 * through these methods — never Worker, Service, or DAO.
 */
object DownloadUi {
    const val EXTRA_BATCH_ID = DownloadActions.EXTRA_BATCH_ID
    const val EXTRA_VIDEO_ID = "videoId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_AUTHOR = "author"
    const val EXTRA_THUMBNAIL = "thumbnailUrl"
    const val EXTRA_SNAPSHOT_ID = "snapshotId"

    fun showSingleConfirm(
        context: Context,
        videoId: String,
        title: String = "",
        author: String? = null,
        thumbnailUrl: String? = null,
    ) {
        context.startActivity(
            DownloadSheetActivity.singleIntent(context, videoId, title, author, thumbnailUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun showBatchConfirm(context: Context, snapshot: BatchSnapshot): DownloadUiStart {
        val reject = DownloadSnapshotGuard.validate(snapshot)
        if (reject != null) return DownloadUiStart.Rejected(reject)
        val id = DownloadUiSessions.put(context, snapshot)
        context.startActivity(
            DownloadSheetActivity.batchIntent(context, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return DownloadUiStart.Started
    }

    fun openManager(context: Context, batchId: String? = null) {
        context.startActivity(
            DownloadActivity.intent(context, batchId = batchId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

sealed class DownloadUiStart {
    data object Started : DownloadUiStart()
    data class Rejected(val reason: SnapshotReject) : DownloadUiStart()
}

internal object DownloadUiSessions {
    private val memory = ConcurrentHashMap<String, BatchSnapshot>()
    private val gson = Gson()

    fun put(context: Context, snapshot: BatchSnapshot): String {
        val id = UUID.randomUUID().toString()
        memory[id] = snapshot
        runCatching {
            file(context, id).writeText(gson.toJson(snapshot))
        }
        return id
    }

    fun get(context: Context, id: String): BatchSnapshot? {
        memory[id]?.let { return it }
        val stored = file(context, id)
        if (!stored.exists()) return null
        return runCatching {
            gson.fromJson(stored.readText(), BatchSnapshot::class.java)
        }.getOrNull()?.also { memory[id] = it }
    }

    private fun file(context: Context, id: String) =
        File(context.cacheDir, "download-snapshot-$id.json")
}
