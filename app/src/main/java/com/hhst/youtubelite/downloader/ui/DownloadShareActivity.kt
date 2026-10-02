package com.hhst.youtubelite.downloader.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.pip.PipAutoEnter
import com.hhst.youtubelite.downloader.share.DownloadShareOnce
import com.hhst.youtubelite.downloader.share.DownloadShareParser
import com.hhst.youtubelite.downloader.share.DownloadShareTarget

/**
 * Independent share-sheet "Download" entry. Cold start is supported because
 * [com.hhst.youtubelite.App] starts Koin before any activity.
 */
class DownloadShareActivity : Activity() {

    private var pipHandle: PipAutoEnter.Handle? = null
    private var finishWhenPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipHandle = PipAutoEnter.suppress()
        if (savedInstanceState == null) {
            handle(intent)
        } else {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onPause() {
        super.onPause()
        if (finishWhenPaused) finish()
    }

    override fun onDestroy() {
        pipHandle?.restore()
        pipHandle = null
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        if (intent.getBooleanExtra(DownloadShareParser.EXTRA_CONSUMED, false)) {
            finish()
            return
        }
        val target = DownloadShareParser.parse(
            intent.action,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.dataString,
        )
        if (target == null) {
            Toast.makeText(this, R.string.share_no_link_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!DownloadShareOnce.consume(target.dedupeKey, SystemClock.elapsedRealtime())) {
            finish()
            return
        }
        intent.putExtra(DownloadShareParser.EXTRA_CONSUMED, true)
        when (target) {
            is DownloadShareTarget.Video -> {
                DownloadUi.showSingleConfirm(this, target.videoId)
                finishWhenPaused = true
            }
            is DownloadShareTarget.Playlist -> {
                PipAutoEnter.noteLegacyLaunch()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(target.url))
                        .putExtra(DownloadShareParser.EXTRA_PENDING_PLAYLIST, true)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        ),
                )
                finishWhenPaused = true
            }
        }
    }
}
