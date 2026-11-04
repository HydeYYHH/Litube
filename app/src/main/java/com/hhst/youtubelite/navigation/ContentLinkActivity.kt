package com.hhst.youtubelite.navigation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hhst.youtubelite.downloader.core.DownloadShareParser
import com.hhst.youtubelite.home.HomeActivity

/** External playback does not need to initialize the home feed first. */
class ContentLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            DownloadShareParser.extractUrl(intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT))
        } else null
        val link = url?.let(ContentLink::parse)
        if (link is ContentLink.Video || link is ContentLink.Short) {
            val destination = link.intent(this)
            if (isTaskRoot) {
                startActivities(arrayOf(Intent(this, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_DEFER_HOME, true), destination))
            } else startActivity(destination)
        } else {
            startActivity(Intent(intent).setClass(this, HomeActivity::class.java))
        }
        finish()
    }
}
