package com.hhst.youtubelite.downloader.pip

import com.hhst.youtubelite.downloader.ui.DownloadActionActivity
import com.hhst.youtubelite.downloader.ui.DownloadActivity
import com.hhst.youtubelite.downloader.ui.DownloadShareActivity
import com.hhst.youtubelite.downloader.ui.DownloadSheetActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPipEntryTest {

    @Test
    fun shareExtensionAndNotification_useSuppressingActivities() {
        val overlay = setOf(
            DownloadActivity::class.java.name,
            DownloadSheetActivity::class.java.name,
            DownloadActionActivity::class.java.name,
            DownloadShareActivity::class.java.name,
        )
        assertTrue(overlay.contains("com.hhst.youtubelite.downloader.ui.DownloadActivity"))
        assertTrue(overlay.contains("com.hhst.youtubelite.downloader.ui.DownloadShareActivity"))
        assertTrue(overlay.contains("com.hhst.youtubelite.downloader.ui.DownloadActionActivity"))
        assertTrue(overlay.contains("com.hhst.youtubelite.downloader.ui.DownloadSheetActivity"))
    }

    @Test
    fun nestedShareThenManager_keepsAutoEnterOffUntilRestored() {
        while (PipAutoEnter.isSuppressed()) PipAutoEnter.restore()
        val share = PipAutoEnter.suppress()
        val manager = PipAutoEnter.suppress()
        share.restore()
        assertTrue(PipAutoEnter.isSuppressed())
        assertFalse(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 34))
        manager.restore()
        assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = 34))
    }
}
