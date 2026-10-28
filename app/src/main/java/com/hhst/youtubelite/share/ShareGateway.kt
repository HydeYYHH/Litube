package com.hhst.youtubelite.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import com.hhst.youtubelite.R
import android.content.Intent
import android.net.Uri
import android.widget.Toast

class ShareGateway(
    private val activity: Activity,
    private val startChooser: (Intent) -> Unit = activity::startActivity,
) {
    var launchChooser: Boolean = true
    var lastShareIntent: Intent? = null
        private set
    var lastOpenWithIntent: Intent? = null
        private set
    var lastCopiedUrl: String? = null
        private set

    fun share(videoId: String): Boolean {
        val url = VideoShare.urlFor(videoId) ?: return false
        return shareUrl(url)
    }

    fun openWith(videoId: String): Boolean {
        val url = VideoShare.urlFor(videoId) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        lastOpenWithIntent = intent
        if (!launchChooser) return true
        return try {
            startChooser(Intent.createChooser(intent, "Open with"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun sharePlaylist(playlistId: String): Boolean {
        val url = PlaylistShare.urlFor(playlistId) ?: return false
        return shareUrl(url)
    }

    fun shareChannel(channelId: String): Boolean {
        if (channelId.isBlank()) return false
        val path = if (channelId.startsWith("@")) channelId else "channel/$channelId"
        return shareUrl("https://www.youtube.com/$path")
    }

    private fun shareUrl(url: String): Boolean {
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
        Toast.makeText(activity, R.string.link_copied, Toast.LENGTH_SHORT).show()
        return true
    }
}
