package com.hhst.youtubelite.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ShareGateway(
    private val activity: Activity,
    private val startChooser: (Intent) -> Unit = activity::startActivity,
) {
    var launchChooser: Boolean = true
    var lastShareIntent: Intent? = null
        private set
    var lastCopiedUrl: String? = null
        private set

    fun share(videoId: String): Boolean {
        val url = VideoShare.urlFor(videoId) ?: return false
        val intent = VideoShare.sendIntent(url)
        lastShareIntent = intent
        if (!launchChooser) return true
        return try {
            startChooser(Intent.createChooser(intent, "Share"))
            true
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(url)
        } catch (_: SecurityException) {
            false
        }
    }

    fun copyLink(videoId: String): Boolean {
        val url = VideoShare.urlFor(videoId) ?: return false
        return copyToClipboard(url)
    }

    private fun copyToClipboard(url: String): Boolean {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Video link", url))
        } catch (_: SecurityException) {
            return false
        }
        lastCopiedUrl = url
        Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show()
        return true
    }
}
