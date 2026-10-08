package com.hhst.youtubelite.downloader.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.hhst.youtubelite.downloader.io.DownloadPublishedUris

/** Builds a share Intent that actually grants FileProvider read to the chosen app. */
object DownloadFileShare {

    fun parseOpenable(raw: String?): Uri? {
        if (!DownloadPublishedUris.isOpenable(raw)) return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun viewIntent(uri: Uri, mime: String = "*/*"): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun sendIntent(uri: Uri, title: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            clipData = ClipData.newRawUri(title, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun grantsRead(flags: Int, hasClipData: Boolean): Boolean {
        val granted = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        return granted == Intent.FLAG_GRANT_READ_URI_PERMISSION && hasClipData
    }
}
