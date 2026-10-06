package com.hhst.youtubelite.downloader.ui

import android.content.Intent
import com.hhst.youtubelite.downloader.io.DownloadPublishedUris
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileShareTest {

    @Test
    fun shareIntent_grantsReadViaClipData() {
        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        assertTrue(DownloadFileShare.grantsRead(flag, hasClipData = true))
        assertFalse(DownloadFileShare.grantsRead(0, hasClipData = true))
        assertFalse(DownloadFileShare.grantsRead(flag, hasClipData = false))
    }

    @Test
    fun invalidUri_isNotOpenable() {
        assertNull(DownloadFileShare.parseOpenable(null))
        assertNull(DownloadFileShare.parseOpenable(""))
        assertNull(DownloadFileShare.parseOpenable("not-a-uri"))
        assertNull(DownloadFileShare.parseOpenable("http://example.com/clip.mp4"))
        assertNull(DownloadFileShare.parseOpenable("content://"))
        assertTrue(
            DownloadPublishedUris.isOpenable(
                "content://com.hhst.youtubelite.download.fileprovider/downloads/clip.mp4",
            ),
        )
        assertTrue(
            DownloadPublishedUris.isOpenable("file:///storage/emulated/0/Download/a.mp4"),
        )
    }
}
